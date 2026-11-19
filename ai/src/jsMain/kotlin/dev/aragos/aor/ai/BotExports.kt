@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.Commodity
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.PlayerId
import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** JSON boundary keeps domain classes and generated Kotlin collection types out of browser code. */
@JsExport
fun chooseBotMove(
    observationJson: String,
    modelJson: String,
    difficulty: String,
    seed: Int,
): String {
    val observation = readBotObservation(observationJson)
    val level = BotDifficulty.valueOf(difficulty.uppercase())
    val model = readBotModel(modelJson, observation, level)
    return BotPolicy.choose(observation, level, seed, model)
}

internal fun readBotObservation(observationJson: String): BotObservation {
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
                        playerTarget = readPlayerTarget(candidate.playerTarget),
                    )
                },
            rulesVersion = raw.rulesVersion as String,
            dataVersion = raw.dataVersion as String,
            configuration = readConfiguration(raw.configuration),
            expansionPlanning = readExpansionPlanning(raw.expansionPlanning),
            reserveShortfallBasis =
                if (raw.reserveShortfallBasis == null) null
                else numberMap(raw.reserveShortfallBasis),
            advancePlanning = readAdvancePlanning(raw.advancePlanning),
            leaderPlanning = readLeaderPlanning(raw.leaderPlanning),
        )
    return observation
}

private fun readLeaderPlanning(raw: dynamic): BotLeaderPlanning? {
    if (raw == null) return null
    val commands = js("Object.keys(raw.leaders)") as Array<String>
    return BotLeaderPlanning(
        candidates =
            planningArray(raw.candidates, "leader candidates").map { candidate ->
                BotCandidate(
                    command = candidate.command as String,
                    features = numberMap(candidate.features),
                    cashCost = planningInteger(candidate.cashCost, "forecast cash cost"),
                    tokenCost = planningInteger(candidate.tokenCost, "forecast token cost"),
                    group = candidate.group as String,
                    family = candidate.family as String,
                    playerTarget = readPlayerTarget(candidate.playerTarget),
                )
            },
        forecasts =
            planningArray(raw.forecasts, "purchase forecasts").map { forecast ->
                BotPurchaseForecast(
                    stage = BotPurchaseForecastStage.valueOf(forecast.stage as String),
                    cash = planningInteger(forecast.cash, "forecast cash"),
                    stabilization = (forecast.stabilization as Number).toDouble(),
                    candidates =
                        planningArray(forecast.candidates, "forecast candidate indices").map {
                            planningInteger(it, "forecast candidate index")
                        },
                    reserveShortfallBasis =
                        if (forecast.reserveShortfallBasis == null) null
                        else numberMap(forecast.reserveShortfallBasis),
                    advancePlanning = readAdvancePlanning(forecast.advancePlanning),
                )
            },
        leaders =
            commands.associateWith { command ->
                val leader = raw.leaders[command]
                BotLeaderAlternative(
                    withoutLeader = readLeaderContinuation(leader.withoutLeader),
                    withLeader = readLeaderContinuation(leader.withLeader),
                    retainedDiscount = (leader.retainedDiscount as Number).toDouble(),
                )
            },
        cashCards = planningArray(raw.cashCards, "leader cash cards").map { it as String }.toSet(),
    )
}

private fun readLeaderContinuation(raw: dynamic): BotLeaderContinuation? {
    if (raw == null) return null
    val commands = js("Object.keys(raw.advances)") as Array<String>
    return BotLeaderContinuation(
        shipping = planningInteger(raw.shipping, "leader shipping forecast"),
        advances =
            commands.associateWith { planningInteger(raw.advances[it], "leader advance forecast") },
    )
}

private fun readPlayerTarget(raw: dynamic): PlayerId? {
    if (raw == null) return null
    require(jsTypeOf(raw) == "string") { "A bot player target must be a player id string." }
    return PlayerId(raw as String)
}

