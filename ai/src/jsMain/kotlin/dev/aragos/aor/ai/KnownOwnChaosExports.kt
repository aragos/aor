@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/**
 * Explicit diagnostic boundary for the opt-in behavior, including its conditional probabilities.
 */
@JsExport
fun evaluateKnownOwnChaosCardPolicy(
    observationJson: String,
    modelJson: String,
    uniform: Double,
): String {
    require(modelJson.length <= 24_000_000) { "Card model exceeds its transport bound." }
    val observation = readCardObservation(observationJson)
    val evaluation = CardPolicy.evaluate(observation, readCardModel(JSON.parse<dynamic>(modelJson)))
    return supportedChoiceJson(
        KnownOwnChaosSupport.card(observation, evaluation, uniform),
        evaluation.probabilities,
    )
}

/** Purchase counterpart; original full-support exports and model tensors remain unchanged. */
@JsExport
fun evaluateKnownOwnChaosPurchasePolicy(
    observationJson: String,
    modelJson: String,
    uniform: Double,
): String {
    require(modelJson.length <= 24_000_000) { "Purchase model exceeds its transport bound." }
    val observation = readPurchaseObservation(observationJson)
    val evaluation =
        PurchasePolicy.evaluate(observation, readPurchaseModel(JSON.parse<dynamic>(modelJson)))
    return supportedChoiceJson(
        KnownOwnChaosSupport.purchase(observation, evaluation, uniform),
        evaluation.probabilities,
    )
}

private fun supportedChoiceJson(choice: KnownOwnChaosChoice, original: List<Double>): String {
    val result = js("({})")
    result.supportProfile = KnownOwnChaosSupport.VERSION
    result.originalProbabilities = original.toTypedArray()
    result.probabilities = choice.probabilities.toTypedArray()
    result.excludedIndices = choice.excludedIndices.toTypedArray()
    result.value = choice.value
    result.index = choice.index
    result.command = choice.command
    result.logProbability = choice.logProbability
    return JSON.stringify(result)
}
