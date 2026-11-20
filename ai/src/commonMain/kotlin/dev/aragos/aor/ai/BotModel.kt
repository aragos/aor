package dev.aragos.aor.ai

import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CatalogVersion
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.LEGACY_RULES_VERSION
import dev.aragos.aor.MapRule
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs

const val BOT_POLICY_VERSION = "aor-policy-1"

/** Controller identity stored with each seat using the evaluated browser checkpoint. */
const val HARD_AUTOMATA_VERSION = "aor-hard-2026-11-20-1"

/** Controller strength is independent of the rules and the engine's player identity. */
enum class BotDifficulty {
    EASY,
    NORMAL,
    HARD,
}

/**
 * Exact public setup on which a downloaded model is qualified. JSON uses enum names for [map] and
 * [initialCard], and booleans for rule switches. Missing [drawThreeKeepTwo] in legacy JSON means
 * false. Rules/catalog versions remain separate model compatibility checks. Missing configuration
 * metadata cannot qualify a scoped model.
 */
data class BotConfiguration(
    val playerCount: Int,
    val map: MapRule,
    val initialCard: InitialCardRule,
    val deckModification: Boolean,
    val immediateMasterArtDiscard: Boolean,
    val drawThreeKeepTwo: Boolean = false,
) {
    init {
        require(playerCount in 3..6) { "A bot configuration requires three through six players." }
    }
}

/**
 * Portable policy input. It contains only the acting seat's public and private observations.
 * Resource amounts are dollars and expansion-pool tokens. Candidates are bounded suggestions,
 * rather than an exhaustive enumeration of the command language.
 */
data class BotObservation(
    val schemaVersion: Int = 1,
    val playerId: String,
    val decision: String,
    val context: Map<String, Double>,
    val availableCash: Int,
    val availableTokens: Int,
    val candidates: List<BotCandidate>,
    val rulesVersion: String,
    val dataVersion: String,
    val configuration: BotConfiguration? = null,
    val expansionPlanning: BotExpansionPlanning? = null,
    /** Per-dollar reserve features, including active conditions; absent for legacy observations. */
    val reserveShortfallBasis: Map<String, Double>? = null,
    val advancePlanning: BotAdvancePlanning? = null,
    val leaderPlanning: BotLeaderPlanning? = null,
) {
    init {
        require(schemaVersion == 1) { "Unsupported bot observation schema: $schemaVersion." }
        require(playerId.isNotBlank() && decision.isNotBlank()) {
            "A bot observation requires a seat and decision identity."
        }
        require(availableCash >= 0 && availableTokens >= 0) {
            "Observed bot resources cannot be negative."
        }
        require(context.size <= 128 && context.values.all(Double::isFinite)) {
            "Bot context must contain bounded finite values."
        }
        require(candidates.size in 1..1024) {
            "A bot observation requires 1 through 1024 candidates."
        }
        val byCommand = candidates.associateBy(BotCandidate::command)
        require(byCommand.size == candidates.size) {
            "Bot candidate commands must be unique."
        }
        require(
            expansionPlanning == null ||
                decision == "expand" &&
                    expansionPlanning.steps.keys.all { command ->
                        byCommand[command]?.family == "expansion"
                    }
        ) {
            "Expansion planning facts must belong to offered expansion candidates."
        }
        require(
            advancePlanning == null ||
                decision == "purchase-advances" &&
                    advancePlanning.steps.keys.all { byCommand[it]?.family == "advance" }
        ) {
            "Advance planning facts must belong to offered advance candidates."
        }
        requireReserveShortfallBasis(reserveShortfallBasis)
        require(rulesVersion in setOf(RULES_VERSION, LEGACY_RULES_VERSION)) {
            "Unsupported bot rules version: $rulesVersion."
        }
        require(
            leaderPlanning == null ||
                decision == "play-cards" &&
                    leaderPlanning.leaders.keys.all { byCommand[it]?.family == "play" } &&
                    leaderPlanning.cashCards.all { byCommand[it]?.family == "play" }
        ) {
            "Leader forecasts must belong to offered card-play commands."
        }
        CatalogVersion.fromWire(dataVersion)
    }
}

/** Shared boundary for actual observations and nonrecursive hypothetical purchase forecasts. */
internal fun requireReserveShortfallBasis(basis: Map<String, Double>?) {
    require(
        basis == null ||
            basis.size in 1..128 &&
                basis["reserveShortfall"] == 1.0 &&
                basis.all { (name, value) ->
                    name.length <= 120 &&
                        (name == "reserveShortfall" || name.endsWith(".reserveShortfall")) &&
                        value.isFinite() &&
                        value in 0.0..1.0
                }
    ) {
        "Reserve planning requires bounded per-dollar shortfall features and a unit base."
    }
}

