@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/** JSON boundary keeps domain classes and generated Kotlin collection types out of browser code. */
@JsExport
fun chooseBotMove(
    observationJson: String,
    modelJson: String,
    difficulty: String,
    seed: Int,
): String {
    require(observationJson.length <= 8_000_000) {
        "The bot observation exceeds its transport bound."
    }
    val raw = JSON.parse<dynamic>(observationJson)
    val observation =
        BotObservation(
            schemaVersion = (raw.schemaVersion as Number).toInt(),
            playerId = raw.playerId as String,
            decision = raw.decision as String,
            context = numberMap(raw.context),
            availableCash = (raw.availableCash as Number).toInt(),
            availableTokens = (raw.availableTokens as Number).toInt(),
            candidates =
                (raw.candidates as Array<dynamic>).map { candidate ->
                    BotCandidate(
                        command = candidate.command as String,
                        features = numberMap(candidate.features),
                        cashCost = (candidate.cashCost as Number).toInt(),
                        tokenCost = (candidate.tokenCost as Number).toInt(),
                        group = candidate.group as String,
                        family = candidate.family as String,
                    )
                },
            rulesVersion = raw.rulesVersion as String,
            dataVersion = raw.dataVersion as String,
            purchase = if (raw.purchase == null) null else readPurchase(raw.purchase),
        )
    val model = readModel(modelJson, observation)
    return BotPolicy.choose(observation, BotDifficulty.valueOf(difficulty.uppercase()), seed, model)
}

private fun readPurchase(raw: dynamic): BotPurchaseSnapshot =
    BotPurchaseSnapshot(
        owner = raw.owner as String,
        currentTurn = (raw.currentTurn as Number).toInt(),
        decision = raw.decision as String,
        seats =
            (raw.seats as Array<dynamic>).map { seat ->
                BotPurchaseSeat(
                    seat.id as String,
                    seat.capital as String,
                    (seat.cash as Number).toInt(),
                )
            },
        cash = (raw.cash as Number).toInt(),
        misery = raw.misery as String,
        ships = raw.ships as String,
        acquisitions =
            (raw.acquisitions as Array<dynamic>).map { acquisition ->
                BotPurchaseAdvance(
                    acquisition.letter as String,
                    (acquisition.turn as Number).toInt(),
                )
            },
        hand =
            (raw.hand as Array<dynamic>).map { card ->
                BotPurchaseCard(
                    card.reference as String,
                    card.card as String,
                    (card.value as Number).toDouble(),
                    card.burden as Boolean,
                )
            },
        lastShipUpgradeTurn = (raw.lastShipUpgradeTurn as Number?)?.toInt(),
        reliefSpaces = (raw.reliefSpaces as Number).toInt(),
        reliefRatingSpent = (raw.reliefRatingSpent as Number).toInt(),
        immediateMasterArtDiscard = raw.immediateMasterArtDiscard as Boolean,
        europeanMap = raw.europeanMap as Boolean,
        papalCategory = raw.papalCategory as String?,
        crusadesPlayed = raw.crusadesPlayed as Boolean,
        leaders =
            (raw.leaders as Array<dynamic>).map { leader ->
                BotPurchaseLeader(
                    card = leader.card as String,
                    owner = leader.owner as String,
                    exposed = leader.exposed as Boolean,
                    protectionCash = (leader.protectionCash as Number).toInt(),
                    claimants = (leader.claimants as Array<String>).toList(),
                    mongolEnhanced = leader.mongolEnhanced as Boolean,
                )
            },
        dataVersion = raw.dataVersion as String,
    )

private fun readModel(modelJson: String, observation: BotObservation): BotModel {
    if (modelJson.isBlank() || modelJson == "{}" || modelJson.length > 1_000_000) {
        return BotModels.default
    }
    return try {
        val document = JSON.parse<dynamic>(modelJson)
        val raw = if (document.model != null) document.model else document
        val rules = raw.rulesVersion ?: document.rulesVersion
        val data = raw.dataVersion ?: document.dataVersion
        if (
            raw.schemaVersion != observation.schemaVersion ||
                (rules != null && rules != observation.rulesVersion) ||
                (data != null && data != observation.dataVersion)
        ) {
            BotModels.default
        } else {
            BotModel(
                schemaVersion = observation.schemaVersion,
                weights = BotModels.default.weights + numberMap(raw.weights),
            )
        }
    } catch (_: Throwable) {
        // Cached policies are replaceable assets. An invalid download must not strand a game.
        BotModels.default
    }
}

private fun numberMap(raw: dynamic): Map<String, Double> {
    val keys = js("Object.keys(raw)") as Array<String>
    return keys.associateWith { key -> (raw[key] as Number).toDouble() }
}
