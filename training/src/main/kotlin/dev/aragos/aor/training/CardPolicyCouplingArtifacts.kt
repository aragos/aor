package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals

/** Validate complete cached diagnostic records without trusting their schedule or alias labels. */
internal object CardPolicyCouplingArtifacts {
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun validate(node: JsonNode, input: CardPolicyCouplingInput, index: Int) {
        val request = input.origins[index]
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "kind",
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
                "root",
                "outcomes",
                "policyTraces",
            ),
        )
        require(
            integer(node, "schemaVersion") == 1 &&
                text(node, "kind") == CardPolicyCouplingInput.KIND &&
                text(node, "signature") == input.signature() &&
                integer(node, "index") == index &&
                integer(node, "seed") == request.seed &&
                integer(node, "seat") == request.seat &&
                text(node, "split") == request.split &&
                node.required("request") == PurchaseJson.mapper.valueToTree<JsonNode>(request)
        ) {
            "A coupling origin must retain its exact source, request and frozen policy identities."
        }
        require(
            integer(node, "backboneDecisions") in 1..12000 &&
                hashPattern.matches(text(node, "backboneCanonicalSha256"))
        ) {
            "The coupling backbone requires bounded canonical replay provenance."
        }
        val outcomes = node.required("outcomes")
        val traces = node.required("policyTraces")
        require(
            outcomes.isObject &&
                outcomes.size() <= 48 &&
                traces.isObject &&
                traces.properties().map { it.key }.toSet() ==
                    outcomes.properties().map { it.key }.toSet()
        ) {
            "Each bounded complete coupling tail needs exactly one policy trace."
        }
        if (text(node, "status") == "skipped") {
            require(
                text(node, "skipReason") == "requested-root-not-reached" &&
                    node.required("root").isNull &&
                    outcomes.isEmpty &&
                    traces.isEmpty
            ) {
                "A skipped request cannot contain partial targets, traces or replacement roots."
            }
            return
        }
        require(text(node, "status") == "collected" && node.required("skipReason").isNull) {
            "A coupling origin is complete or explicitly skipped."
        }
        val root = readRoot(node.required("root"), input, request)
        val referenced = root.arms.flatMap { it.outcomeIds }.toSet()
        require(
            referenced == outcomes.properties().map { it.key }.toSet() &&
                outcomes.size() == if (root.alternativeIndex == null) 24 else 48
        ) {
            "Coupling tails must preserve all six arms, eight streams and explicit trivial aliases."
        }
        val validated = mutableSetOf<String>()
        root.arms.forEach { arm ->
            arm.outcomeIds.forEachIndexed { replicate, id ->
                if (validated.add(id)) {
                    val outcome = requireNotNull(outcomes[id]) { "A declared tail must exist." }
                    validateOutcome(outcome, root, request, replicate, input)
                    validateTrace(
                        requireNotNull(traces[id]) { "A declared policy trace must exist." },
                        root,
                        request,
                        replicate,
                        CardPolicyCouplingArm.schedule(arm.id),
                        integer(outcome, "gameDecisions"),
                    )
                }
            }
        }
    }

    private fun readRoot(
        node: JsonNode,
        input: CardPolicyCouplingInput,
        request: CardAdvantageRequest,
    ): CardPolicyCouplingRoot {
        PurchaseJson.fields(
            node,
            setOf(
                "number",
                "botSeed",
                "turn",
                "epoch",
                "observation",
                "proposalIndices",
                "referenceIndex",
                "acquisition",
                "alternativeIndex",
                "arms",
            ),
        )
        val number = integer(node, "number")
        val botSeed = integer(node, "botSeed")
        require(
            integer(node, "epoch") == request.epoch &&
                botSeed == CardAdvantageExecutor.policySeed(request.seed, number, request.seat)
        ) {
            "The coupling root must retain its requested epoch and legacy F decision seed."
        }
        val observation = CardTrajectoryValidation.observation(node.required("observation"))
        require(
            CardAdvantagePolicy.supports(observation) &&
                observation.stateFeatures.keys.all {
                    it in input.environment.dictionary.stateFeatureNames
                } &&
                observation.actions.all { action ->
                    action.features.keys.all {
                        it in input.environment.dictionary.actionFeatureNames
                    }
                }
        ) {
            "The root must use the frozen actor-safe normal-window dictionary."
        }
        val proposals =
            PurchaseJson.array(node.required("proposalIndices"), "proposalIndices").map {
                PurchaseJson.integer(it, "proposal index")
            }
        require(proposals == CardProposals.indices(observation, botSeed)) {
            "The coupling root must preserve full legacy S."
        }
        val reference = integer(node, "referenceIndex")
        val acquisition = CardAdvantageAcquisition.select(proposals, reference, botSeed, true)
        require(
            node.required("acquisition") == PurchaseJson.mapper.valueToTree<JsonNode>(acquisition)
        ) {
            "The alternative must retain its exact pre-outcome acquisition."
        }
        val alternative =
            node
                .required("alternativeIndex")
                .takeUnless { it.isNull }
                ?.let { PurchaseJson.integer(it, "alternativeIndex") }
        val arms =
            PurchaseJson.array(node.required("arms"), "arms").map { arm ->
                PurchaseJson.fields(arm, setOf("id", "schedule", "actionIndex", "outcomeIds"))
                CardPolicyCouplingArm(
                    text(arm, "id"),
                    text(arm, "schedule"),
                    integer(arm, "actionIndex"),
                    PurchaseJson.strings(arm.required("outcomeIds"), "outcomeIds"),
                )
            }
        return CardPolicyCouplingRoot(
            number,
            botSeed,
            integer(node, "turn"),
            integer(node, "epoch"),
            observation,
            proposals,
            reference,
            acquisition,
            alternative,
            arms,
        )
    }

    private fun validateOutcome(
        node: JsonNode,
        root: CardPolicyCouplingRoot,
        request: CardAdvantageRequest,
        replicate: Int,
        input: CardPolicyCouplingInput,
    ) {
        PurchaseJson.fields(
            node,
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
        require(
            integer(node, "gameDecisions") > root.number &&
                integer(node, "gameDecisions") <= 12000 &&
                hashPattern.matches(text(node, "canonicalSha256"))
        ) {
            "A coupling tail must include the forced action and a bounded verified canonical replay."
        }
        require(
            node.required("chanceSeed").isIntegralNumber &&
                node.required("chanceSeed").longValue() ==
                    CardPolicySeeds.chanceSeed(request.seed, replicate)
        ) {
            "Every schedule and action must preserve the paired authority stream start."
        }
        CardAdvantageArtifacts.terminal(node, request.seat, input.environment.configuration.id)
    }

    private fun validateTrace(
        node: JsonNode,
        root: CardPolicyCouplingRoot,
        request: CardAdvantageRequest,
        replicate: Int,
        schedule: CardPolicySchedule,
        gameDecisions: Int,
    ) {
        val rows = PurchaseJson.array(node, "policy trace")
        require(rows.size == gameDecisions - root.number) {
            "A policy trace must account for the forced root and every subsequent player command."
        }
        val seeds = CardPolicySeeds(request.seed, replicate, schedule)
        seeds.reserveRoot(root.number, root.turn, request.seat)
        rows.forEachIndexed { index, row ->
            PurchaseJson.fields(
                row,
                setOf(
                    "number",
                    "batchStart",
                    "turn",
                    "stage",
                    "decision",
                    "seat",
                    "occurrence",
                    "seed",
                    "forced",
                ),
            )
            require(
                row.required("forced").isBoolean &&
                    row.required("forced").booleanValue() == (index == 0)
            ) {
                "Only the first trace row may reserve a forced action."
            }
            if (index > 0) {
                seeds.seed(
                    integer(row, "number"),
                    integer(row, "batchStart"),
                    integer(row, "turn"),
                    Stage.valueOf(text(row, "stage")),
                    text(row, "decision"),
                    integer(row, "seat"),
                )
            }
        }
        require(node == PurchaseJson.mapper.valueToTree<JsonNode>(seeds.trace)) {
            "Policy trace contexts, counters and seeds must reproduce their declared schedule exactly."
        }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)

    private fun text(node: JsonNode, name: String): String =
        PurchaseJson.text(node.required(name), name)
}
