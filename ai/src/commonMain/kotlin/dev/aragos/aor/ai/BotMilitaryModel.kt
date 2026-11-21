package dev.aragos.aor.ai

/**
 * Optional absolute military card coefficients qualified for Hard play. Exactly one and all four
 * coefficients enable replacement; other difficulties and incomplete entries retain the ordinary
 * model. Asset routing owns rules/configuration scope. Support alone does not activate a model.
 */
object BotMilitaryModel {
    private const val PREFIX = "planning.militaryModel."
    const val GATE = "${PREFIX}enabled"

    /** [key] transports the complete replacement for [feature], never an additive correction. */
    enum class Coefficient(val feature: String) {
        ARMOR("card.armor"),
        STIRRUPS("card.stirrups"),
        LONG_BOW("card.long-bow"),
        GUNPOWDER("card.gunpowder");

        val key: String
            get() = "$PREFIX$feature"
    }

    internal fun reserved(name: String): Boolean = name.startsWith(PREFIX)

    internal fun forDifficulty(difficulty: BotDifficulty, model: BotModel): BotModel {
        if (difficulty != BotDifficulty.HARD || model.weights[GATE] != 1.0) return model
        val coefficients =
            Coefficient.entries.associate { coefficient ->
                coefficient.feature to (model.weights[coefficient.key] ?: return model)
            }
        // Removing transport keys preserves the external model size bound after replacement.
        return model.copy(weights = model.weights.filterKeys { !reserved(it) } + coefficients)
    }
}
