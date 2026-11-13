@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Explicit research evaluator only: no worker routing, asset publication or preview activation. */
@JsExport
fun evaluateCpePolicy(
    head: String,
    observationJson: String,
    modelJson: String,
    uniform: Double,
): String {
    require(head in setOf("CARD", "PURCHASE", "EXPANSION")) {
        "A C/P/E evaluator requires an explicit supported head."
    }
    require(observationJson.length <= 32_000_000 && modelJson.length <= 24_000_000) {
        "C/P/E inference input exceeds its transport bound."
    }
    val input = JSON.parse<dynamic>(observationJson)
    cpeFields(input, setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"))
    purchaseArray(input.actions).forEach { cpeFields(it, setOf("command", "features")) }
    val raw = JSON.parse<dynamic>(modelJson)
    cpeFields(
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
    return when (head) {
        "CARD" -> evaluateCardPolicy(observationJson, modelJson, uniform)
        "PURCHASE" -> evaluatePurchasePolicy(observationJson, modelJson, uniform)
        else -> evaluateExpansion(input, raw, uniform)
    }
}

/**
 * Actual common per-head salt and integer mixer, using the original policy's pre-batch bot seed.
 */
@JsExport
fun evaluateCpePolicySeeded(
    head: String,
    observationJson: String,
    modelJson: String,
    botSeed: Int,
): String {
    val uniform =
        when (head) {
            "CARD" -> CardPolicy.uniform(CardPolicy.actorSeed(botSeed))
            "PURCHASE" -> PurchasePolicy.uniform(PurchasePolicy.actorSeed(botSeed))
            "EXPANSION" ->
                TerritoryPolicy.uniform(
                    TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, botSeed)
                )
            else -> error("A C/P/E evaluator requires an explicit supported head.")
        }
    return evaluateCpePolicy(head, observationJson, modelJson, uniform)
}

private fun evaluateExpansion(input: dynamic, raw: dynamic, uniform: Double): String {
    require(
        purchaseInteger(raw.schemaVersion) == 2 &&
            purchaseString(raw.encoderVersion) == TerritoryEncoder.EXPANSION_2.version
    ) {
        "C/P/E expansion requires explicit schema2 / EXPANSION_2."
    }
    val observation =
        TerritoryObservation(
            purchaseString(input.encoderVersion),
            purchaseMap(input.stateFeatures),
            purchaseArray(input.actions).map {
                TerritoryAction(purchaseString(it.command), purchaseMap(it.features))
            },
            purchaseInteger(input.baselineIndex),
        )
    val model =
        TerritoryPolicyModel(
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

private fun cpeFields(raw: dynamic, names: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "C/P/E public JSON requires an object."
    }
    val fields = js("Object.keys(raw)") as Array<String>
    require(fields.toSet() == names) { "C/P/E JSON fields must match the exact public contract." }
}
