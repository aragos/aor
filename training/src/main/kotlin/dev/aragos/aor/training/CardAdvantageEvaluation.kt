package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.CardAdvantageModel
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardFrequencyModel
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.CardProposals
import java.nio.file.Path

/**
 * Explicit world, role and arm; no padded world executions or role inference from list position.
 */
internal data class CardAdvantageEvaluationJob(
    val index: Int,
    val seed: Int,
    val seat: Int,
    val arm: String,
    val policy: String,
    val rivals: List<String>,
) {
    init {
        require(
            index >= 0 &&
                seat in 0..3 &&
                arm.matches(Regex("[a-z][a-z0-9-]{0,63}")) &&
                rivals.size == 4
        ) {
            "Evaluation jobs need explicit bounded index, role, arm and four rival slots."
        }
    }
}

internal data class CardAdvantageEvaluationPolicy(
    val kind: String,
    val path: Path?,
    val sha256: String?,
    val advantage: CardAdvantageModel? = null,
    val frequency: CardFrequencyModel? = null,
    val economy: JointEconomyPolicy? = null,
    val cardActor: CardPolicyModel? = null,
    val jointBundle: JointActorBundle? = null,
    val territoryBundle: TerritoryActorBundle? = null,
    val territoryValueBundle: TerritoryValueBundle? = null,
) {
    fun metadata(): Map<String, Any?> =
        mapOf("kind" to kind, "path" to path?.toString(), "sha256" to sha256) +
            (jointBundle?.let { mapOf("bundle" to it.metadata()) } ?: emptyMap()) +
            (territoryBundle?.let {
                mapOf("bundle" to it.metadata(), "frozenCardPurchaseBundle" to it.frozen.metadata())
            } ?: emptyMap()) +
            (territoryValueBundle?.let {
                mapOf(
                    "bundle" to it.metadata(),
                    "frozenCardPurchaseBundle" to it.frozen.metadata(),
                    "epsilon" to 0.0,
                )
            } ?: emptyMap())

    /** Registry adapters share the collector's actor, purchase prior and decision scope exactly. */
    fun controller(environment: CardAdvantageEnvironment): SimulationController =
        when {
            territoryValueBundle != null ->
                territoryValueBundle.controller(environment.baseline, 0.0)
            territoryBundle != null -> territoryBundle.controller(environment.baseline)
            jointBundle != null -> jointBundle.controller(environment.baseline)
            economy != null -> JointEconomyController(environment, economy)
            cardActor != null ->
                CardActorController(
                    environment.baseline.model,
                    cardActor,
                    environment.purchase.model,
                )
            else -> CardAdvantageController(environment, advantage, frequency)
        }
}

