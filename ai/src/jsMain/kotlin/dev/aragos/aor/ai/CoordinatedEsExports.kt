@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Constructed/research inference only: no worker routing, model assets or activation. */
@JsExport
fun evaluateCoordinatedPolicySeeded(
    head: String,
    observationJson: String,
    modelJson: String,
    originalJson: String,
    scale: Double,
    botSeed: Int,
): String {
    require(head in setOf("CARD", "PURCHASE", "EXPANSION", "TOKEN")) {
        "Coordinated inference requires an explicit named head."
    }
    require(
        observationJson.length <= 32_000_000 &&
            modelJson.length <= 24_000_000 &&
            originalJson.length <= 24_000_000
    ) {
        "Coordinated inference exceeds its bounded public transport."
    }
    val raw = JSON.parse<dynamic>(modelJson)
    val original = JSON.parse<dynamic>(originalJson)
    listOf(raw, original).forEach { model -> coordinatedFields(model, modelFields) }
    if (head == "CARD") {
        require(scale == 1.0) { "C/P preserve their original sampler without a selector scale." }
        val currentModel = readCardModel(raw)
        val originalModel = readCardModel(original)
        require(
            currentModel.stateFeatureNames == originalModel.stateFeatureNames &&
                currentModel.actionFeatureNames == originalModel.actionFeatureNames &&
                currentModel.valueWeights == originalModel.valueWeights &&
                currentModel.valueBias == originalModel.valueBias
        ) {
            "Coordinated card inference preserves original dictionaries and value arrays."
        }
        return evaluateCpePolicySeeded(head, observationJson, modelJson, botSeed)
    }
    if (head == "PURCHASE") {
        require(scale == 1.0) { "C/P preserve their original sampler without a selector scale." }
        val currentModel = readPurchaseModel(raw)
        val originalModel = readPurchaseModel(original)
        require(
            currentModel.stateFeatureNames == originalModel.stateFeatureNames &&
                currentModel.actionFeatureNames == originalModel.actionFeatureNames &&
                currentModel.valueWeights == originalModel.valueWeights &&
                currentModel.valueBias == originalModel.valueBias
        ) {
            "Coordinated purchase inference preserves original dictionaries and value arrays."
        }
        return evaluateCpePolicySeeded(head, observationJson, modelJson, botSeed)
    }
    val input = JSON.parse<dynamic>(observationJson)
    coordinatedFields(input, setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"))
    val observation =
        TerritoryObservation(
            purchaseString(input.encoderVersion),
            purchaseMap(input.stateFeatures),
            purchaseArray(input.actions).map { action ->
                coordinatedFields(action, setOf("command", "features"))
                TerritoryAction(purchaseString(action.command), purchaseMap(action.features))
            },
            purchaseInteger(input.baselineIndex),
        )
    val choice =
        IncumbentResidualPolicy.choose(
            observation,
            coordinatedTerritory(raw, head),
            coordinatedTerritory(original, head),
            IncumbentResidualSelector(scale),
        )
    val result = js("({})")
    result.residualDifferences = choice.residualDifferences.toTypedArray()
    result.scores = choice.scores.toTypedArray()
    result.index = choice.index
    result.command = choice.command
    result.strongestGap =
        IncumbentResidualPolicy.strongestGap(choice.residualDifferences, observation.baselineIndex)
    return JSON.stringify(result)
}

private val modelFields =
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
    )

private fun coordinatedTerritory(raw: dynamic, head: String): TerritoryPolicyModel {
    val encoder =
        if (head == "EXPANSION") TerritoryEncoder.EXPANSION_2 else TerritoryEncoder.TOKEN_2
    val schema = if (head == "EXPANSION") 2 else 3
    require(
        purchaseInteger(raw.schemaVersion) == schema &&
            purchaseString(raw.encoderVersion) == encoder.version
    ) {
        "An incumbent-relative head must match its declared E2 or TOKEN2 container."
    }
    return TerritoryPolicyModel(
        schemaVersion = schema,
        encoderVersion = encoder.version,
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

private fun coordinatedFields(raw: dynamic, fields: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Coordinated inference requires typed JSON objects."
    }
    require((js("Object.keys(raw)") as Array<String>).toSet() == fields) {
        "Coordinated inference fields must match the exact declared contract."
    }
}
