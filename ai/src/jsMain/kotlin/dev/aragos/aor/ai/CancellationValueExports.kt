@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import dev.aragos.aor.PlayerId
import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Explicit experimental inference, never installed in the browser's opponent dispatcher. */
@JsExport
fun evaluateCancellationValue(inputJson: String, modelJson: String): String {
    require(inputJson.length <= 500_000 && modelJson.length <= 100_000) {
        "Cancellation inference exceeds its transport bounds."
    }
    val raw = JSON.parse<dynamic>(inputJson)
    cancellationFields(
        raw,
        setOf(
            "context",
            "turnOrder",
            "epoch",
            "turn",
            "deckRemaining",
            "endingTriggered",
            "handCount",
            "boardMarkers",
            "ownCardDefinitions",
        ),
    )
    require(jsTypeOf(raw.endingTriggered) == "boolean") { "Ending trigger must be boolean." }
    val input =
        CancellationValueInput(
            readCancellationContext(raw.context),
            purchaseArray(raw.turnOrder).map { PlayerId(purchaseString(it)) },
            purchaseInteger(raw.epoch),
            purchaseInteger(raw.turn),
            purchaseInteger(raw.deckRemaining),
            raw.endingTriggered as Boolean,
            purchaseInteger(raw.handCount),
            purchaseInteger(raw.boardMarkers),
            purchaseArray(raw.ownCardDefinitions).map(::purchaseString),
        )
    val model = JSON.parse<dynamic>(modelJson)
    cancellationFields(
        model,
        setOf(
            "version",
            "featureNames",
            "coefficients",
            "mean",
            "standardDeviation",
            "intercept",
            "sourceSha256",
        ),
    )
    val typed =
        CancellationValueModel(
            purchaseString(model.version),
            purchaseArray(model.featureNames).map(::purchaseString),
            purchaseArray(model.coefficients).map(::purchaseNumber),
            purchaseArray(model.mean).map(::purchaseNumber),
            purchaseArray(model.standardDeviation).map(::purchaseNumber),
            purchaseNumber(model.intercept),
            purchaseString(model.sourceSha256),
        )
    val features = CancellationValuePolicy.features(input)
    val predictions = features.mapValues { (_, row) -> typed.predict(row) }
    val result = js("({})")
    result.features = js("({})")
    result.predictions = js("({})")
    features.forEach { (command, values) -> result.features[command] = values.toTypedArray() }
    predictions.forEach { (command, value) -> result.predictions[command] = value }
    result.selected = CancellationValuePolicy.choose(predictions)
    return JSON.stringify(result)
}

private fun cancellationFields(raw: dynamic, expected: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Cancellation inference requires an object."
    }
    val keys = js("Object.keys(raw)") as Array<String>
    require(keys.toSet() == expected) {
        "Cancellation inference contains missing or unexpected fields."
    }
}
