package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class SupportedGameCompletionTest {
    @Test
    fun everySupportedPlayerCountCompletesThroughOrdinaryCommands() {
        val completed =
            (3..6).map { playerCount ->
                val dealt = Scenario.create(playerCount).play("p1: start")
                val bid =
                    (1..playerCount).fold(dealt) { current, number ->
                        current.play("p$number: bid-capital ${playerCount + 1 - number}")
                    }
                val selected =
                    bid.state.capitalOrder.fold(bid) { current, actor ->
                        current.play(
                            "$actor: choose-capital ${current.state.availableCapitals.first()}"
                        )
                    }
                selected.complete().state
            }
        assertThat(completed.map(GameState::stage))
            .containsExactly(
                Stage.FINISHED,
                Stage.FINISHED,
                Stage.FINISHED,
                Stage.FINISHED,
            )
        assertThat(
                completed.map { state -> state.viewForPlayer(state.playerIds.first()).scores.size }
            )
            .containsExactly(3, 4, 5, 6)
            .inOrder()
    }
}
