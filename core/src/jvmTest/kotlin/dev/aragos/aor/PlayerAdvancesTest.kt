package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

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

        assertThat(advances.creditsFor(AdvanceCategory.SCIENCE, currentTurn = 2))
            .containsExactly(AdvanceCredit(AdvanceId.THE_HEAVENS, 20))
    }

    @Test
    fun categoryCreditsNameOnlyEligiblePositiveSources() {
        val advances =
            PlayerAdvances(
                listOf(
                    AdvanceAcquisition(AdvanceId.CARAVAN, 1),
                    AdvanceAcquisition(AdvanceId.WIND_WATERMILL, 1),
                    AdvanceAcquisition(AdvanceId.IMPROVED_AGRICULTURE, 1),
                    AdvanceAcquisition(AdvanceId.INTEREST_PROFIT, 1),
                    AdvanceAcquisition(AdvanceId.INDUSTRY, 1),
                    AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1),
                )
            )

        val credits = advances.creditsFor(AdvanceCategory.COMMERCE, currentTurn = 2)

        assertThat(credits)
            .containsExactly(
                AdvanceCredit(AdvanceId.CARAVAN, 10),
                AdvanceCredit(AdvanceId.WIND_WATERMILL, 10),
                AdvanceCredit(AdvanceId.IMPROVED_AGRICULTURE, 10),
                AdvanceCredit(AdvanceId.INTEREST_PROFIT, 10),
            )
            .inOrder()
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
}