internal fun readBotModel(
    modelJson: String,
    observation: BotObservation,
    difficulty: BotDifficulty,
): BotModel {
    if (modelJson.isBlank() || modelJson == "{}" || modelJson.length > 1_000_000) {
        return BotModels.default
    }
    return try {
        val document = JSON.parse<dynamic>(modelJson)
        if (document.models != null) {
            require(readModelSchema(document.schemaVersion) == 1) {
                "Unsupported bot model bundle schema."
            }
            require(js("Array.isArray(document.models)") as Boolean) {
                "Bot model bundle entries must be an array."
            }
            val models = document.models as Array<dynamic>
            require(models.size in 1..16) {
                "A bot model bundle requires one through sixteen entries."
            }
            models.firstNotNullOfOrNull { entry -> compatibleModel(entry, observation, difficulty) }
                ?: BotModels.default
        } else {
            compatibleModel(document, observation, difficulty) ?: BotModels.default
        }
    } catch (_: Throwable) {
        // Cached policies are replaceable assets. An invalid download must not strand a game.
        BotModels.default
    }
}

/**
 * Bundles select the first valid compatible entry. Legacy unscoped single-model documents remain
 * supported; a scoped entry must declare exact rules/catalog identities as well as its setup.
 */
private fun compatibleModel(
    document: dynamic,
    observation: BotObservation,
    difficulty: BotDifficulty,
): BotModel? =
    try {
        val raw = if (document.model != null) document.model else document
        val rules = raw.rulesVersion ?: document.rulesVersion
        val data = raw.dataVersion ?: document.dataVersion
        val level = raw.difficulty ?: document.difficulty
        val schemaVersion = readModelSchema(raw.schemaVersion)
        val configuration = readConfiguration(raw.configuration ?: document.configuration)
        if (
            schemaVersion != observation.schemaVersion ||
                (rules != null && rules != observation.rulesVersion) ||
                (data != null && data != observation.dataVersion) ||
                (level != null && level != difficulty.name) ||
                (configuration != null &&
                    (rules == null || data == null || configuration != observation.configuration))
        ) {
            null
        } else {
            BotModels.fromSerializedWeights(
                schemaVersion = schemaVersion,
                weights = numberMap(raw.weights),
            )
        }
    } catch (_: Throwable) {
        // One malformed replaceable asset cannot hide a later compatible bundle entry.
        null
    }

private fun readModelSchema(raw: dynamic): Int {
    require(jsTypeOf(raw) == "number") { "Bot model schemaVersion must be numerical." }
    val value = (raw as Number).toDouble()
    require(value.isFinite() && value == value.toInt().toDouble()) {
        "Bot model schemaVersion must be an integer within the signed 32-bit range."
    }
    return value.toInt()
}

internal fun readConfiguration(raw: dynamic): BotConfiguration? {
    if (raw == null) return null
    require(jsTypeOf(raw.playerCount) == "number") { "Bot player count must be numerical." }
    val playerCount = (raw.playerCount as Number).toDouble()
    require(playerCount.isFinite() && playerCount == playerCount.toInt().toDouble()) {
        "Bot player count must be a finite integer."
    }
    require(jsTypeOf(raw.deckModification) == "boolean") {
        "Bot deck modification must be a boolean."
    }
    require(jsTypeOf(raw.immediateMasterArtDiscard) == "boolean") {
        "Bot immediate Master Art discard must be a boolean."
    }
    val drawThreeKeepTwo =
        if (jsTypeOf(raw.drawThreeKeepTwo) == "undefined") {
            false
        } else {
            require(jsTypeOf(raw.drawThreeKeepTwo) == "boolean") {
                "Bot draw three keep two must be a boolean."
            }
            raw.drawThreeKeepTwo as Boolean
        }
    return BotConfiguration(
        playerCount = playerCount.toInt(),
        map = MapRule.valueOf(raw.map as String),
        initialCard = InitialCardRule.valueOf(raw.initialCard as String),
        deckModification = raw.deckModification as Boolean,
        immediateMasterArtDiscard = raw.immediateMasterArtDiscard as Boolean,
        drawThreeKeepTwo = drawThreeKeepTwo,
    )
}

