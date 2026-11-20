package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.Stage

/** Choose the best offered local expansion geography only at the second capital selection. */
object SecondCapitalGeography {
    const val VERSION = "second-capital-geography-1"
    const val DECISION = "choose-capital"
    internal const val FEATURE = "capitalOpportunity"

    fun choose(view: GameView, observation: BotObservation): String? {
        if (
            view.stage != Stage.CAPITAL_CHOICE ||
                observation.decision != DECISION ||
                view.players.count { it.capital != null } != 1 ||
                observation.candidates.size < 2
        ) {
            return null
        }
        return observation.candidates
            .maxByOrNull { option ->
                option.features[FEATURE] ?: 0.0
            }
            ?.command
    }
}
