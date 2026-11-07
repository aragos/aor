package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import kotlin.math.abs

/** Validate complete origins and recorded seed provenance before reusing any terminal labels. */
internal class TerritoryTrajectoryValidation(
    private val input: TerritoryCollectionInput,
    private val signature: String,
) {
    fun validate(node: JsonNode, index: Int) {
        require(index in input.seeds.indices) {
            "Territory origin index must belong to its fixed bank."
        }
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "signature",
                "index",
                "configuration",
                "gameSeed",
                "controlledSeat",
                "opponentIndices",
                "bundleSha256",
                "frozenCardPurchaseBundleSha256",
                "tokenModelSha256",
                "expansionModelSha256",
                "tokenDecisions",
                "expansionDecisions",
                "terminal",
            ),
        )
        require(
            integer(node, "schemaVersion") == 1 &&
                node.required("signature").asText() == signature &&
                integer(node, "index") == index &&
                node.required("configuration").asText() == input.configuration.id &&
                integer(node, "gameSeed") == input.seeds[index] &&
                integer(node, "controlledSeat") == input.seat(index) &&
                PurchaseJson.array(node.required("opponentIndices"), "opponentIndices").map {
                    PurchaseJson.integer(it, "opponent index")
                } == input.opponentIndices(index)
        ) {
            "Territory origin, seat and rival schedule changed."
        }
        require(
            node.required("bundleSha256").asText() == input.bundle.sha256 &&
                node.required("frozenCardPurchaseBundleSha256").asText() == input.frozen.sha256 &&
                node.required("tokenModelSha256").asText() == input.bundle.token?.sha256 &&
                node.required("expansionModelSha256").asText() == input.bundle.expansion?.sha256
        ) {
            "Territory records require exact behavior and continuation models."
        }
        val steps =
            PurchaseTrajectoryValidation.terminal(
                node.required("terminal"),
                input.seat(index),
                input.configuration.playerCount,
            )
        val tokens = PurchaseJson.array(node.required("tokenDecisions"), "tokenDecisions")
        val expansions =
            PurchaseJson.array(node.required("expansionDecisions"), "expansionDecisions")
        fun numbers(rows: List<JsonNode>): List<Int> =
            rows
                .map { integer(it, "number") }
                .also {
                    require(
                        it == it.distinct().sorted() && it.all { number -> number in 0 until steps }
                    ) {
                        "Territory rows require distinct increasing absolute numbers."
                    }
                }
        require(numbers(tokens).toSet().intersect(numbers(expansions).toSet()).isEmpty()) {
            "Territory phase arrays must be disjoint."
        }
        tokens.forEach { decision(it, index, TerritoryPhase.TOKEN) }
        expansions.forEach { decision(it, index, TerritoryPhase.EXPANSION) }
        require(
            (tokens + expansions)
                .sortedBy { integer(it, "number") }
                .zipWithNext()
                .all { (a, b) -> integer(a, "turn") <= integer(b, "turn") }
        ) {
            "Merged territory rows must preserve public turn order."
        }
    }

    private fun decision(node: JsonNode, index: Int, phase: TerritoryPhase) {
        PurchaseJson.fields(
            node,
            setOf(
                "number",
                "batchStartNumber",
                "botSeed",
                "actorSeed",
                "decision",
                "turn",
                "observation",
                "selectedIndex",
                "behaviorLogProbability",
                "behaviorValue",
            ),
        )
        val number = integer(node, "number")
        val batch = integer(node, "batchStartNumber")
        require(
            node.required("decision").asText() == phase.decision &&
                integer(node, "turn") > 0 &&
                batch >= 0 &&
                number - batch in 0 until input.configuration.playerCount &&
                (phase == TerritoryPhase.TOKEN || batch == number)
        ) {
            "Territory phase, public turn and sealed-batch provenance must agree."
        }
        val botSeed = input.seeds[index] xor (batch * 65537 + input.seat(index) * 8191)
        require(
            integer(node, "botSeed") == botSeed &&
                integer(node, "actorSeed") == TerritoryPolicy.actorSeed(phase, botSeed)
        ) {
            "Actor seeds derive from the actual pre-batch number, never a later sealed actor's global offset."
        }
        val encoded = node.required("observation")
        PurchaseJson.fields(
            encoded,
            setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"),
        )
        val observation =
            TerritoryObservation(
                PurchaseJson.text(encoded.required("encoderVersion"), "encoderVersion"),
                features(encoded.required("stateFeatures")),
                PurchaseJson.array(encoded.required("actions"), "actions").map { action ->
                    PurchaseJson.fields(action, setOf("command", "features"))
                    TerritoryAction(
                        PurchaseJson.text(action.required("command"), "command"),
                        features(action.required("features")),
                    )
                },
                integer(encoded, "baselineIndex"),
            )
        require(observation.encoderVersion == phase.encoderVersion) {
            "A territory row cannot cross typed phase boundaries."
        }
        val actor =
            requireNotNull(
                if (phase == TerritoryPhase.TOKEN) input.bundle.token else input.bundle.expansion
            ) {
                "Collected territory rows require their frozen behavior actor."
            }
        val choice = TerritoryPolicy.choose(observation, actor.model, integer(node, "actorSeed"))
        require(
            integer(node, "selectedIndex") == choice.index &&
                abs(
                    PurchaseJson.number(
                        node.required("behaviorLogProbability"),
                        "behaviorLogProbability",
                    ) - choice.logProbability
                ) <= 1e-9 &&
                abs(
                    PurchaseJson.number(node.required("behaviorValue"), "behaviorValue") -
                        choice.value
                ) <= 1e-9
        ) {
            "Territory action, likelihood and value must reproduce the exact original seeded behavior."
        }
    }

    private fun features(node: JsonNode): Map<String, Double> {
        require(node.isObject) { "Territory sparse features require a named object." }
        return node.properties().associate { (name, value) ->
            name to PurchaseJson.number(value, name)
        }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
