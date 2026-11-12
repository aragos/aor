package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyModel
import kotlin.math.abs

/**
 * Intrinsic expansion-v2 row likelihood and seed validation shared by explicit research records.
 */
internal object ExpansionBehaviorValidation {
    fun validate(node: JsonNode, worldSeed: Int, seat: Int, model: TerritoryPolicyModel) {
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
        val botSeed = simulationPolicySeed(worldSeed, batch, seat)
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
        val choice = TerritoryPolicy.choose(observation, model, actorSeed)
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