/** Named frozen policy registry and explicit world/role/arm jobs. */
internal data class CardAdvantageEvaluationInput(
    val source: Path,
    val sourceSha256: String,
    val environment: CardAdvantageEnvironment,
    val policies: Map<String, CardAdvantageEvaluationPolicy>,
    val jobs: List<CardAdvantageEvaluationJob>,
) {
    init {
        require(jobs.size in 1..8192 && jobs.map { it.index } == jobs.indices.toList()) {
            "Evaluation job indices must be contiguous and bounded."
        }
        require(jobs.map { Triple(it.seed, it.seat, it.arm) }.distinct().size == jobs.size) {
            "Evaluation may not repeat a world/role/arm cell."
        }
        require(jobs.all { it.policy in policies && it.rivals.all(policies::containsKey) }) {
            "Every evaluation role requires a named frozen policy."
        }
    }

    fun signature(): String =
        trainingSignature(
            listOf(
                    "card-advantage-evaluate-1",
                    source,
                    sourceSha256,
                    environment.baseline.sha256,
                    environment.purchase.sha256,
                    policies.mapValues { it.value.sha256 },
                    CardAdvantagePolicy.SELECTOR_VERSION,
                    CardProposals.VERSION,
                )
                .joinToString("\n") +
                (if (policies.values.any { it.economy != null }) {
                    "\n${JointEconomyPolicy.VERSION}"
                } else "") +
                (if (policies.values.any { it.cardActor != null }) {
                    "\n${CardPolicy.ACTOR_VERSION}"
                } else "") +
                (if (policies.values.any { it.jointBundle != null }) {
                    "\n${JointActorBundle.VERSION}"
                } else "") +
                (if (policies.values.any { it.territoryBundle != null }) {
                    "\n${TerritoryActorBundle.VERSION}"
                } else "") +
                (if (policies.values.any { it.territoryValueBundle != null }) {
                    "\n${TerritoryValueBundle.VERSION}"
                } else "")
        )

    fun manifest(): Map<String, Any?> =
        mapOf(
            "kind" to "card-advantage-evaluate",
            "schemaVersion" to 1,
            "signature" to signature(),
            "rulesVersion" to RULES_VERSION,
            "dataVersion" to DATA_VERSION,
            "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
            "selectorVersion" to CardAdvantagePolicy.SELECTOR_VERSION,
            "proposalVersion" to CardProposals.VERSION,
            "trajectoryEncoding" to CardTrajectoryFiles.ENCODING,
            "source" to source.toString(),
            "sourceSha256" to sourceSha256,
            "configuration" to environment.configuration.id,
            "baseline" to
                mapOf(
                    "path" to environment.baseline.path.toString(),
                    "sha256" to environment.baseline.sha256,
                ),
            "frozenPurchasePolicy" to
                mapOf(
                    "path" to environment.purchase.path.toString(),
                    "sha256" to environment.purchase.sha256,
                ),
            "policies" to policies.mapValues { it.value.metadata() },
            "jobs" to jobs,
        ) +
            (if (policies.values.any { it.economy != null }) {
                mapOf("economyVersion" to JointEconomyPolicy.VERSION)
            } else emptyMap()) +
            (if (policies.values.any { it.cardActor != null }) {
                mapOf("cardActorVersion" to CardPolicy.ACTOR_VERSION)
            } else emptyMap()) +
            (if (policies.values.any { it.jointBundle != null }) {
                mapOf("jointActorVersion" to JointActorBundle.VERSION)
            } else emptyMap()) +
            (if (policies.values.any { it.territoryBundle != null }) {
                mapOf("territoryActorVersion" to TerritoryActorBundle.VERSION)
            } else emptyMap()) +
            (if (policies.values.any { it.territoryValueBundle != null }) {
                mapOf("territoryValueVersion" to TerritoryValueBundle.VERSION)
            } else emptyMap())

    companion object {
        fun read(path: Path): CardAdvantageEvaluationInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "configuration",
                    "baseline",
                    "frozenPurchasePolicy",
                    "policies",
                    "jobs",
                ),
            )
            require(PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1) {
                "Advantage evaluation requires schemaVersion 1."
            }
            val environment = CardAdvantageEnvironment.read(source, node)
            val registry = node.required("policies")
            require(registry.isObject && registry.size() in 1..16) {
                "Evaluation requires a bounded named policy registry."
            }
            val policies =
                registry.properties().associate { (name, row) ->
                    require(name.matches(Regex("[a-z][a-z0-9-]{0,63}"))) {
                        "Policy registry names must be safe simple identifiers."
                    }
                    val kind = PurchaseJson.text(row.required("kind"), "kind")
                    name to
                        if (kind == "baseline") {
                            PurchaseJson.fields(row, setOf("kind"))
                            CardAdvantageEvaluationPolicy(kind, null, null)
                        } else {
                            PurchaseJson.fields(
                                row,
                                if (kind == "territory-value-bundle")
                                    setOf("kind", "path", "frozenCardPurchaseBundle", "epsilon")
                                else if (kind == "territory-bundle")
                                    setOf("kind", "path", "frozenCardPurchaseBundle")
                                else setOf("kind", "path"),
                            )
                            val text = PurchaseJson.text(row.required("path"), "path")
                            if (kind == "territory-bundle" || kind == "territory-value-bundle") {
                                val frozen =
                                    JointActorBundle.read(
                                        source.parent
                                            .resolve(
                                                PurchaseJson.text(
                                                    row.required("frozenCardPurchaseBundle"),
                                                    "frozenCardPurchaseBundle",
                                                )
                                            )
                                            .normalize(),
                                        environment.configuration,
                                        environment.baseline,
                                    )
                                if (kind == "territory-value-bundle") {
                                    require(
                                        PurchaseJson.number(row.required("epsilon"), "epsilon") ==
                                            0.0
                                    ) {
                                        "Territory action-value evaluation must explicitly use epsilon zero."
                                    }
                                    val bundle =
                                        TerritoryValueBundle.read(
                                            source.parent.resolve(text).normalize(),
                                            environment.configuration,
                                            environment.baseline,
                                            frozen,
                                        )
                                    CardAdvantageEvaluationPolicy(
                                        kind,
                                        bundle.path,
                                        bundle.sha256,
                                        territoryValueBundle = bundle,
                                    )
                                } else {
                                    val bundle =
                                        TerritoryActorBundle.read(
                                            source.parent.resolve(text).normalize(),
                                            environment.configuration,
                                            environment.baseline,
                                            frozen,
                                        )
                                    CardAdvantageEvaluationPolicy(
                                        kind,
                                        bundle.path,
                                        bundle.sha256,
                                        territoryBundle = bundle,
                                    )
                                }
                            } else if (kind == "advantage") {
                                val model = environment.readAdvantage(source, text)
                                CardAdvantageEvaluationPolicy(
                                    kind,
                                    model.path,
                                    model.sha256,
                                    advantage = model.model,
                                )
                            } else if (kind == "joint-bundle") {
                                val bundle =
                                    JointActorBundle.read(
                                        source.parent.resolve(text).normalize(),
                                        environment.configuration,
                                        environment.baseline,
                                    )
                                CardAdvantageEvaluationPolicy(
                                    kind,
                                    bundle.path,
                                    bundle.sha256,
                                    jointBundle = bundle,
                                )
                            } else if (kind == "card-actor") {
                                val file = source.parent.resolve(text).normalize().toRealPath()
                                val fingerprint = PurchaseJson.hash(file)
                                val model = CardPolicyInput.read(file)
                                require(
                                    model.stateFeatureNames ==
                                        environment.dictionary.stateFeatureNames &&
                                        model.actionFeatureNames ==
                                            environment.dictionary.actionFeatureNames
                                ) {
                                    "An evaluation card actor must retain this setup's complete dictionaries."
                                }
                                require(PurchaseJson.hash(file) == fingerprint) {
                                    "An evaluation card actor changed while being read."
                                }
                                CardAdvantageEvaluationPolicy(
                                    kind,
                                    file,
                                    fingerprint,
                                    cardActor = model,
                                )
                            } else if (kind == "economy") {
                                val file = source.parent.resolve(text).normalize().toRealPath()
                                val fingerprint = PurchaseJson.hash(file)
                                val model = TrainingArtifacts.readModel(file)
                                val economy = JointEconomyPolicy(environment.baseline.model, model)
                                require(PurchaseJson.hash(file) == fingerprint) {
                                    "An economy model changed while being read."
                                }
                                CardAdvantageEvaluationPolicy(
                                    kind,
                                    file,
                                    fingerprint,
                                    economy = economy,
                                )
                            } else {
                                require(kind == "frequency") {
                                    "Unknown evaluation policy kind: $kind."
                                }
                                val file = source.parent.resolve(text).normalize().toRealPath()
                                val fingerprint = PurchaseJson.hash(file)
                                val model = frequency(PurchaseJson.read(file))
                                require(PurchaseJson.hash(file) == fingerprint) {
                                    "A frequency model changed while being read."
                                }
                                CardAdvantageEvaluationPolicy(
                                    kind,
                                    file,
                                    fingerprint,
                                    frequency = model,
                                )
                            }
                        }
                }
            val jobs =
                PurchaseJson.array(node.required("jobs"), "jobs").map { row ->
                    PurchaseJson.fields(
                        row,
                        setOf("index", "seed", "seat", "arm", "policy", "rivals"),
                    )
                    CardAdvantageEvaluationJob(
                        index = PurchaseJson.integer(row.required("index"), "index"),
                        seed = PurchaseJson.integer(row.required("seed"), "seed"),
                        seat = PurchaseJson.integer(row.required("seat"), "seat"),
                        arm = PurchaseJson.text(row.required("arm"), "arm"),
                        policy = PurchaseJson.text(row.required("policy"), "policy"),
                        rivals = PurchaseJson.strings(row.required("rivals"), "rivals"),
                    )
                }
            require(PurchaseJson.hash(source) == hash) {
                "An evaluation source changed while being read."
            }
            return CardAdvantageEvaluationInput(source, hash, environment, policies, jobs)
        }

        private fun frequency(node: JsonNode): CardFrequencyModel {
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "encoderVersion",
                    "selectorVersion",
                    "proposalVersion",
                    "passProbability",
                    "otherProbability",
                ),
            )
            return CardFrequencyModel(
                schemaVersion =
                    PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion"),
                encoderVersion =
                    PurchaseJson.text(node.required("encoderVersion"), "encoderVersion"),
                selectorVersion =
                    PurchaseJson.text(node.required("selectorVersion"), "selectorVersion"),
                proposalVersion =
                    PurchaseJson.text(node.required("proposalVersion"), "proposalVersion"),
                passProbability =
                    PurchaseJson.number(node.required("passProbability"), "passProbability"),
                otherProbability =
                    PurchaseJson.number(node.required("otherProbability"), "otherProbability"),
            )
        }
    }
}

