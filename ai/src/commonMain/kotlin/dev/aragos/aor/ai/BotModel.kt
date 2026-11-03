package dev.aragos.aor.ai

import dev.aragos.aor.CatalogVersion
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs

const val BOT_POLICY_VERSION = "aor-policy-1"

/** Controller strength is independent of the rules and the engine's player identity. */
enum class BotDifficulty {
    EASY,
    NORMAL,
    HARD,
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
        require(candidates.map(BotCandidate::command).distinct().size == candidates.size) {
            "Bot candidate commands must be unique."
        }
        require(rulesVersion == RULES_VERSION) { "Unsupported bot rules version: $rulesVersion." }
        CatalogVersion.fromWire(dataVersion)
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
    }
}

/** Versioned, compact trainable evaluator. Missing feature weights contribute zero. */
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
                    "capitalOpportunity" to 0.5,
                    "capitalLeaderOpportunity" to 0.15,
                    "endgame.cash" to 0.3,
                    "endgame.income" to -2.6,
                    "endgame.cards" to -5.0,
                    "endgame.discount" to -0.9,
                    "endgame.reach" to -1.5,
                )
        )
}
