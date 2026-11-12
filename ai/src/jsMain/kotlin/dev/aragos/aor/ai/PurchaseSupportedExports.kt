@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/** Opt-in research proof boundary only; no model asset or worker selector calls this function. */
@JsExport
fun evaluateSupportedPurchasePolicy(
    observationJson: String,
    referenceJson: String,
    candidateJson: String,
    actorSeed: Int,
): String {
    require(
        observationJson.length <= 8_000_000 &&
            referenceJson.length <= 24_000_000 &&
            candidateJson.length <= 24_000_000
    ) {
        "Supported purchase inference exceeds its transport bounds."
    }
    val observation = readPurchaseObservation(JSON.parse<dynamic>(observationJson))
    val policy =
        PurchaseSupportedPolicy(
            readPurchaseModel(JSON.parse<dynamic>(referenceJson)),
            readPurchaseModel(JSON.parse<dynamic>(candidateJson)),
        )
    val evaluation = policy.evaluate(observation)
    val choice = PurchasePolicy.select(observation, evaluation, PurchasePolicy.uniform(actorSeed))
    return purchaseEvaluationJson(evaluation, choice)
}
