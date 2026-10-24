package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlayerEconomyTest {
    @Test
    fun cashStabilizationPaysTheWholeHandCost() {
        val player = buyer().copy(cash = 20, hand = hand(4))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.CASH)

        assertThat(result.player.cash).isEqualTo(10)
        assertThat(result.player.misery).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun middleClassRoundsAnOddStabilizationCostUp() {
        val player = buyer(AdvanceId.MIDDLE_CLASS).copy(cash = 20, hand = hand(2))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.CASH)

        assertThat(result.player.cash).isEqualTo(18)
    }

    @Test
    fun stabilizationCannotBePartlyPaid() {
        val player = buyer().copy(cash = 9, hand = hand(4))

        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.stabilize(player, StabilizationPayment.CASH)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_CASH)
        assertThat(player.cash).isEqualTo(9)
    }

    @Test
    fun stabilizationCanBeDeclinedEvenWhenAffordable() {
        val player = buyer().copy(cash = 20, hand = hand(4))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.MISERY)

        assertThat(result.player.cash).isEqualTo(20)
        assertThat(result.player.misery).isEqualTo(MiseryStep.TEN)
    }

    @Test
    fun anEmptyHandRequiresNoMiseryPayment() {
        val player = buyer().copy(misery = MiseryStep.NINETY)

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.MISERY)

        assertThat(result.player.misery).isEqualTo(MiseryStep.NINETY)
    }

    @Test
    fun unpaidStabilizationUsesTheFewestSpacesAcrossTheHundredBoundary() {
        val player = buyer().copy(misery = MiseryStep.NINETY, hand = hand(5))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.MISERY)

        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED_TWENTY_FIVE)
    }

    @Test
    fun oneTwentyFivePointStepCanCoverATwentyOneDollarDebt() {
        val player = buyer().copy(misery = MiseryStep.HUNDRED, hand = hand(6))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.MISERY)

        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED_TWENTY_FIVE)
    }

    @Test
    fun debtBeyondTheLastPrintedBoxReportsChaos() {
        val player = buyer().copy(misery = MiseryStep.THOUSAND, hand = hand(1))

        val result = PlayerEconomy.stabilize(player, StabilizationPayment.MISERY)

        assertThat(result.status).isEqualTo(EconomyStatus.ENTERED_CHAOS)
        assertThat(result.player.misery).isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun debtCanReachTheLastPrintedBoxWithoutEnteringChaos() {
        assertThat(MiseryRules.payDebt(MiseryStep.NINE_HUNDRED, cost = 100))
            .isEqualTo(MiseryStep.THOUSAND)
    }

    @Test
    fun hugeHandsAndDebtsDoNotOverflow() {
        val cost = PlayerEconomy.stabilizationCost(Int.MAX_VALUE, middleClass = true)

        assertThat(cost).isEqualTo(1_152_921_504_069_976_064L)
        assertThat(MiseryRules.payDebt(MiseryStep.ZERO, cost)).isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun miserySpacesClampAtZero() {
        assertThat(MiseryRules.shift(MiseryStep.TEN, spaces = -2)).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun chaosCannotBeReversedByLaterReliefEffects() {
        assertThat(MiseryRules.shift(MiseryStep.CHAOS, spaces = -100)).isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun reliefRequiresAtLeastOneCompleteTier() {
        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.buyRelief(
                    buyer().copy(misery = MiseryStep.TEN),
                    spaces = 1,
                    currentTurn = 2,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_MISERY_RELIEF)
    }

    @Test
    fun oneTierAllowsOnlyOneSpacePerTurnAcrossSeparatePurchases() {
        val player =
            PlayerEconomy.buyRelief(oneTier().copy(misery = MiseryStep.TWENTY), 1, 2).player

        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.buyRelief(player, spaces = 1, currentTurn = 2)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_MISERY_RELIEF)
    }

    @Test
    fun theFollowingTurnHasANewReliefAllowance() {
        val player =
            PlayerEconomy.buyRelief(oneTier().copy(misery = MiseryStep.TWENTY), 1, 2).player

        val result = PlayerEconomy.buyRelief(player, spaces = 1, currentTurn = 3)

        assertThat(result.player.misery).isEqualTo(MiseryStep.ZERO)
        assertThat(result.player.miseryReliefUsage).isEqualTo(MiseryReliefUsage(3, 1, 0))
    }

    @Test
    fun newlyCompletedTierAndPooledNewRatingsCanPayReliefImmediately() {
        val player =
            oneTier()
                .copy(
                    advances =
                        PlayerAdvances(
                            oneTier().advances.acquisitions.map { acquisition ->
                                acquisition.copy(turnNumber = 2)
                            }
                        ),
                    misery = MiseryStep.HUNDRED_TWENTY_FIVE,
                )

        val result = PlayerEconomy.buyRelief(player, spaces = 1, currentTurn = 2)

        assertThat(result.player.cash).isEqualTo(player.cash)
        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED)
        assertThat(result.player.miseryReliefUsage?.ratingSpent).isEqualTo(25)
    }

    @Test
    fun enlightenmentRoundsEachSpaceBeforeUsingRating() {
        val player = twoTiers().copy(misery = MiseryStep.HUNDRED_SEVENTY_FIVE)

        val quote = PlayerEconomy.reliefQuote(player, spaces = 2, currentTurn = 2)

        assertThat(quote.steps.map(MiseryReliefStep::priceBeforeRating))
            .containsExactly(13, 13)
            .inOrder()
        assertThat(quote.cashCost).isEqualTo(26)
    }

    @Test
    fun enlightenmentHalvesTheGrossPriceBeforeNewRatingPaysIt() {
        val player =
            twoTiers()
                .copy(
                    advances =
                        twoTiers().advances.acquire(AdvanceId.LAWS_OF_MATTER, turnNumber = 2),
                    misery = MiseryStep.HUNDRED_SEVENTY_FIVE,
                )

        val quote = PlayerEconomy.reliefQuote(player, spaces = 1, currentTurn = 2)

        assertThat(quote.cashCost).isEqualTo(8)
        assertThat(quote.ratingSpent).isEqualTo(5)
    }

    @Test
    fun separateReliefPurchasesCannotReuseTheSameRating() {
        val player =
            twoTiers()
                .copy(
                    advances =
                        twoTiers().advances.acquire(AdvanceId.LAWS_OF_MATTER, turnNumber = 2),
                    misery = MiseryStep.HUNDRED_SEVENTY_FIVE,
                )
        val afterFirst = PlayerEconomy.buyRelief(player, spaces = 1, currentTurn = 2).player

        val quote = PlayerEconomy.reliefQuote(afterFirst, spaces = 1, currentTurn = 2)

        assertThat(quote.cashCost).isEqualTo(13)
        assertThat(quote.ratingSpent).isEqualTo(0)
    }

    @Test
    fun reliefIsNotAllowedBelowZeroEvenWithUnusedTiers() {
        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.buyRelief(oneTier(), spaces = 1, currentTurn = 2)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_MISERY_RELIEF)
    }

    @Test
    fun reliefMustBeAffordableAfterRatingsAreApplied() {
        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.buyRelief(oneTier().copy(cash = 9, misery = MiseryStep.TEN), 1, 2)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_CASH)
    }

    @Test
    fun incomeUsesTheSuppliedBoardCountAndInitialPlayerCount() {
        val player = buyer().copy(cash = 0)

        val result =
            PlayerEconomy.collectIncome(player, initialPlayerCount = 3, boardMarkerCount = 1)

        assertThat(result.player.cash).isEqualTo(18)
    }

    @Test
    fun middleClassAddsTenToIncomeImmediately() {
        val result = PlayerEconomy.collectIncome(buyer(AdvanceId.MIDDLE_CLASS).copy(cash = 0), 6, 1)

        assertThat(result.player.cash).isEqualTo(31)
    }

    @Test
    fun profitIsCappedAtIncomeAndDoesNotDoubleTheIncomeReceipt() {
        val player = buyer(AdvanceId.INTEREST_PROFIT).copy(cash = 108)

        val result =
            PlayerEconomy.collectIncome(player, initialPlayerCount = 5, boardMarkerCount = 17)

        assertThat(result.player.cash).isEqualTo(308)
    }

    @Test
    fun profitCannotExceedTheCashHeldBeforeIncome() {
        val player = buyer(AdvanceId.INTEREST_PROFIT).copy(cash = 8)

        val result =
            PlayerEconomy.collectIncome(player, initialPlayerCount = 5, boardMarkerCount = 1)

        assertThat(result.player.cash).isEqualTo(36)
    }

    @Test
    fun middleClassRaisesTheProfitCeilingAsWellAsIncome() {
        val quote =
            PlayerEconomy.incomeQuote(
                buyer(AdvanceId.INTEREST_PROFIT, AdvanceId.MIDDLE_CLASS),
                5,
                1,
            )

        assertThat(quote.income).isEqualTo(30)
        assertThat(quote.interestProfit).isEqualTo(30)
    }

    @Test
    fun newWorldRelievesOneSpaceDuringIncome() {
        val player = buyer(AdvanceId.NEW_WORLD).copy(misery = MiseryStep.TEN)

        val result = PlayerEconomy.collectIncome(player, 5, 1)

        assertThat(result.player.misery).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun playersInChaosCannotCollectIncome() {
        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.collectIncome(buyer().copy(misery = MiseryStep.CHAOS), 5, 1)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.PLAYER_IN_CHAOS)
    }

    private fun buyer(vararg owned: AdvanceId): PlayerState =
        PlayerState(
            player = Player(PlayerId("p1"), "Buyer"),
            cash = 1_000,
            advances = PlayerAdvances(owned.map { advance -> AdvanceAcquisition(advance, 1) }),
        )

    private fun oneTier(): PlayerState =
        buyer(
            AdvanceId.THE_HEAVENS,
            AdvanceId.PATRONAGE,
            AdvanceId.CARAVAN,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.OVERLAND_EAST,
            AdvanceId.URBAN_ASCENDANCY,
        )

    private fun twoTiers(): PlayerState =
        buyer(
            AdvanceId.THE_HEAVENS,
            AdvanceId.ENLIGHTENMENT,
            AdvanceId.PATRONAGE,
            AdvanceId.HOLY_INDULGENCE,
            AdvanceId.CARAVAN,
            AdvanceId.WIND_WATERMILL,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.PRINTED_WORD,
            AdvanceId.OVERLAND_EAST,
            AdvanceId.SEAWORTHY_VESSELS,
            AdvanceId.URBAN_ASCENDANCY,
            AdvanceId.NATIONALISM,
        )

    private fun hand(size: Int): List<HandCard> =
        CardData.epochOne.take(size).mapIndexed { index, card ->
            HandCard(reference = "test-${index + 1}", definition = card)
        }
}
