@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Constructed-fixture parity boundary; this does not install a browser policy or model asset. */
@JsExport
fun evaluateExpansionPolicy(
    projectionJson: String,
    modelJson: String,
    baselineCommand: String,
    uniform: Double,
): String {
    require(modelJson.length <= 24_000_000) { "Expansion model exceeds its transport bound." }
    val observation = readExpansionProjection(projectionJson).bindBaseline(baselineCommand)
    val model = readExpansionModel(JSON.parse<dynamic>(modelJson))
    val evaluation = TerritoryPolicy.evaluate(observation, model)
    val choice = TerritoryPolicy.select(observation, evaluation, uniform)
    val result = js("({})")
    result.logits = evaluation.logits.toTypedArray()
    result.probabilities = evaluation.probabilities.toTypedArray()
    result.value = evaluation.value
    result.index = choice.index
    result.command = choice.command
    result.logProbability = choice.logProbability
    return JSON.stringify(result)
}

internal fun readExpansionProjection(json: String): ExpansionProjection {
    require(json.length <= 8_000_000) { "Expansion projection exceeds its transport bound." }
    val raw = JSON.parse<dynamic>(json)
    requireExpansionFields(raw, setOf("encoderVersion", "stateFeatures", "actions"))
    return ExpansionProjection(
        encoderVersion = purchaseString(raw.encoderVersion),
        stateFeatures = purchaseMap(raw.stateFeatures),
        actions =
            purchaseArray(raw.actions).map { action ->
                requireExpansionFields(action, setOf("command", "features"))
                TerritoryAction(purchaseString(action.command), purchaseMap(action.features))
            },
    )
}

internal fun readExpansionModel(raw: dynamic): TerritoryPolicyModel {
    requireExpansionFields(
        raw,
        setOf(
            "schemaVersion",
            "encoderVersion",
            "hiddenSize",
            "baselineProbability",
            "stateFeatureNames",
            "actionFeatureNames",
            "stateWeights",
            "stateBias",
            "actionWeights",
            "actionBias",
            "actorWeights",
            "valueWeights",
            "valueBias",
        ),
    )
    require(
        purchaseInteger(raw.schemaVersion) == 2 &&
            purchaseString(raw.encoderVersion) == TerritoryEncoder.EXPANSION_2.version
    ) {
        "The expansion browser boundary requires schema 2 and its complete version-2 encoder."
    }
    return TerritoryPolicyModel(
        schemaVersion = purchaseInteger(raw.schemaVersion),
        encoderVersion = purchaseString(raw.encoderVersion),
        hiddenSize = purchaseInteger(raw.hiddenSize),
        baselineProbability = purchaseNumber(raw.baselineProbability),
        stateFeatureNames = purchaseArray(raw.stateFeatureNames).map(::purchaseString),
        actionFeatureNames = purchaseArray(raw.actionFeatureNames).map(::purchaseString),
        stateWeights = purchaseArray(raw.stateWeights).map(::purchaseNumber),
        stateBias = purchaseArray(raw.stateBias).map(::purchaseNumber),
        actionWeights = purchaseArray(raw.actionWeights).map(::purchaseNumber),
        actionBias = purchaseArray(raw.actionBias).map(::purchaseNumber),
        actorWeights = purchaseArray(raw.actorWeights).map(::purchaseNumber),
        valueWeights = purchaseArray(raw.valueWeights).map(::purchaseNumber),
        valueBias = purchaseNumber(raw.valueBias),
    )
}

/** Reject undeclared transport fields; authority, baseline indices and outcomes are not inputs. */
internal fun requireExpansionFields(raw: dynamic, names: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Expansion JSON requires an object."
    }
    val actual = js("Object.keys(raw)") as Array<String>
    require(actual.toSet() == names) { "Expansion JSON fields must match its exact contract." }
}
