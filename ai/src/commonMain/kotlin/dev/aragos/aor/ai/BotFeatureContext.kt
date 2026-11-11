package dev.aragos.aor.ai

import dev.aragos.aor.GameView

/** Exact legacy setup/endgame expansion, shared by nominal features and research evaluation. */
internal class BotFeatureContext
private constructor(
    val endgame: Double,
    private val conditions: List<String>,
) {
    fun expand(base: Map<String, Double>): Map<String, Double> = buildMap {
        putAll(base)
        for ((feature, value) in base) {
            if (feature.startsWith("capitalHand.") || feature.startsWith("planning.")) continue
            if (endgame != 0.0) put("endgame.$feature", value * endgame)
            for (condition in conditions) {
                put("$condition.$feature", value)
            }
        }
    }

    companion object {
        fun from(view: GameView): BotFeatureContext =
            BotFeatureContext(
                endgame =
                    if (view.endingTriggered) {
                        1.0
                    } else if (view.currentEpoch == 3) {
                        (1.0 - view.deckRemaining / 18.0).coerceIn(0.0, 1.0)
                    } else {
                        0.0
                    },
                conditions =
                    listOf(
                        "count.${view.players.size}",
                        "epoch.${view.currentEpoch}",
                        "map.${view.rules.map.commandValue}",
                        "initial.${view.rules.initialCard.commandValue}",
                    ) +
                        listOfNotNull(
                            "deckModified".takeIf { view.rules.deckModification },
                            "immediateArt".takeIf { view.rules.immediateMasterArtDiscard },
                        ),
            )
    }
}