private fun readExpansionPlanning(raw: dynamic): BotExpansionPlanning? {
    if (raw == null) return null
    require(jsTypeOf(raw.industry) == "boolean") {
        "Planning Industry ownership must be a boolean."
    }
    require(
        raw.steps != null &&
            jsTypeOf(raw.steps) == "object" &&
            !(js("Array.isArray(raw.steps)") as Boolean)
    ) {
        "Planning steps must be an object keyed by candidate command."
    }
    val commands = js("Object.keys(raw.steps)") as Array<String>
    return BotExpansionPlanning(
        markerStock = planningInteger(raw.markerStock, "marker stock"),
        overseasSlots =
            if (raw.overseasSlots == null) 0
            else planningInteger(raw.overseasSlots, "overseas slots"),
        industry = raw.industry as Boolean,
        commodities =
            planningArray(raw.commodities, "commodities").map { forecast ->
                BotPlanningCommodity(
                    commodity = Commodity.valueOf(forecast.commodity as String),
                    markets = planningInteger(forecast.markets, "markets"),
                    baseValue = planningInteger(forecast.baseValue, "base value"),
                    adjustmentOffset = planningInteger(forecast.adjustmentOffset, "adjustment"),
                    outsideCards = planningInteger(forecast.outsideCards, "outside cards"),
                )
            },
        heldCards = planningArray(raw.heldCards, "held cards").map(::planningCommodities),
        steps =
            commands.associateWith { command ->
                val step = raw.steps[command]
                BotExpansionStep(
                    markerCost = planningInteger(step.markerCost, "marker cost"),
                    commodities = planningCommodities(step.commodities),
                    newOverseasCost =
                        if (step.newOverseasCost == null) 0
                        else planningInteger(step.newOverseasCost, "overseas cost"),
                )
            },
    )
}

private fun readAdvancePlanning(raw: dynamic): BotAdvancePlanning? {
    if (raw == null) return null
    require(
        raw.steps != null &&
            jsTypeOf(raw.steps) == "object" &&
            !(js("Array.isArray(raw.steps)") as Boolean)
    ) {
        "Advance planning steps must be an object keyed by candidate command."
    }
    require(raw.ownsInterestProfit == null || jsTypeOf(raw.ownsInterestProfit) == "boolean") {
        "Planning Interest & Profit ownership must be a boolean."
    }
    val commands = js("Object.keys(raw.steps)") as Array<String>
    return BotAdvancePlanning(
        steps =
            commands.associateWith { command ->
                val step = raw.steps[command]
                BotAdvanceStep(
                    advance = AdvanceId.valueOf(step.advance as String),
                    institutionalResearchDiscount =
                        planningInteger(step.institutionalResearchDiscount, "research discount"),
                )
            },
        ownsInterestProfit =
            if (raw.ownsInterestProfit == null) false else raw.ownsInterestProfit as Boolean,
        ordinaryIncome =
            if (raw.ordinaryIncome == null) null
            else planningInteger(raw.ordinaryIncome, "ordinary income"),
    )
}

private fun planningArray(raw: dynamic, name: String): Array<dynamic> {
    require(js("Array.isArray(raw)") as Boolean) { "Planning $name must be an array." }
    return raw as Array<dynamic>
}

private fun planningCommodities(raw: dynamic): Set<Commodity> =
    planningArray(raw, "card or market choices").map { Commodity.valueOf(it as String) }.toSet()

private fun planningInteger(raw: dynamic, name: String): Int {
    require(jsTypeOf(raw) == "number") { "Planning $name must be numerical." }
    val value = (raw as Number).toDouble()
    require(value.isFinite() && value == value.toInt().toDouble()) {
        "Planning $name must be a finite integer."
    }
    return value.toInt()
}

private fun numberMap(raw: dynamic): Map<String, Double> {
    val keys = js("Object.keys(raw)") as Array<String>
    return keys.associateWith { key -> (raw[key] as Number).toDouble() }
}
