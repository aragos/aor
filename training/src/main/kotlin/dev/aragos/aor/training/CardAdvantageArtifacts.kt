package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ScoreView
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals
import kotlin.math.abs

/** Validate immutable cached origins before they can be reused as labels. */
internal object CardAdvantageArtifacts {
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun validate(node: JsonNode, input: CardAdvantageCollectionInput, index: Int) {
        val request = input.origins[index]
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "signature",
                "index",
                "split",
                "seed",
                "seat",
                "status",
                "skipReason",
                "request",
                "backboneDecisions",
                "backboneCanonicalSha256",
                "observations",
                "groups",
                "outcomes",
            ),
        )
        require(
            integer(node, "schemaVersion") == 1 &&
                node.required("signature").asText() == input.signature() &&
                integer(node, "index") == index &&
                integer(node, "seed") == request.seed &&
                integer(node, "seat") == request.seat &&
                node.required("split").asText() == request.split &&
                node.required("request") == PurchaseJson.mapper.valueToTree<JsonNode>(request)
        ) {
            "An advantage origin must preserve its exact source, continuation, request and native signature."
        }
        require(
            integer(node, "backboneDecisions") in 1..12000 &&
                hashPattern.matches(node.required("backboneCanonicalSha256").asText())
        ) {
            "Backbone replay provenance is invalid."
        }
        val observationsNode = node.required("observations")
        val outcomes = node.required("outcomes")
        val groups = PurchaseJson.array(node.required("groups"), "groups")
        require(
            observationsNode.isObject &&
                outcomes.isObject &&
                outcomes.size() <= 52 &&
                groups.size <= 13
        ) {
            "Advantage origin payloads exceed their fixed bound."
        }
        if (node.required("status").asText() == "skipped") {
            require(
                node.required("skipReason").asText() == "requested-root-not-reached" &&
                    observationsNode.isEmpty &&
                    outcomes.isEmpty &&
                    groups.isEmpty()
            ) {
                "Skipped requests may not contain partial targets or replacement roots."
            }
            return
        }
        require(
            node.required("status").asText() == "collected" &&
                node.required("skipReason").isNull &&
                groups.isNotEmpty()
        ) {
            "An advantage origin must be complete or explicitly skipped."
        }
        val observations =
            observationsNode.properties().associate { (hash, document) ->
                val observation = CardTrajectoryValidation.observation(document)
                require(
                    CardAdvantageExecutor.hash(observation) == hash &&
                        CardAdvantagePolicy.supports(observation)
                ) {
                    "A safe observation must preserve its exact JSON hash and normal-window scope."
                }
                require(
                    observation.stateFeatures.keys.all {
                        it in input.environment.dictionary.stateFeatureNames
                    } &&
                        observation.actions.all { action ->
                            action.features.keys.all {
                                it in input.environment.dictionary.actionFeatureNames
                            }
                        }
                ) {
                    "Unknown features may not enter the frozen advantage dictionary."
                }
                hash to observation
            }
        val roots = groups.filter { it.required("kind").asText() == "root" }
        require(
            roots.size == 1 &&
                roots.single().required("id").asText() == "root" &&
                groups.map { it.required("id").asText() }.distinct().size == groups.size
        ) {
            "Every collected world requires exactly one root and unique decision groups."
        }
        val root = roots.single()
        require(
            integer(root, "epoch") == request.epoch &&
                integer(root, "turn") >= 1 &&
                root.required("parentActionIndex").isNull &&
                root.required("replicate").isNull &&
                root.required("prefixCanonicalSha256").isNull
        ) {
            "The root must belong to its requested epoch and have no invented parent."
        }
        val childIdentities =
            groups
                .filter { it.required("kind").asText() == "child" }
                .map {
                    integer(it, "parentActionIndex") to integer(it, "replicate")
                }
        require(childIdentities.distinct().size == childIdentities.size) {
            "Each parent action and paired stream may have only one child decision group."
        }
        val referencedOutcomes = mutableSetOf<String>()
        groups.forEach { group ->
            PurchaseJson.fields(
                group,
                setOf(
                    "id",
                    "kind",
                    "observationSha256",
                    "number",
                    "botSeed",
                    "turn",
                    "epoch",
                    "proposalIndices",
                    "referenceIndex",
                    "returns",
                    "parentActionIndex",
                    "replicate",
                    "continuationIndex",
                    "prefixCanonicalSha256",
                ),
            )
            val observation =
                requireNotNull(observations[group.required("observationSha256").asText()]) {
                    "Every group must refer to its stored safe observation."
                }
            val number = integer(group, "number")
            val seed = integer(group, "botSeed")
            require(
                number in 0..11999 &&
                    seed == CardAdvantageExecutor.policySeed(request.seed, number, request.seat)
            ) {
                "A normal card group must preserve the absolute Simulation decision seed."
            }
            val proposals =
                PurchaseJson.array(group.required("proposalIndices"), "proposalIndices").map {
                    PurchaseJson.integer(it, "proposal index")
                }
            require(
                proposals == CardProposals.indices(observation, seed) &&
                    integer(group, "referenceIndex") == observation.baselineIndex
            ) {
                "A decision group must retain shared proposals and explicit F reference."
            }
            val pi =
                input.continuation?.model?.let {
                    CardAdvantagePolicy.choose(observation, it, seed).index
                } ?: observation.baselineIndex
            require(integer(group, "continuationIndex") == pi && pi in proposals) {
                "Stored continuation reuse must match the exact frozen selector and seed."
            }
            val returns = PurchaseJson.array(group.required("returns"), "returns")
            require(returns.map { integer(it, "actionIndex") } == proposals) {
                "All and only the shared proposals require targets."
            }
            val child = group.required("kind").asText() == "child"
            require(child || group.required("kind").asText() == "root") {
                "Unknown advantage decision group."
            }
            if (child) {
                require(
                    number > integer(root, "number") &&
                        integer(group, "turn") == integer(root, "turn") &&
                        integer(group, "epoch") == integer(root, "epoch") &&
                        hashPattern.matches(group.required("prefixCanonicalSha256").asText())
                ) {
                    "A child must remain in its original normal card window."
                }
                val parent = integer(group, "parentActionIndex")
                val replicate = integer(group, "replicate")
                require(replicate in 0..3) { "A child requires its paired future-stream index." }
                val parentReturns =
                    root
                        .required("returns")
                        .single { integer(it, "actionIndex") == parent }
                        .required("outcomeIds")
                val reused =
                    returns
                        .single { integer(it, "actionIndex") == pi }
                        .required("outcomeIds")
                        .single()
                        .asText()
                require(parentReturns[replicate].asText() == reused) {
                    "The root pi target must reuse the exact selected child outcome."
                }
            }
            returns.forEach { row ->
                PurchaseJson.fields(row, setOf("actionIndex", "outcomeIds"))
                val ids = PurchaseJson.strings(row.required("outcomeIds"), "outcomeIds")
                require(ids.size == if (child) 1 else 4) {
                    "Roots require four paired streams and exact children require one."
                }
                ids.forEachIndexed { replicate, id ->
                    val outcome =
                        requireNotNull(outcomes[id]) {
                            "Every action target requires its complete stored tail."
                        }
                    val expectedReplicate = if (child) integer(group, "replicate") else replicate
                    require(
                        outcome.required("chanceSeed").isIntegralNumber &&
                            outcome.required("chanceSeed").longValue() ==
                                CardAdvantageExecutor.chanceSeed(request.seed, expectedReplicate)
                    ) {
                        "Action comparisons must retain the same paired future stream."
                    }
                    referencedOutcomes.add(id)
                }
            }
        }
        require(referencedOutcomes == outcomes.properties().map { it.key }.toSet()) {
            "Origin outcomes may not contain unlabeled extra rollouts."
        }
        outcomes.properties().forEach { (_, value) ->
            PurchaseJson.fields(
                value,
                setOf(
                    "reward",
                    "winShare",
                    "rankReward",
                    "scores",
                    "gameDecisions",
                    "chanceSeed",
                    "canonicalSha256",
                ),
            )
            require(hashPattern.matches(value.required("canonicalSha256").asText())) {
                "A tail requires its verified canonical replay hash."
            }
            terminal(value, request.seat, input.environment.configuration.id)
        }
    }

    fun terminal(node: JsonNode, seat: Int, configuration: String) {
        val scores =
            PurchaseJson.array(node.required("scores"), "scores").mapIndexed { index, score ->
                PurchaseJson.fields(
                    score,
                    setOf("player", "advancePoints", "cash", "misery", "total", "winner"),
                )
                require(
                    score.required("player").asText() == "p${index + 1}" &&
                        score.required("winner").isBoolean
                ) {
                    "Terminal scores require explicit registration identities and winner flags."
                }
                val misery =
                    score
                        .required("misery")
                        .takeUnless { it.isNull }
                        ?.let { PurchaseJson.integer(it, "misery") }
                val total =
                    score
                        .required("total")
                        .takeUnless { it.isNull }
                        ?.let { PurchaseJson.integer(it, "total") }
                val cash = integer(score, "cash")
                val advance = integer(score, "advancePoints")
                require(total?.toLong() == misery?.let { advance.toLong() + cash - it }) {
                    "Public score arithmetic differs."
                }
                ScoreView(
                    PlayerId("p${index + 1}"),
                    advance,
                    cash,
                    misery,
                    total,
                    score.required("winner").booleanValue(),
                )
            }
        require(scores.size == 4 && integer(node, "gameDecisions") in 1..12000) {
            "A complete game requires four scores and bounded decisions."
        }
        val maximum = scores.mapNotNull { it.total }.maxOrNull()
        require(scores.all { it.winner == (it.total != null && it.total == maximum) }) {
            "Winner flags differ from public terminal totals."
        }
        val game =
            SimulatedGame(
                configuration,
                0,
                integer(node, "gameDecisions"),
                scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0L,
            )
        listOf(
                "reward" to game.reward(seat),
                "winShare" to game.winShare(seat),
                "rankReward" to game.rankReward(seat),
            )
            .forEach { (name, value) ->
                require(abs(PurchaseJson.number(node.required(name), name) - value) <= 1e-12) {
                    "Terminal $name differs from authoritative public scores."
                }
            }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
