package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlayerAdvancesTest {
    @Test
    fun acquisitionHistoryDerivesCurrentOwnershipAndPrintedScore() {
        val advances =
            PlayerAdvances(
                listOf(
                    AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1),
                    AdvanceAcquisition(AdvanceId.NATIONALISM, 2),
                )
            )

        assertThat(advances.owned).containsExactly(AdvanceId.THE_HEAVENS, AdvanceId.NATIONALISM)
        assertThat(advances.score).isEqualTo(90)
    }

    @Test
    fun currentTurnAcquisitionsDoNotSupplyCategoryCredits() {
        val advances =
            PlayerAdvances(
                listOf(
                    AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1),
                    AdvanceAcquisition(AdvanceId.HUMAN_BODY, 2),
                )
            )

        assertThat(advances.creditsFor(AdvanceCategory.SCIENCE, currentTurn = 2)).isEqualTo(20)
    }

    @Test
    fun onlyCurrentTurnAcquisitionsSupplyMiseryReliefRatings() {
        val advances =
            PlayerAdvances(
                listOf(
                    AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1),
                    AdvanceAcquisition(AdvanceId.HUMAN_BODY, 2),
                )
            )

        assertThat(advances.miseryReliefFor(currentTurn = 2)).isEqualTo(10)
    }

    @Test
    fun tierCountIsLimitedByTheLeastCompleteCategory() {
        val advances =
            PlayerAdvances(
                AdvanceId.entries
                    .filter { advance ->
                        advance !in
                            setOf(
                                AdvanceId.NEW_WORLD,
                                AdvanceId.SEAWORTHY_VESSELS,
                                AdvanceId.OCEAN_NAVIGATION,
                            )
                    }
                    .map { advance -> AdvanceAcquisition(advance, 1) }
            )

        assertThat(advances.tierCount).isEqualTo(1)
    }

    @Test
    fun historyCannotContainFutureAcquisitionsForACreditCalculation() {
        val advances = PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.THE_HEAVENS, 3)))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances.creditsFor(AdvanceCategory.SCIENCE, currentTurn = 2)
            }

        assertThat(failure).hasMessageThat().contains("future turn")
    }
}
