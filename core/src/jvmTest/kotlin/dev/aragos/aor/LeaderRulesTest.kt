package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LeaderRulesTest {
    @Test
    fun theFirstLeaderHasAnExplicitProtectedDisplayRole() {
        val result =
            CardRules.resolve(
                cardContext("charlemagne"),
                cardPlayerOne,
                CardPlay("charlemagne"),
            ) as CardResolution.LeaderPlay

        assertThat(result.leader.role).isEqualTo(CardLeaderRole.DISPLAY)
    }

    @Test
    fun laterLeaderCanHoldProtectionCash() {
        val context = exposedLeaderContext("rashid-ad-din")

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay("rashid-ad-din", CardChoice.LeaderProtection(cash = 7)),
            ) as CardResolution.LeaderPlay

        assertThat(result.leader.role).isEqualTo(CardLeaderRole.EXPOSED)
        assertThat(result.leader.protectionCash).isEqualTo(7)
    }

    @Test
    fun protectionMoneyMustBeAffordable() {
        val context = exposedLeaderContext("rashid-ad-din")

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    CardPlay("rashid-ad-din", CardChoice.LeaderProtection(cash = 41)),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INSUFFICIENT_CASH)
    }

    @Test
    fun printedWordRebatesOnlyPrintedCreditsForAlreadyOwnedAdvances() {
        val context =
            cardContext("st-benedict")
                .withAdvances(
                    cardPlayerOne,
                    AdvanceId.PRINTED_WORD,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.PATRONAGE,
                )

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay("st-benedict"),
            ) as CardResolution.LeaderPlay

        assertThat(result.rebate).isEqualTo(20)
    }

    @Test
    fun aLeaderRebateDoesNotRequirePurchaseOfItsOtherAdvance() {
        val context =
            cardContext("rashid-ad-din")
                .withAdvances(cardPlayerOne, AdvanceId.PRINTED_WORD, AdvanceId.WRITTEN_RECORD)

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay("rashid-ad-din"),
            ) as CardResolution.LeaderPlay

        assertThat(result.rebate).isEqualTo(10)
    }

    @Test
    fun patronagePaysTheLeaderOwnerAndRecordsAClaim() {
        val context = claimContext().withAdvances(cardPlayerOne, AdvanceId.PATRONAGE)

        val result = LeaderRules.claim(context, cardPlayerOne, LeaderId.RASHID_AD_DIN)

        assertThat(result.cash).isEqualTo(7)
        assertThat(result.recipient).isEqualTo(cardPlayerTwo)
        assertThat(result.effects.leaders.last().patronageClaimants).containsExactly(cardPlayerOne)
    }

    @Test
    fun aClaimAllowsBothOfTheLeadersCredits() {
        val context = claimContext().withAdvances(cardPlayerOne, AdvanceId.PATRONAGE)
        val claimed = LeaderRules.claim(context, cardPlayerOne, LeaderId.RASHID_AD_DIN)
        val afterClaim = context.copy(effects = claimed.effects)

        assertThat(LeaderRules.discount(afterClaim, cardPlayerOne, AdvanceId.WRITTEN_RECORD))
            .isEqualTo(10)
        assertThat(LeaderRules.discount(afterClaim, cardPlayerOne, AdvanceId.OVERLAND_EAST))
            .isEqualTo(10)
    }

    @Test
    fun writtenRecordEnhancesClaimedCreditsWithoutCreatingRebates() {
        val context =
            claimContext()
                .withAdvances(
                    cardPlayerOne,
                    AdvanceId.PATRONAGE,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.PRINTED_WORD,
                )
        val claimed = LeaderRules.claim(context, cardPlayerOne, LeaderId.RASHID_AD_DIN)

        val discount =
            LeaderRules.discount(
                context.copy(effects = claimed.effects),
                cardPlayerOne,
                AdvanceId.OVERLAND_EAST,
            )

        assertThat(discount).isEqualTo(20)
        assertThat(claimed.cash).isEqualTo(7)
    }

    @Test
    fun theFirstLeaderCannotBeClaimed() {
        val context = claimContext().withAdvances(cardPlayerOne, AdvanceId.PATRONAGE)

        val failure =
            assertFailsWith<CardRuleViolation> {
                LeaderRules.claim(context, cardPlayerOne, LeaderId.CHARLEMAGNE)
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_LEADER_CLAIM)
    }

    @Test
    fun patronageCannotClaimTheSameLeaderTwice() {
        val context = claimContext().withAdvances(cardPlayerOne, AdvanceId.PATRONAGE)
        val claimed = LeaderRules.claim(context, cardPlayerOne, LeaderId.RASHID_AD_DIN)

        val failure =
            assertFailsWith<CardRuleViolation> {
                LeaderRules.claim(
                    context.copy(effects = claimed.effects),
                    cardPlayerOne,
                    LeaderId.RASHID_AD_DIN,
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_LEADER_CLAIM)
    }

    @Test
    fun discountsFromSeveralEligibleLeadersStack() {
        val context =
            cardContext("stone-1")
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.DIONYSUS_EXIGUUS,
                                        cardPlayerOne,
                                        CardLeaderRole.DISPLAY,
                                    ),
                                    CardLeaderInPlay(
                                        LeaderId.ST_BENEDICT,
                                        cardPlayerOne,
                                        CardLeaderRole.EXPOSED,
                                    ),
                                )
                        )
                )

        val discount = LeaderRules.discount(context, cardPlayerOne, AdvanceId.WRITTEN_RECORD)

        assertThat(discount).isEqualTo(30)
    }

    @Test
    fun crusadesEnhancesWalterForLaterPurchases() {
        val context =
            cardContext("stone-1")
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.WALTER_THE_PENNILESS,
                                        cardPlayerOne,
                                        CardLeaderRole.DISPLAY,
                                    )
                                ),
                            crusadesPlayed = true,
                        )
                )

        assertThat(LeaderRules.discount(context, cardPlayerOne, AdvanceId.OVERLAND_EAST))
            .isEqualTo(30)
    }

    @Test
    fun purchaseCleanupForfeitsOnlyTheOwnersEscrow() {
        assertThat(LeaderRules.protectionToBank(claimContext().effects)).isEqualTo(7)
    }

    private fun exposedLeaderContext(card: String): CardContext =
        cardContext(card)
            .copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.CHARLEMAGNE,
                                    cardPlayerTwo,
                                    CardLeaderRole.DISPLAY,
                                )
                            )
                    )
            )

    private fun claimContext(): CardContext =
        cardContext("stone-1")
            .copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.CHARLEMAGNE,
                                    cardPlayerThree,
                                    CardLeaderRole.DISPLAY,
                                ),
                                CardLeaderInPlay(
                                    LeaderId.RASHID_AD_DIN,
                                    cardPlayerTwo,
                                    CardLeaderRole.EXPOSED,
                                    protectionCash = 7,
                                ),
                            )
                    )
            )
}
