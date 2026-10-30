package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage

/** Competition commitments leave the pool before resolution; count each pending plan once. */
internal fun assertResourceConservation(state: GameState) {
    val commitments =
        state.flow.decisions
            .mapNotNull { decision ->
                when (decision) {
                    is BoardDecision.CathedralChoice -> decision.plan
                    is BoardDecision.CompetitionDice -> decision.plan
                    else -> null
                }
            }
            .distinct()
    for (id in state.playerIds) {
        val owner = state.playerState(id)
        val reserved = commitments.filter { it.player == id }.sumOf { it.addedTokens }
        assertWithMessage("36 tokens for $id after entry ${state.entryCount}")
            .that(owner.stockTokens + owner.poolTokens + state.board.tokenCount(id) + reserved)
            .isEqualTo(36)
        assertThat(state.board.markerCount(id)).isAtMost(25)
    }
}
