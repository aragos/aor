package dev.aragos.aor.ai

import kotlin.random.Random

/**
 * Optional target-only variation after HARD has selected a card and its timing. Exact-score ties
 * among that card's opponent targets use the supplied decision seed instead of player-ID order. The
 * same seed and candidates reproduce the choice on JVM and JS; no mutable or hidden state is
 * consulted. This neither changes unequal scores nor estimates target strength or military risk.
 */
internal object BotTargetTieBreak {
    const val GATE = "planning.targetTieBreak"

    fun choose(
        observation: BotObservation,
        selected: BotCandidate,
        model: BotModel,
        seed: Int,
        scores: Map<String, Double>,
    ): BotCandidate {
        if (model.weights[GATE] != 1.0 || observation.decision != "play-cards") return selected
        val target = selected.playerTarget ?: return selected
        if (target.value == observation.playerId) return selected
        val score =
            checkNotNull(scores[selected.command]) {
                "A selected target requires its cached action score."
            }
        val tied =
            observation.candidates
                .filter { candidate ->
                    candidate.group == selected.group &&
                        candidate.family == "play" &&
                        candidate.playerTarget != null &&
                        candidate.playerTarget.value != observation.playerId &&
                        scores[candidate.command] == score
                }
                .sortedBy { candidate ->
                    checkNotNull(candidate.playerTarget) {
                        "A tied opponent choice requires its typed target."
                    }
                }
        if (tied.size < 2) return selected
        return tied[Random(seed).nextInt(tied.size)]
    }
}
