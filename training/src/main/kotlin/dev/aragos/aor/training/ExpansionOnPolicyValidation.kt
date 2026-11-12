package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import kotlin.math.abs

/** New typed trajectory boundary; no existing token/expansion-v1 reader is relaxed. */
internal class ExpansionOnPolicyValidation(private val input: ExpansionOnPolicyInput) {
    fun validate(node: JsonNode, index: Int) {
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "kind",
                "signature",
                "index",
                "configuration",
                "gameSeed",
                "controlledSeat",
                "opponentIndices",
                "actorModelSha256",
                "frozenCardPurchaseBundleSha256",
                "referenceExpansionModelSha256",
                "arenaSourceSha256",
                "expansionDecisions",
                "terminal",
            ),
        )
        require(
            index in input.seeds.indices &&
                integer(node, "schemaVersion") == 1 &&
                node["kind"].asText() == "expansion-on-policy-trajectory-1" &&
                node["signature"].asText() == input.signature &&
                integer(node, "index") == index &&
                node["configuration"].asText() == input.configuration.id &&
                integer(node, "gameSeed") == input.seeds[index] &&
                integer(node, "controlledSeat") == input.seat(index) &&
                PurchaseJson.array(node["opponentIndices"], "opponentIndices").map {
                    PurchaseJson.integer(it, "opponent index")
                } == List(4) { 0 }
        ) {
            "Expansion trajectory must retain its exact origin, setup and role schedule."
        }
        require(
            node["actorModelSha256"].asText() == input.actor.sha256 &&
                node["frozenCardPurchaseBundleSha256"].asText() ==
                    input.frozenCardPurchaseBundleSha256 &&
                node["referenceExpansionModelSha256"].asText() ==
                    input.referenceExpansionModelSha256 &&
                node["arenaSourceSha256"].asText() == input.arenaSourceSha256
        ) {
            "Expansion behavior and every frozen continuation/reference identity must remain exact."
        }
        val steps = PurchaseTrajectoryValidation.terminal(node["terminal"], input.seat(index), 4)
        val rows = PurchaseJson.array(node["expansionDecisions"], "expansionDecisions")
        val numbers = rows.map { integer(it, "number") }
        require(numbers == numbers.distinct().sorted() && numbers.all { it in 0 until steps }) {
            "Expansion rows require distinct chronological absolute PLAYER numbers."
        }
        require(
            rows.zipWithNext().all { (first, next) ->
                integer(first, "turn") <= integer(next, "turn")
            }
        ) {
            "Expansion rows must preserve public turn order."
        }
        rows.forEach { decision(it, index) }
    }

    internal fun decision(node: JsonNode, index: Int) {
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
            number >= 0 &&
                batch == number &&
                integer(node, "turn") > 0 &&
                node["decision"].asText() == "expand"
        ) {
            "Expansion is a single-actor ordinary window at its actual absolute PLAYER number."
        }
        val botSeed = simulationPolicySeed(input.seeds[index], batch, input.seat(index))
        val actorSeed = TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, botSeed)
        require(integer(node, "botSeed") == botSeed && integer(node, "actorSeed") == actorSeed) {
            "Expansion sampling must retain the original game/batch/seat seed and phase salt."
        }
        val encoded = node.required("observation")
        PurchaseJson.fields(
            encoded,
            setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"),
        )
        val observation =
            TerritoryObservation(
                PurchaseJson.text(encoded["encoderVersion"], "encoderVersion"),
                features(encoded["stateFeatures"]),
                PurchaseJson.array(encoded["actions"], "actions").map { action ->
                    PurchaseJson.fields(action, setOf("command", "features"))
                    TerritoryAction(
                        PurchaseJson.text(action["command"], "command"),
                        features(action["features"]),
                    )
                },
                integer(encoded, "baselineIndex"),
            )
        require(observation.encoderVersion == TerritoryEncoder.EXPANSION_2.version) {
            "Only explicit full-offer expansion-v2 records belong to this collector."
        }
        val choice = TerritoryPolicy.choose(observation, input.actor.model, actorSeed)
        require(
            integer(node, "selectedIndex") == choice.index &&
                abs(
                    PurchaseJson.number(node["behaviorLogProbability"], "behaviorLogProbability") -
                        choice.logProbability
                ) <= 1e-9 &&
                abs(PurchaseJson.number(node["behaviorValue"], "behaviorValue") - choice.value) <=
                    1e-9
        ) {
            "Selected command, log probability and value must reproduce the actual behavior actor."
        }
    }

    private fun features(node: JsonNode): Map<String, Double> {
        require(node.isObject) { "Expansion sparse features require a numeric named object." }
        return node.properties().associate { (name, value) ->
            name to PurchaseJson.number(value, name)
        }
    }

    private fun integer(node: JsonNode, name: String): Int =
        PurchaseJson.integer(node.required(name), name)
}
