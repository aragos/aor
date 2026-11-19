package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameRollKind
import dev.aragos.aor.PlayerId
import dev.aragos.aor.SystemNeed
import kotlin.test.Test

class ExperimentChanceTest {
    private val income = SystemNeed.GameRoll(GameRollKind.SHORTAGE, 2)
    private val trade = SystemNeed.Trade(PlayerId("p1"), PlayerId("p2"))
    private val mode = ExperimentChanceMode.BY_TURN_AND_EVENT

    @Test
    fun earlierTradeDoesNotChangeTheSameTurnsIncomeDice() {
        val first = ExperimentChance(3867, mode)
        val second = ExperimentChance(3867, mode)
        second.forNeed(trade, 4, 2).nextInt(3)

        assertThat(rolls(second, 4)).isEqualTo(rolls(first, 4))
    }

    @Test
    fun extraDiceInOneTurnDoNotShiftTheNextTurn() {
        val first = ExperimentChance(3867, mode)
        val second = ExperimentChance(3867, mode)
        rolls(second, 4)

        assertThat(rolls(second, 5)).isEqualTo(rolls(first, 5))
    }

    @Test
    fun copyingPreservesAnAlreadyUsedStreamWithoutSharingItsPosition() {
        val original = ExperimentChance(3867, mode)
        rolls(original, 4)
        val branch = original.copy()

        assertThat(rolls(branch, 4)).isEqualTo(rolls(original, 4))
    }

    private fun rolls(source: ExperimentChance, turn: Int): List<Int> {
        val stream = source.forNeed(income, turn, 2)
        return List(10) { stream.nextInt(1, 7) }
    }
}