internal data class CardAdvantageEvaluationResult(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val seed: Int,
    val controlledSeat: Int,
    val arm: String,
    val terminal: PurchaseTerminal,
)

/** Complete repeated-policy games use the collector's owner view and exact purchase controller. */
internal class CardAdvantageEvaluator(
    private val input: CardAdvantageEvaluationInput,
    private val deadlineNanos: Long,
) {
    fun evaluate(index: Int): CardAdvantageEvaluationResult {
        require(index in input.jobs.indices) {
            "Evaluation index must belong to its frozen source."
        }
        val job = input.jobs[index]
        val controllers: Map<Int, SimulationController> =
            (0..3).associateWith { seat ->
                val name = if (seat == job.seat) job.policy else job.rivals[seat]
                val policy =
                    requireNotNull(input.policies[name]) { "A role requires a declared policy." }
                policy.controller(input.environment)
            }
        val game =
            Simulation()
                .play(
                    configuration = input.environment.configuration,
                    seed = job.seed,
                    competitors =
                        List(4) {
                            Competitor(input.environment.baseline.model, BotDifficulty.HARD)
                        },
                    deadlineNanos = deadlineNanos,
                    controllers = controllers,
                )
        return CardAdvantageEvaluationResult(
            signature = input.signature(),
            index = index,
            seed = job.seed,
            controlledSeat = job.seat,
            arm = job.arm,
            terminal =
                PurchaseTerminal(
                    game.reward(job.seat),
                    game.winShare(job.seat),
                    game.rankReward(job.seat),
                    game.scores.map(PilotReplayScore::from),
                    game.steps,
                ),
        )
    }

    fun validate(node: JsonNode, index: Int) {
        val job = input.jobs[index]
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "signature",
                "index",
                "seed",
                "controlledSeat",
                "arm",
                "terminal",
            ),
        )
        require(
            PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                node.required("signature").asText() == input.signature() &&
                PurchaseJson.integer(node.required("index"), "index") == index &&
                PurchaseJson.integer(node.required("seed"), "seed") == job.seed &&
                PurchaseJson.integer(node.required("controlledSeat"), "controlledSeat") ==
                    job.seat &&
                node.required("arm").asText() == job.arm
        ) {
            "A cached evaluation must retain its exact world, role, arm and frozen policies."
        }
        PurchaseJson.fields(
            node.required("terminal"),
            setOf("reward", "winShare", "rankReward", "scores", "gameDecisions"),
        )
        CardAdvantageArtifacts.terminal(
            node.required("terminal"),
            job.seat,
            input.environment.configuration.id,
        )
    }
}
