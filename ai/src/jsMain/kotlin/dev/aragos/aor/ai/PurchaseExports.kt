@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Experimental JSON boundary; this does not install a policy in the ordinary bot worker. */
@JsExport
fun evaluatePurchasePolicy(observationJson: String, modelJson: String, uniform: Double): String {
    require(observationJson.length <= 8_000_000 && modelJson.length <= 24_000_000) {
        "Purchase inference input exceeds its transport bound."
    }
    val observation = readPurchaseObservation(observationJson)
    val model = readPurchaseModel(JSON.parse<dynamic>(modelJson))
    val evaluation = PurchasePolicy.evaluate(observation, model)
    val choice = PurchasePolicy.select(observation, evaluation, uniform)
    val result = js("({})")
    result.logits = evaluation.logits.toTypedArray()
    result.probabilities = evaluation.probabilities.toTypedArray()
    result.value = evaluation.value
    result.index = choice.index
    result.command = choice.command
    result.logProbability = choice.logProbability
    return JSON.stringify(result)
}

internal fun readPurchaseObservation(json: String): PurchaseObservation {
    require(json.length <= 8_000_000) { "Purchase observation exceeds its transport bound." }
    val input = JSON.parse<dynamic>(json)
    return PurchaseObservation(
        encoderVersion = purchaseString(input.encoderVersion),
        stateFeatures = purchaseMap(input.stateFeatures),
        actions =
            purchaseArray(input.actions).map { action ->
                PurchaseAction(purchaseString(action.command), purchaseMap(action.features))
            },
        baselineIndex = purchaseInteger(input.baselineIndex),
    )
}

internal fun readPurchaseModel(raw: dynamic): PurchasePolicyModel =
    PurchasePolicyModel(
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

internal fun purchaseString(value: dynamic): String {
    require(jsTypeOf(value) == "string") { "Purchase JSON requires a string." }
    return value as String
}

internal fun purchaseNumber(value: dynamic): Double {
    require(jsTypeOf(value) == "number") { "Purchase JSON requires a number." }
    val number = value as Double
    require(number.isFinite()) { "Purchase JSON numbers must be finite." }
    return number
}

internal fun purchaseInteger(value: dynamic): Int {
    val number = purchaseNumber(value)
    require(number == number.toInt().toDouble()) {
        "Purchase JSON index/version requires an integer."
    }
    return number.toInt()
}

internal fun purchaseArray(value: dynamic): Array<dynamic> {
    require(js("Array.isArray(value)") as Boolean) { "Purchase JSON requires an array." }
    return value as Array<dynamic>
}

internal fun purchaseMap(value: dynamic): Map<String, Double> {
    require(
        value != null && jsTypeOf(value) == "object" && !(js("Array.isArray(value)") as Boolean)
    ) {
        "Purchase JSON features require an object."
    }
    val keys = js("Object.keys(value)") as Array<String>
    return keys.associateWith { purchaseNumber(value[it]) }
}
