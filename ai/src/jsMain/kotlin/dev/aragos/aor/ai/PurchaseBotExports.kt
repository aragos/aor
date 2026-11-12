@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/**
 * Version-specific immutable actors are parsed once per worker. Each binds its qualified model and
 * setup; ordinary play is always computed first, including on an optional-asset failure. An
 * explicit ordinary binding permits a distinct frozen purchase prior. Purchase input carries no
 * recommendation: the selected prior model supplies the network's baseline command.
 */
@JsExport
class PurchaseBotSelector(baselineModelJson: String, purchaseModelJson: String) {
    private val baselineJson = baselineModelJson
    private val purchases = readQualifiedPurchaseAssets(purchaseModelJson)

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
        val actor =
            purchases.firstOrNull { it.rulesVersion == observation.rulesVersion } ?: return baseline
        if (
            purchaseJson == null ||
                !PurchasePolicyScope.supports(observation, level) ||
                (actor.ordinaryBaseline ?: actor.baseline) != baselineModel
        ) {
            return baseline
        }
        return try {
            val projection = readPurchaseProjection(purchaseJson)
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
    val rulesVersion: String,
    val baseline: BotModel,
    val ordinaryBaseline: BotModel?,
    val model: PurchasePolicyModel,
)

/** Ordered version-specific assets retain legacy saves without transferring qualification. */
private fun readQualifiedPurchaseAssets(json: String): List<QualifiedPurchaseAsset> =
    try {
        require(json.isNotBlank() && json.length <= 26_000_000) {
            "A qualified purchase asset must fit its transport bound."
        }
        val raw = JSON.parse<dynamic>(json)
        if (jsTypeOf(raw.models) == "undefined") {
            listOfNotNull(readQualifiedPurchaseAsset(raw))
        } else {
            require(purchaseInteger(raw.schemaVersion) == 1) { "Unknown purchase bundle schema." }
            val models = purchaseArray(raw.models)
            require(models.size in 1..16) { "A purchase bundle requires one to sixteen entries." }
            models.mapNotNull { readQualifiedPurchaseAsset(it) }
        }
    } catch (_: Throwable) {
        emptyList()
    }

private fun readQualifiedPurchaseAsset(raw: dynamic): QualifiedPurchaseAsset? =
    try {
        require(
            purchaseInteger(raw.schemaVersion) == 1 &&
                purchaseString(raw.actorVersion) == PurchasePolicy.ACTOR_VERSION &&
                purchaseString(raw.encoderVersion) == PurchaseObservationEncoder.ENCODER_VERSION &&
                purchaseString(raw.rulesVersion) in PurchasePolicyScope.rulesVersions &&
                purchaseString(raw.dataVersion) == PurchasePolicyScope.DATA_VERSION &&
                readConfiguration(raw.configuration) == PurchasePolicyScope.configuration
        ) {
            "The purchase actor must declare its exact qualified versions and configuration."
        }
        QualifiedPurchaseAsset(
            rulesVersion = purchaseString(raw.rulesVersion),
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

internal fun readPurchaseBaseline(raw: dynamic): BotModel {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "A qualified purchase baseline must be a model object."
    }
    return BotModels.fromSerializedWeights(
        schemaVersion = purchaseInteger(raw.schemaVersion),
        weights = purchaseMap(raw.weights),
    )
}

internal fun readPurchaseProjection(json: String): PurchaseProjection {
    require(json.length <= 8_000_000) { "Purchase projection exceeds its transport bound." }
    val raw = JSON.parse<dynamic>(json)
    return PurchaseProjection(
        encoderVersion = purchaseString(raw.encoderVersion),
        stateFeatures = purchaseMap(raw.stateFeatures),
        actions =
            purchaseArray(raw.actions).map { action ->
                PurchaseAction(purchaseString(action.command), purchaseMap(action.features))
            },
    )
}
