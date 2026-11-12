@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/** Experimental JSON boundary; this does not install a policy in the ordinary bot worker. */
@JsExport
fun evaluateCardPolicy(observationJson: String, modelJson: String, uniform: Double): String {
    require(observationJson.length <= 32_000_000 && modelJson.length <= 24_000_000) {
        "Card inference input exceeds its transport bound."
    }
    val input = JSON.parse<dynamic>(observationJson)
    val observation =
        CardObservation(
            encoderVersion = purchaseString(input.encoderVersion),
            stateFeatures = purchaseMap(input.stateFeatures),
            actions =
                purchaseArray(input.actions).map { action ->
                    CardAction(purchaseString(action.command), purchaseMap(action.features))
                },
            baselineIndex = purchaseInteger(input.baselineIndex),
        )
    val model = readCardModel(JSON.parse<dynamic>(modelJson))
    val evaluation = CardPolicy.evaluate(observation, model)
    val choice = CardPolicy.select(observation, evaluation, uniform)
    val result = js("({})")
    result.logits = evaluation.logits.toTypedArray()
    result.probabilities = evaluation.probabilities.toTypedArray()
    result.value = evaluation.value
    result.index = choice.index
    result.command = choice.command
    result.logProbability = choice.logProbability
    return JSON.stringify(result)
}

internal fun readCardModel(raw: dynamic): CardPolicyModel =
    CardPolicyModel(
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

internal fun readCardProjection(json: String): CardProjection {
    require(json.length <= 32_000_000) { "Card projection exceeds its transport bound." }
    val raw = JSON.parse<dynamic>(json)
    return CardProjection(
        encoderVersion = purchaseString(raw.encoderVersion),
        stateFeatures = purchaseMap(raw.stateFeatures),
        actions =
            purchaseArray(raw.actions).map { action ->
                CardAction(purchaseString(action.command), purchaseMap(action.features))
            },
    )
}
