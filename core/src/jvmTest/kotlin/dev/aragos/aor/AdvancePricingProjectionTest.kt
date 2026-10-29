package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdvancePricingProjectionTest {
    private val buyer = PlayerId("p1")

    @Test
    fun missingPrerequisitesStillShowAllEligibleDiscounts() {
        val before =
            state(
                    AdvanceAcquisition(AdvanceId.OVERLAND_EAST, 1),
                    AdvanceAcquisition(AdvanceId.WRITTEN_RECORD, 1),
                    AdvanceAcquisition(AdvanceId.INSTITUTIONAL_RESEARCH, 2),
                )
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.PRINCE_HENRY,
                                        buyer,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        )
                )

        val offer =
            before.viewForPlayer(buyer).advanceOffers.single {
                it.advance == AdvanceId.OCEAN_NAVIGATION
            }
        val failure =
            assertFailsWith<RuleViolation> {
                TurnCommand.BuyAdvance(AdvanceId.OCEAN_NAVIGATION.letter).applyTo(before, buyer)
            }

        assertThat(offer.unavailableReason).isEqualTo("MISSING_ADVANCE_PREREQUISITE")
        assertThat(offer.price).isEqualTo(60)
        assertThat(offer.priceBreakdown?.advanceCredits)
            .containsExactly(AdvanceCredit(AdvanceId.OVERLAND_EAST, 20))
        assertThat(offer.priceBreakdown?.institutionalResearchCredit).isEqualTo(10)
        assertThat(offer.priceBreakdown?.leaders)
            .containsExactly(AppliedLeaderDiscount("prince-henry", 20, 10))
        assertThat(before.legalChoices(buyer).commands).doesNotContain("buy-advance T")
        assertThat(failure.code).isEqualTo(ViolationCode.MISSING_ADVANCE_PREREQUISITE)
    }

    @Test
    fun papalDecreeBlocksPurchaseWithoutRemovingItsPrice() {
        val before =
            state(AdvanceAcquisition(AdvanceId.PATRONAGE, 1))
                .copy(effects = CardTurnEffects(papalCategory = AdvanceCategory.RELIGION))

        val offer =
            before.viewForPlayer(buyer).advanceOffers.single {
                it.advance == AdvanceId.HOLY_INDULGENCE
            }
        val failure =
            assertFailsWith<RuleViolation> {
                TurnCommand.BuyAdvance(AdvanceId.HOLY_INDULGENCE.letter).applyTo(before, buyer)
            }

        assertThat(offer.unavailableReason).isEqualTo("ADVANCE_CATEGORY_FORBIDDEN")
        assertThat(offer.price).isEqualTo(40)
        assertThat(offer.priceBreakdown?.advanceCredits)
            .containsExactly(AdvanceCredit(AdvanceId.PATRONAGE, 20))
        assertThat(before.legalChoices(buyer).commands).doesNotContain("buy-advance F")
        assertThat(failure.code).isEqualTo(ViolationCode.ADVANCE_CATEGORY_FORBIDDEN)
    }

    @Test
    fun currentTurnAdvanceCreditsStayExcludedFromBlockedEstimates() {
        val before = state(AdvanceAcquisition(AdvanceId.OVERLAND_EAST, 2))

        val offer =
            before.viewForPlayer(buyer).advanceOffers.single {
                it.advance == AdvanceId.OCEAN_NAVIGATION
            }

        assertThat(offer.unavailableReason).isEqualTo("MISSING_ADVANCE_PREREQUISITE")
        assertThat(offer.price).isEqualTo(120)
        assertThat(offer.priceBreakdown?.advanceCredits).isEmpty()
        assertThat(offer.priceBreakdown?.categoryCredit).isEqualTo(0)
    }

    private fun state(vararg advances: AdvanceAcquisition): GameState {
        val players =
            listOf(
                Player(buyer, "Buyer"),
                Player(PlayerId("p2"), "Second"),
                Player(PlayerId("p3"), "Third"),
            )
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, buyer),
            turnNumber = 2,
            stage = Stage.PURCHASE,
            flow =
                TurnFlow(
                    actor = buyer,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                ),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 1000,
                                advances =
                                    if (player.id == buyer) {
                                        PlayerAdvances(advances.toList())
                                    } else {
                                        PlayerAdvances()
                                    },
                            )
                    }
                    .toMap(),
        )
    }
}
