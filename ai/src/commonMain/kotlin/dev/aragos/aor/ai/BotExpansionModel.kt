package dev.aragos.aor.ai

/**
 * Optional absolute expansion coefficients, separately qualified from the ordinary model. Exactly
 * one enables the complete eight-coefficient set for HARD `expand` decisions. Other decisions and
 * difficulties, missing coefficients and non-unit gates retain the ordinary model. Model asset
 * routing owns rules/configuration compatibility; this hook does not establish qualification.
 */
object BotExpansionModel {
    private const val PREFIX = "planning.expansionModel."
    const val GATE = "${PREFIX}enabled"

    /** [key] stores the replacement coefficient for [feature], never an additive correction. */
    enum class Coefficient(val feature: String) {
        TOKENS("tokens"),
        PROGRESS("progress"),
        REACH("reach"),
        COMMODITY("commodity"),
        INCOME("income"),
        RISK("risk"),
        EXPANSION("action.expansion"),
        PASS("action.pass");

        val key: String
            get() = "$PREFIX$feature"
    }

    internal fun reserved(name: String): Boolean = name.startsWith(PREFIX)

    internal fun forObservation(
        observation: BotObservation,
        difficulty: BotDifficulty,
        model: BotModel,
    ): BotModel {
        if (
            difficulty != BotDifficulty.HARD ||
                observation.decision != "expand" ||
                model.weights[GATE] != 1.0
        ) {
            return model
        }
        val coefficients =
            Coefficient.entries.associate { coefficient ->
                coefficient.feature to (model.weights[coefficient.key] ?: return model)
            }
        // Removing the transport keys also keeps a complete maximum-size model within its bound.
        return model.copy(weights = model.weights.filterKeys { !reserved(it) } + coefficients)
    }
}