/**
 * Features are contextual numerical inputs to the learned linear evaluator. [group] identifies
 * mutually exclusive uses of one province, card, or advance within a speculative plan. A plan
 * considers only currently offered actions; it is advisory and never authorizes a command batch.
 */
data class BotCandidate(
    val command: String,
    val features: Map<String, Double>,
    val cashCost: Int = 0,
    val tokenCost: Int = 0,
    val group: String = "",
    val family: String = "other",
    /** Authorized player choice for this card; absent in legacy observations and other actions. */
    val playerTarget: PlayerId? = null,
) {
    init {
        require(
            command.isNotBlank() && command.length <= 2000 && '\n' !in command && '\r' !in command
        ) {
            "A bot candidate requires one bounded canonical command."
        }
        require(cashCost >= 0 && tokenCost >= 0) {
            "Bot candidate resource costs cannot be negative."
        }
        require(
            features.size <= 512 &&
                features.all { (key, value) ->
                    key.length in 1..120 && value.isFinite() && abs(value) <= 1.0e12
                }
        ) {
            "Bot candidate features require bounded names and finite values."
        }
        if (playerTarget != null) {
            require(family == "play" && group.startsWith("card.")) {
                "A player target must belong to a played card's candidate group."
            }
            val expected =
                CardCommand.PlayCard(
                    group.removePrefix("card."),
                    CardChoice.PlayerChoice(playerTarget),
                )
            require(command == expected.toText()) {
                "A player target must match its canonical card command."
            }
        }
    }
}

/**
 * Versioned in-memory evaluator. Missing weights contribute zero. External schema-one documents
 * instead overlay the prior through [BotModels.fromSerializedWeights]; writers use
 * [BotModels.serializedWeights] to preserve this model's exact missing-weight behavior.
 */
data class BotModel(val schemaVersion: Int = 1, val weights: Map<String, Double>) {
    init {
        require(schemaVersion == 1) { "Unsupported bot model schema: $schemaVersion." }
        require(weights.size <= 4096) { "A bot model may contain at most 4096 feature weights." }
        require(
            weights.all { (name, weight) ->
                name.length in 1..120 && weight.isFinite() && abs(weight) <= 1.0e6
            }
        ) {
            "Bot weights require short feature names and finite values."
        }
    }
}

/** Initial policy prior; offline self-play can replace these weights without changing rules. */
object BotModels {
    /** Controller parameters never act as ordinary candidate or planning features. */
    internal fun reservedFeature(name: String): Boolean =
        BotTokenValue.reserved(name) ||
            BotExpansionModel.reserved(name) ||
            BotMilitaryModel.reserved(name)

    /**
     * Schema-one wire weights are overrides of the prior. Explicit zeros disable prior features,
     * and unknown learned feature names retain their supplied values. This contract is shared by
     * browser downloads and offline model files; direct [BotModel] construction never adds a prior.
     */
    fun fromSerializedWeights(
        weights: Map<String, Double>,
        schemaVersion: Int = 1,
    ): BotModel = BotModel(schemaVersion, default.weights + weights)

    /**
     * Complete wire weights for an in-memory policy. Omitted prior keys must be explicit zeros so
     * reading the document cannot restore their default values. The complete representation must
     * still fit [BotModel]'s external weight bound.
     */
    fun serializedWeights(model: BotModel): Map<String, Double> =
        model.copy(weights = default.weights.mapValues { 0.0 } + model.weights).weights

    val default: BotModel =
        BotModel(
            weights =
                mapOf(
                    "cash" to 1.0,
                    "opponentCash" to -0.32,
                    "points" to 1.0,
                    "misery" to -1.15,
                    "opponentMisery" to 0.28,
                    "chaos" to -10000.0,
                    "income" to 3.2,
                    "commodity" to 1.4,
                    "reach" to 2.1,
                    "discount" to 1.0,
                    "cards" to 7.5,
                    "burden" to -14.0,
                    "tokens" to 2.1,
                    "risk" to -6.0,
                    "reserveShortfall" to -3.2,
                    "progress" to 0.2,
                    "stabilizeCash" to 0.25,
                    "bidPreference" to 8.0,
                    "tokenPreference" to 12.0,
                    "military" to 5.0,
                    "cathedral" to 11.0,
                    "windmill" to 2.0,
                    "order" to 2.0,
                    "protection" to 0.25,
                    "initialCommodity" to 2.0,
                    "capitalCommodityPotential" to 0.8,
                    SecondCapitalGeography.FEATURE to 0.5,
                    "capitalLeaderOpportunity" to 0.15,
                    "endgame.cash" to 0.3,
                    "endgame.income" to -2.6,
                    "endgame.cards" to -5.0,
                    "endgame.discount" to -0.9,
                    "endgame.reach" to -1.5,
                )
        )
}
