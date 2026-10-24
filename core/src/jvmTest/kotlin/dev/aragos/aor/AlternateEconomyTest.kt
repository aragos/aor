package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AlternateEconomyTest {
    @Test
    fun commerceQuoteUsesSelectedPriceAndPrerequisites() {
        val rules = RuleOptions(commerce = CommerceRule.SECOND_EDITION)
        val player = buyer(AdvanceId.CARAVAN)

        val quote =
            AdvancePurchases.quote(
                player,
                AdvanceId.IMPROVED_AGRICULTURE,
                PurchaseContext(currentTurn = 2, rules = rules),
            )

        assertThat(quote.baseCost).isEqualTo(40)
        assertThat(quote.price).isEqualTo(30)
    }

    @Test
    fun selectedCommercePricesDetermineScore() {
        val player = buyer(AdvanceId.IMPROVED_AGRICULTURE)

        assertThat(player.advances.score(RuleOptions(commerce = CommerceRule.SECOND_EDITION)))
            .isEqualTo(40)
        assertThat(player.advances.score).isEqualTo(50)
    }

    @Test
    fun secondEditionCreditsWaitUntilFollowingTurn() {
        val player =
            buyer()
                .copy(
                    advances =
                        PlayerAdvances(
                            listOf(
                                AdvanceAcquisition(AdvanceId.WRITTEN_RECORD, 1),
                                AdvanceAcquisition(AdvanceId.PRINTED_WORD, 2),
                            )
                        )
                )

        val quote =
            AdvancePurchases.quote(
                player,
                AdvanceId.MASTER_ART,
                PurchaseContext(
                    currentTurn = 2,
                    rules = RuleOptions(credits = CreditRule.SECOND_EDITION),
                ),
            )

        assertThat(quote.categoryCredit).isEqualTo(30)
        assertThat(quote.price).isEqualTo(60)
    }

    @Test
    fun takeoverWrittenRecordDoesNotIncreaseLeaderCredit() {
        val player = buyer(AdvanceId.WRITTEN_RECORD)

        val quote =
            AdvancePurchases.quote(
                player,
                AdvanceId.THE_HEAVENS,
                PurchaseContext(
                    currentTurn = 2,
                    leaderDiscounts =
                        listOf(
                            AuthorizedLeaderDiscount("galileo-galilei", AdvanceId.THE_HEAVENS, 20)
                        ),
                    rules = RuleOptions(writtenRecord = WrittenRecordRule.TAKEOVER_TRADE),
                ),
            )

        assertThat(quote.leaders.single().writtenRecordBonus).isEqualTo(0)
        assertThat(quote.price).isEqualTo(10)
    }

    @Test
    fun completingSecondThresholdEarnsOneSpaceImmediately() {
        val player =
            buyer(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.HUMAN_BODY,
                    AdvanceId.CARAVAN,
                    AdvanceId.IMPROVED_AGRICULTURE,
                    AdvanceId.WRITTEN_RECORD,
                )
                .copy(misery = MiseryStep.HUNDRED)

        val result =
            AdvancePurchases.purchase(
                player,
                AdvanceId.PRINTED_WORD,
                PurchaseContext(
                    currentTurn = 2,
                    rules = RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
                ),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.NINETY)
    }

    @Test
    fun completingThirdThresholdEarnsTwoFurtherSpaces() {
        val player =
            buyer(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.HUMAN_BODY,
                    AdvanceId.LAWS_OF_MATTER,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                    AdvanceId.IMPROVED_AGRICULTURE,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.PRINTED_WORD,
                )
                .copy(misery = MiseryStep.HUNDRED)

        val result =
            AdvancePurchases.purchase(
                player,
                AdvanceId.MASTER_ART,
                PurchaseContext(
                    currentTurn = 2,
                    rules = RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
                ),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.EIGHTY)
    }

    @Test
    fun completedThresholdCannotEarnReliefAgain() {
        val player =
            buyer(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.HUMAN_BODY,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.PRINTED_WORD,
                )
                .copy(misery = MiseryStep.HUNDRED)

        val result =
            AdvancePurchases.purchase(
                player,
                AdvanceId.LAWS_OF_MATTER,
                PurchaseContext(
                    currentTurn = 2,
                    rules = RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
                ),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED)
    }

    @Test
    fun finalThresholdUsesFourCommerceAdvancesAndEarnsThreeFurtherSpaces() {
        val player =
            buyer(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.HUMAN_BODY,
                    AdvanceId.LAWS_OF_MATTER,
                    AdvanceId.ENLIGHTENMENT,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                    AdvanceId.IMPROVED_AGRICULTURE,
                    AdvanceId.INTEREST_PROFIT,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.PRINTED_WORD,
                    AdvanceId.MASTER_ART,
                )
                .copy(misery = MiseryStep.HUNDRED)

        val result =
            AdvancePurchases.purchase(
                player,
                AdvanceId.RENAISSANCE,
                PurchaseContext(
                    currentTurn = 2,
                    rules = RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
                ),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.SEVENTY)
    }

    @Test
    fun reliefEarnedAtZeroIsConsumedRatherThanDeferred() {
        val initial =
            buyer(
                AdvanceId.THE_HEAVENS,
                AdvanceId.HUMAN_BODY,
                AdvanceId.CARAVAN,
                AdvanceId.WIND_WATERMILL,
                AdvanceId.WRITTEN_RECORD,
            )
        val context =
            PurchaseContext(
                currentTurn = 2,
                rules = RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
            )
        val alreadyEarned =
            AdvancePurchases.purchase(initial, AdvanceId.PRINTED_WORD, context)
                .player
                .copy(misery = MiseryStep.HUNDRED)

        val result = AdvancePurchases.purchase(alreadyEarned, AdvanceId.LAWS_OF_MATTER, context)

        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED)
    }

    @Test
    fun categoryCompletionDisablesCashRelief() {
        val failure =
            assertFailsWith<RuleViolation> {
                PlayerEconomy.buyRelief(
                    buyer(),
                    1,
                    2,
                    RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_MISERY_RELIEF)
    }

    @Test
    fun alternateIncomeReliefBelongsToEnlightenment() {
        val result =
            PlayerEconomy.collectIncome(
                buyer(AdvanceId.ENLIGHTENMENT).copy(misery = MiseryStep.HUNDRED),
                3,
                1,
                RuleOptions(incomeRelief = IncomeReliefRule.ENLIGHTENMENT),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.NINETY)
    }

    @Test
    fun newWorldDoesNotAlsoAwardAlternateIncomeRelief() {
        val result =
            PlayerEconomy.collectIncome(
                buyer(AdvanceId.NEW_WORLD).copy(misery = MiseryStep.HUNDRED),
                3,
                1,
                RuleOptions(incomeRelief = IncomeReliefRule.ENLIGHTENMENT),
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.HUNDRED)
    }

    private fun buyer(vararg advances: AdvanceId): PlayerState =
        PlayerState(
            player = Player(PlayerId("p1"), "Buyer"),
            cash = 10000,
            advances = PlayerAdvances(advances.map { advance -> AdvanceAcquisition(advance, 1) }),
        )
}
