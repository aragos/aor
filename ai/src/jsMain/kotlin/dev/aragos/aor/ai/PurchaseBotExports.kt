@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/**
 * One immutable actor is parsed per worker. Its manifest binds the exact qualified ordinary model
 * and setup; ordinary play is always computed first, including on an optional-asset failure. An
 * explicit ordinary binding permits a distinct frozen purchase prior. Purchase input carries no
 * recommendation: the selected prior model supplies the network's baseline command.
 */
@JsExport
class PurchaseBotSelector(baselineModelJson: String, purchaseModelJson: String) {
    private val baselineJson = baselineModelJson
    private val purchase = readQualifiedPurchaseAsset(purchaseModelJson)

    fun choose(
        observationJson: String,
        purchaseJson: String?,
        difficulty: String,
        seed: Int,
    ): String {
        val observation = readBotObservation(observationJson)
        val level = BotDifficulty.valueOf(difficulty.uppercase())
        val baselineModel = readBotModel(baselineJson, observation)
        val baseline = BotPolicy.choose(observation, level, seed, baselineModel)
        val actor = purchase ?: return baseline
        if (
            purchaseJson == null ||
                !PurchasePolicyScope.supports(observation, level) ||
                (actor.ordinaryBaseline ?: actor.baseline) != baselineModel
        ) {
            return baseline
        }
        return try {
            require(purchaseJson.length <= 8_000_000) {
                "Purchase projection exceeds its transport bound."
            }
            val raw = JSON.parse<dynamic>(purchaseJson)
            val projection =
                PurchaseProjection(
                    encoderVersion = purchaseString(raw.encoderVersion),
                    stateFeatures = purchaseMap(raw.stateFeatures),
                    actions =
                        purchaseArray(raw.actions).map { action ->
                            PurchaseAction(
                                purchaseString(action.command),
                                purchaseMap(action.features),
                            )
                        },
                )
            require(
                projection.actions.map(PurchaseAction::command) ==
                    observation.candidates.map(BotCandidate::command).sorted()
            ) {
                "Purchase projection must describe exactly the ordinary offered commands."
            }
            val prior =
                if (actor.ordinaryBaseline == null) {
                    baseline
                } else {
                    BotPolicy.choose(observation, BotDifficulty.HARD, seed, actor.baseline)
                }
            PurchasePolicy.choose(
                    observation = projection.bindBaseline(prior),
                    model = actor.model,
                    seed = PurchasePolicy.actorSeed(seed),
                )
                .command
        } catch (_: Throwable) {
            // Optional learned policies must not strand an otherwise playable automated seat.
            baseline
        }
    }
}

/**
 * [baseline] is the frozen purchase prior. [ordinaryBaseline] binds the runtime ordinary model;
 * absence preserves the legacy contract that the prior and ordinary model must match.
 */
private data class QualifiedPurchaseAsset(
    val baseline: BotModel,
    val ordinaryBaseline: BotModel?,
    val model: PurchasePolicyModel,
)

private fun readQualifiedPurchaseAsset(json: String): QualifiedPurchaseAsset? =
    try {
        require(json.isNotBlank() && json.length <= 26_000_000) {
            "A qualified purchase asset must fit its transport bound."
        }
        val raw = JSON.parse<dynamic>(json)
        require(
            purchaseInteger(raw.schemaVersion) == 1 &&
                purchaseString(raw.actorVersion) == PurchasePolicy.ACTOR_VERSION &&
                purchaseString(raw.encoderVersion) == PurchaseObservationEncoder.ENCODER_VERSION &&
                purchaseString(raw.rulesVersion) == PurchasePolicyScope.RULES_VERSION &&
                purchaseString(raw.dataVersion) == PurchasePolicyScope.DATA_VERSION &&
                readConfiguration(raw.configuration) == PurchasePolicyScope.configuration
        ) {
            "The purchase actor must declare its exact qualified versions and configuration."
        }
        QualifiedPurchaseAsset(
            baseline = readPurchaseBaseline(raw.baseline),
            ordinaryBaseline =
                if (jsTypeOf(raw.ordinaryBaseline) == "undefined") {
                    null
                } else {
                    readPurchaseBaseline(raw.ordinaryBaseline)
                },
            model = readPurchaseModel(raw.model),
        )
    } catch (_: Throwable) {
        null
    }

private fun readPurchaseBaseline(raw: dynamic): BotModel {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "A qualified purchase baseline must be a model object."
    }
    return BotModels.fromSerializedWeights(
        schemaVersion = purchaseInteger(raw.schemaVersion),
        weights = purchaseMap(raw.weights),
    )
}
