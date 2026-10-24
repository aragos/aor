package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class SupportedGameCompletionTest {
    @Test
    fun everySupportedPlayerCountCompletesThroughAllThreeEpochs() {
        val completed = (3..6).map(::completedGame)

        assertThat(completed.map { game -> game.state.stage })
            .containsExactly(Stage.FINISHED, Stage.FINISHED, Stage.FINISHED, Stage.FINISHED)
        assertThat(completed.map { game -> game.state.currentEpoch })
            .containsExactly(Epoch.THREE, Epoch.THREE, Epoch.THREE, Epoch.THREE)
        assertThat(
                completed.map { game -> game.lines.count { line -> line.startsWith("!shuffle ") } }
            )
            .containsExactly(3, 3, 3, 3)
    }

    @Test
    fun everySupportedPlayerCountReceivesFinalScores() {
        val completed = (3..6).map(::completedGame)

        assertThat(
                completed.map { game ->
                    game.state.viewForPlayer(game.state.playerIds.first()).scores.size
                }
            )
            .containsExactly(3, 4, 5, 6)
            .inOrder()
    }

    @Test
    fun everySupportedPlayerCountReplaysToItsCompletedState() {
        for (playerCount in 3..6) {
            val completed = completedGame(playerCount)

            assertThat(replay(completed.state.header, completed.lines)).isEqualTo(completed.state)
        }
    }

    private fun completedGame(playerCount: Int): Scenario {
        val dealt = Scenario.create(playerCount).play("p1: start")
        val bid =
            (1..playerCount).fold(dealt) { current, number ->
                current.play("p$number: bid-capital ${playerCount + 1 - number}")
            }
        val selected =
            bid.state.capitalOrder.fold(bid) { current, actor ->
                current.play("$actor: choose-capital ${current.state.availableCapitals.first()}")
            }
        return selected.complete()
    }
}
