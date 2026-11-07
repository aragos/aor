@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Experimental boundary only; no ordinary worker dispatcher or asset is changed here. */
@JsExport
fun evaluateCardAdvantage(observationJson: String, modelJson: String, botSeed: Int): String {
    require(modelJson.length <= 72_000_000) { "Card advantage model exceeds its transport bound." }
    val observation = readAdvantageObservation(observationJson)
    val model = readCardAdvantageModel(JSON.parse<dynamic>(modelJson))
    val supported = CardAdvantagePolicy.supports(observation)
    val evaluation =
        if (supported) CardAdvantagePolicy.evaluate(observation, model, botSeed) else null
    val choice =
        if (evaluation != null) CardAdvantagePolicy.select(observation, evaluation)
        else CardAdvantagePolicy.choose(observation, model, botSeed)
    val result = js("({})")
    result.supported = supported
    result.proposalIndices = evaluation?.proposalIndices?.toTypedArray()
    result.memberValues = evaluation?.memberValues?.map { it.toTypedArray() }?.toTypedArray()
    result.memberAdvantages =
        evaluation?.memberAdvantages?.map { it.toTypedArray() }?.toTypedArray()
    result.meanAdvantages = evaluation?.meanAdvantages?.toTypedArray()
    result.index = choice.index
    result.command = choice.command
    result.winnerIndex = choice.winnerIndex
    result.overridden = choice.overridden
    return JSON.stringify(result)
}

/** Exposes the same proposal identities to numerical and collector parity probes. */
@JsExport
fun cardProposalIndices(observationJson: String, botSeed: Int): String =
    JSON.stringify(
        CardProposals.indices(readAdvantageObservation(observationJson), botSeed).toTypedArray()
    )

@JsExport
fun chooseCardFrequency(observationJson: String, modelJson: String, botSeed: Int): String {
    require(modelJson.length <= 4096) { "Card frequency model exceeds its transport bound." }
    val observation = readAdvantageObservation(observationJson)
    val raw = JSON.parse<dynamic>(modelJson)
    requireAdvantageFields(
        raw,
        setOf(
            "schemaVersion",
            "encoderVersion",
            "selectorVersion",
            "proposalVersion",
            "passProbability",
            "otherProbability",
        ),
    )
    val model =
        CardFrequencyModel(
            schemaVersion = purchaseInteger(raw.schemaVersion),
            encoderVersion = purchaseString(raw.encoderVersion),
            selectorVersion = purchaseString(raw.selectorVersion),
            proposalVersion = purchaseString(raw.proposalVersion),
            passProbability = purchaseNumber(raw.passProbability),
            otherProbability = purchaseNumber(raw.otherProbability),
        )
    val choice = CardFrequencyControl.choose(observation, model, botSeed)
    val result = js("({})")
    result.index = choice.index
    result.command = choice.command
    result.requestedFamily = choice.requestedFamily.name
    result.overridden = choice.overridden
    return JSON.stringify(result)
}

private fun readAdvantageObservation(json: String): CardObservation {
    require(json.length <= 32_000_000) { "Card advantage observation exceeds its transport bound." }
    val raw = JSON.parse<dynamic>(json)
    return CardObservation(
        encoderVersion = purchaseString(raw.encoderVersion),
        stateFeatures = purchaseMap(raw.stateFeatures),
        actions =
            purchaseArray(raw.actions).map {
                CardAction(purchaseString(it.command), purchaseMap(it.features))
            },
        baselineIndex = purchaseInteger(raw.baselineIndex),
    )
}

internal fun readCardAdvantageModel(raw: dynamic): CardAdvantageModel {
    requireAdvantageFields(
        raw,
        setOf(
            "schemaVersion",
            "encoderVersion",
            "selectorVersion",
            "proposalVersion",
            "hiddenSize",
            "margin",
            "stateFeatureNames",
            "actionFeatureNames",
            "members",
        ),
    )
    return CardAdvantageModel(
        schemaVersion = purchaseInteger(raw.schemaVersion),
        encoderVersion = purchaseString(raw.encoderVersion),
        selectorVersion = purchaseString(raw.selectorVersion),
        proposalVersion = purchaseString(raw.proposalVersion),
        hiddenSize = purchaseInteger(raw.hiddenSize),
        margin = purchaseNumber(raw.margin),
        stateFeatureNames = purchaseArray(raw.stateFeatureNames).map(::purchaseString),
        actionFeatureNames = purchaseArray(raw.actionFeatureNames).map(::purchaseString),
        members =
            purchaseArray(raw.members).map { member ->
                requireAdvantageFields(
                    member,
                    setOf(
                        "stateWeights",
                        "stateBias",
                        "actionWeights",
                        "actionBias",
                        "outputWeights",
                        "outputBias",
                    ),
                )
                CardAdvantageNetwork(
                    stateWeights = purchaseArray(member.stateWeights).map(::purchaseNumber),
                    stateBias = purchaseArray(member.stateBias).map(::purchaseNumber),
                    actionWeights = purchaseArray(member.actionWeights).map(::purchaseNumber),
                    actionBias = purchaseArray(member.actionBias).map(::purchaseNumber),
                    outputWeights = purchaseArray(member.outputWeights).map(::purchaseNumber),
                    outputBias = purchaseNumber(member.outputBias),
                )
            },
    )
}

private fun requireAdvantageFields(value: dynamic, expected: Set<String>) {
    require(
        value != null && jsTypeOf(value) == "object" && !(js("Array.isArray(value)") as Boolean)
    ) {
        "Card selector JSON requires an object."
    }
    val fields = js("Object.keys(value)") as Array<String>
    require(fields.toSet() == expected) { "Card selector JSON has unexpected or missing fields." }
}
