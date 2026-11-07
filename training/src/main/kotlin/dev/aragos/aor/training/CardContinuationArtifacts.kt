package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals

/** Strict completed diagnostic validation; no old training reader may reinterpret these records. */
internal object CardContinuationArtifacts {
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun validate(node: JsonNode, input: CardContinuationDiagnosticInput, index: Int) {
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
            ),
        )
        require(
            integer(node, "schemaVersion") == 3 &&
                node.required("kind").asText() == CardContinuationDiagnosticInput.KIND &&
                node.required("signature").asText() == input.signature() &&
                integer(node, "index") == index &&
                integer(node, "seed") == request.seed &&
                integer(node, "seat") == request.seat &&
                node.required("split").asText() == request.split &&
                node.required("request") == PurchaseJson.mapper.valueToTree<JsonNode>(request)
        ) {
            "A diagnostic origin must retain its exact source, request and model identities."
        }
        require(
            integer(node, "backboneDecisions") in 1..12000 &&
                hashPattern.matches(node.required("backboneCanonicalSha256").asText())
        ) {
            "The F backbone requires bounded replay provenance."
        }
        val root = node.required("root")
        val outcomes = node.required("outcomes")
        require(outcomes.isObject && outcomes.size() <= 16) {
            "Diagnostic outcomes must be a bounded object of complete tails."
        }
        if (node.required("status").asText() == "skipped") {
            require(
                node.required("skipReason").asText() == "requested-root-not-reached" &&
                    root.isNull &&
                    outcomes.isEmpty
            ) {
                "A skipped diagnostic request cannot contain partial targets or a replacement root."
            }
            return
        }
        require(
            node.required("status").asText() == "collected" && node.required("skipReason").isNull
        ) {
            "An origin must be complete or explicitly skipped."
        }
        PurchaseJson.fields(
            root,
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
        val number = integer(root, "number")
        val botSeed = integer(root, "botSeed")
        require(
            number in 0..11999 &&
                integer(root, "turn") >= 1 &&
                integer(root, "epoch") == request.epoch &&
                botSeed == CardAdvantageExecutor.policySeed(request.seed, number, request.seat)
        ) {
            "A diagnostic root must preserve its requested epoch and absolute F decision seed."
        }
        val observation = CardTrajectoryValidation.observation(root.required("observation"))
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
            "Only normal-window actor-safe observations in the frozen dictionary are diagnostic input."
        }
        val proposals =
            PurchaseJson.array(root.required("proposalIndices"), "proposalIndices").map {
                PurchaseJson.integer(it, "proposal index")
            }
        val reference = integer(root, "referenceIndex")
        require(
            proposals == CardProposals.indices(observation, botSeed) &&
                reference == observation.baselineIndex
        ) {
            "A diagnostic root must retain complete shared S and its explicit F reference."
        }
        val acquisition =
            CardAdvantageAcquisition.select(proposals, reference, botSeed, sampled = true)
        val alternative =
            root
                .required("alternativeIndex")
                .takeUnless { it.isNull }
                ?.let { PurchaseJson.integer(it, "alternativeIndex") }
        require(
            root.required("acquisition") ==
                PurchaseJson.mapper.valueToTree<JsonNode>(acquisition) &&
                alternative == acquisition.sampledActionIndex
        ) {
            "The acquired alternative must be chosen by the exact actor-safe sampler before outcomes."
        }
        val arms = PurchaseJson.array(root.required("arms"), "arms")
        require(arms.map { it.required("id").asText() } == CardContinuationArm.IDS) {
            "A diagnostic requires all four declared arms in canonical order."
        }
        val referenced = mutableSetOf<String>()
        arms.forEach { arm ->
            PurchaseJson.fields(arm, setOf("id", "actionIndex", "continuation", "outcomeIds"))
            val id = PurchaseJson.text(arm.required("id"), "arm ID")
            val ids = PurchaseJson.strings(arm.required("outcomeIds"), "outcomeIds")
            require(
                integer(arm, "actionIndex") ==
                    CardContinuationArm.action(id, reference, alternative) &&
                    arm.required("continuation").asText() == CardContinuationArm.tail(id) &&
                    ids == CardContinuationArm.outcomeIds(id, alternative)
            ) {
                "An arm must retain its initial action, own continuation and explicit trivial alias."
            }
            ids.forEachIndexed { replicate, outcomeId ->
                val outcome =
                    requireNotNull(outcomes[outcomeId]) {
                        "Every diagnostic arm needs its complete declared outcome."
                    }
                require(
                    outcome.required("chanceSeed").isIntegralNumber &&
                        outcome.required("chanceSeed").longValue() ==
                            CardAdvantageExecutor.chanceSeed(request.seed, replicate)
                ) {
                    "All action/continuation arms must share the same chance start for each replicate."
                }
                referenced.add(outcomeId)
            }
        }
        require(
            referenced == outcomes.properties().map { it.key }.toSet() &&
                outcomes.size() == if (alternative == null) 8 else 16
        ) {
            "No diagnostic tails may be missing, invented or silently aliased."
        }
        outcomes.properties().forEach { (_, outcome) ->
            PurchaseJson.fields(
                outcome,
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
                integer(outcome, "gameDecisions") > number &&
                    hashPattern.matches(outcome.required("canonicalSha256").asText())
            ) {
                "A diagnostic tail must include its forced action and verified canonical replay."
            }
            CardAdvantageArtifacts.terminal(
                outcome,
                request.seat,
                input.environment.configuration.id,
            )
        }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
