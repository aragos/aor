@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Experimental inference only; no worker routing or scientific actor asset is installed. */
@JsExport
fun evaluateTokenPolicy(observationJson: String, modelJson: String, uniform: Double): String {
    require(observationJson.length <= 8_000_000 && modelJson.length <= 24_000_000) {
        "Token inference input exceeds its transport bound."
    }
    val raw = JSON.parse<dynamic>(observationJson)
    tokenFields(raw, setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"))
    val observation =
        TerritoryObservation(
            purchaseString(raw.encoderVersion),
            purchaseMap(raw.stateFeatures),
            purchaseArray(raw.actions).map { action ->
                tokenFields(action, setOf("command", "features"))
                TerritoryAction(purchaseString(action.command), purchaseMap(action.features))
            },
            purchaseInteger(raw.baselineIndex),
        )
    val model = readTokenPolicyModel(JSON.parse<dynamic>(modelJson))
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

/** Same token salt/mixer as native; [botSeed] is the original sealed-batch policy seed. */
@JsExport
fun evaluateTokenPolicySeeded(observationJson: String, modelJson: String, botSeed: Int): String =
    evaluateTokenPolicy(
        observationJson,
        modelJson,
        TerritoryPolicy.uniform(TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, botSeed)),
    )

private fun readTokenPolicyModel(raw: dynamic): TerritoryPolicyModel {
    tokenFields(
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
        purchaseInteger(raw.schemaVersion) == 3 &&
            purchaseString(raw.encoderVersion) == TerritoryEncoder.TOKEN_2.version
    ) {
        "The token evaluator accepts only explicit schema 3 / TOKEN_2 actors."
    }
    return TerritoryPolicyModel(
        schemaVersion = 3,
        encoderVersion = TerritoryEncoder.TOKEN_2.version,
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

private fun tokenFields(raw: dynamic, expected: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Token JSON subjects must be objects."
    }
    val fields = js("Object.keys(raw)") as Array<String>
    require(fields.toSet() == expected) {
        "Token JSON fields must match the exact public contract."
    }
}
