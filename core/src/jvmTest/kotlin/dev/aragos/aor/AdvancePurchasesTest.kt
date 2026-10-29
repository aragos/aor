package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdvancePurchasesTest {
    private val turnTwo = PurchaseContext(currentTurn = 2)

    @Test
    fun everyAdvanceCanBePurchasedWhenItsPrerequisitesAreOwned() {
        for (advance in AdvanceId.entries) {
            val player =
                buyer(*AdvanceId.entries.filter { owned -> owned != advance }.toTypedArray())

            val result = AdvancePurchases.purchase(player, advance, turnTwo)

            assertThat(result.player.advances.owned).containsExactlyElementsIn(AdvanceId.entries)
            assertThat(result.player.advances.acquisitions.last())
                .isEqualTo(AdvanceAcquisition(advance, turnNumber = 2))
            assertThat(result.player.advances.score).isEqualTo(2100)
        }
    }

    @Test
    fun advanceHistoryRejectsDuplicateOwnership() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PlayerAdvances(
                    listOf(
                        AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1),
                        AdvanceAcquisition(AdvanceId.THE_HEAVENS, 2),
                    )
                )
            }

        assertThat(failure).hasMessageThat().contains("only once")
    }

    @Test
    fun acquisitionTurnsMustBePositive() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AdvanceAcquisition(AdvanceId.THE_HEAVENS, turnNumber = 0)
            }

        assertThat(failure).hasMessageThat().contains("positive turn")
    }

    @Test
    fun categoryCreditStartsInTheFollowingTurn() {
        val player =
            AdvancePurchases.purchase(
                    buyer(),
                    AdvanceId.THE_HEAVENS,
                    PurchaseContext(currentTurn = 1),
                )
                .player

        val quote = AdvancePurchases.quote(player, AdvanceId.HUMAN_BODY, turnTwo)

        assertThat(quote.categoryCredit).isEqualTo(20)
        assertThat(quote.price).isEqualTo(40)
    }

    @Test
    fun namedAdvanceCreditsAndOtherDiscountsMatchTheChargedPrice() {
        val player =
            buyer(
                AdvanceId.THE_HEAVENS,
                AdvanceId.HUMAN_BODY,
                AdvanceId.INSTITUTIONAL_RESEARCH,
                AdvanceId.WRITTEN_RECORD,
            )
        val context =
            turnTwo.copy(
                leaderDiscounts =
                    listOf(
                        AuthorizedLeaderDiscount(
                            "henry-oldenburg",
                            AdvanceId.ENLIGHTENMENT,
                            30,
                        )
                    )
            )

        val quote = AdvancePurchases.quote(player, AdvanceId.ENLIGHTENMENT, context)
        val purchased = AdvancePurchases.purchase(player, AdvanceId.ENLIGHTENMENT, context)

        assertThat(quote.advanceCredits)
            .containsExactly(
                AdvanceCredit(AdvanceId.THE_HEAVENS, 20),
                AdvanceCredit(AdvanceId.HUMAN_BODY, 20),
            )
        assertThat(quote.categoryCredit).isEqualTo(40)
        assertThat(quote.institutionalResearchCredit).isEqualTo(10)
        assertThat(quote.leaders).containsExactly(AppliedLeaderDiscount("henry-oldenburg", 30, 10))
        assertThat(quote.price).isEqualTo(30)
        assertThat(quote.effectiveDiscount).isEqualTo(90)
        assertThat(player.cash - purchased.player.cash).isEqualTo(quote.price)
    }

    @Test
    fun newPrerequisitesApplyImmediatelyWithoutAddingCategoryCredit() {
        val player = AdvancePurchases.purchase(buyer(), AdvanceId.CARAVAN, turnTwo).player

        val quote = AdvancePurchases.quote(player, AdvanceId.WIND_WATERMILL, turnTwo)

        assertThat(quote.categoryCredit).isEqualTo(0)
        assertThat(quote.price).isEqualTo(40)
    }

    @Test
    fun newInstitutionalResearchDiscountsTheNextPurchaseImmediately() {
        val player =
            AdvancePurchases.purchase(buyer(), AdvanceId.INSTITUTIONAL_RESEARCH, turnTwo).player

        val quote = AdvancePurchases.quote(player, AdvanceId.THE_HEAVENS, turnTwo)

        assertThat(quote.institutionalResearchCredit).isEqualTo(10)
        assertThat(quote.price).isEqualTo(20)
    }

    @Test
    fun institutionalResearchDoesNotDiscountReligion() {
        val quote =
            AdvancePurchases.quote(
                buyer(AdvanceId.INSTITUTIONAL_RESEARCH),
                AdvanceId.PATRONAGE,
                turnTwo,
            )

        assertThat(quote.institutionalResearchCredit).isEqualTo(0)
        assertThat(quote.price).isEqualTo(30)
    }

    @Test
    fun institutionalResearchGivesOnlyItsCategoryCreditForCivics() {
        val quote =
            AdvancePurchases.quote(
                buyer(AdvanceId.INSTITUTIONAL_RESEARCH),
                AdvanceId.NATIONALISM,
                turnTwo,
            )

        assertThat(quote.institutionalResearchCredit).isEqualTo(0)
        assertThat(quote.categoryCredit).isEqualTo(30)
        assertThat(quote.price).isEqualTo(30)
    }

    @Test
    fun newlyBoughtWrittenRecordEnhancesEachUsableLeader() {
        val player = AdvancePurchases.purchase(buyer(), AdvanceId.WRITTEN_RECORD, turnTwo).player
        val context =
            turnTwo.copy(
                leaderDiscounts =
                    listOf(
                        AuthorizedLeaderDiscount("charlemagne", AdvanceId.NATIONALISM, 20),
                        AuthorizedLeaderDiscount("another-leader", AdvanceId.NATIONALISM, 10),
                    )
            )

        val quote = AdvancePurchases.quote(player, AdvanceId.NATIONALISM, context)

        assertThat(quote.leaders.map(AppliedLeaderDiscount::writtenRecordBonus))
            .containsExactly(10, 10)
        assertThat(quote.price).isEqualTo(10)
    }

    @Test
    fun excessLeaderDiscountMakesThePurchaseFreeWithoutReturningCash() {
        val player = buyer().copy(cash = 7)
        val context =
            turnTwo.copy(
                leaderDiscounts =
                    listOf(AuthorizedLeaderDiscount("charlemagne", AdvanceId.NATIONALISM, 100))
            )

        val result = AdvancePurchases.purchase(player, AdvanceId.NATIONALISM, context)

        assertThat(result.player.cash).isEqualTo(7)
    }

    @Test
    fun effectiveDiscountExcludesCreditsBeyondTheZeroPriceFloor() {
        val context =
            turnTwo.copy(
                leaderDiscounts =
                    listOf(AuthorizedLeaderDiscount("charlemagne", AdvanceId.NATIONALISM, 100))
            )

        val quote = AdvancePurchases.quote(buyer(), AdvanceId.NATIONALISM, context)

        assertThat(quote.price).isEqualTo(0)
        assertThat(quote.effectiveDiscount).isEqualTo(quote.baseCost)
        assertThat(quote.effectiveDiscount).isEqualTo(60)
    }

    @Test
    fun missingCrossCategoryPrerequisitesIdentifyEveryMissingAdvance() {
        val failure =
            assertFailsWith<RuleViolation> {
                AdvancePurchases.purchase(buyer(), AdvanceId.OCEAN_NAVIGATION, turnTwo)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.MISSING_ADVANCE_PREREQUISITE)
        assertThat(failure.entityIds).containsExactly("A", "S")
    }

    @Test
    fun papalDecreeRejectsTheForbiddenCategory() {
        val failure =
            assertFailsWith<RuleViolation> {
                AdvancePurchases.purchase(
                    buyer(),
                    AdvanceId.PATRONAGE,
                    turnTwo.copy(papalDecree = AdvanceCategory.RELIGION),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.ADVANCE_CATEGORY_FORBIDDEN)
    }

    @Test
    fun alreadyOwnedAdvancesCannotBePurchasedAgain() {
        val failure =
            assertFailsWith<RuleViolation> {
                AdvancePurchases.purchase(
                    buyer(AdvanceId.THE_HEAVENS),
                    AdvanceId.THE_HEAVENS,
                    turnTwo,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.ADVANCE_ALREADY_OWNED)
    }

    @Test
    fun unaffordableAdvanceRejectsWithoutChangingTheBuyer() {
        val player = buyer().copy(cash = 29)

        val failure =
            assertFailsWith<RuleViolation> {
                AdvancePurchases.purchase(player, AdvanceId.THE_HEAVENS, turnTwo)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_CASH)
        assertThat(player.advances.owned).isEmpty()
        assertThat(player.cash).isEqualTo(29)
    }

    @Test
    fun religionPurchaseCanEnterChaos() {
        val player = buyer().copy(misery = MiseryStep.THOUSAND)

        val result = AdvancePurchases.purchase(player, AdvanceId.PATRONAGE, turnTwo)

        assertThat(result.status).isEqualTo(EconomyStatus.ENTERED_CHAOS)
        assertThat(result.player.misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(result.player.advances.owns(AdvanceId.PATRONAGE)).isTrue()
    }

    @Test
    fun humanBodyRelievesMiseryImmediately() {
        val result =
            AdvancePurchases.purchase(
                buyer().copy(misery = MiseryStep.TEN),
                AdvanceId.HUMAN_BODY,
                turnTwo,
            )

        assertThat(result.player.misery).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun seaworthyVesselsResetsTheFleetAfterAPaidUpgrade() {
        val player = buyer().copy(ships = ShippingCapability.GALLEY_8, lastShipUpgradeTurn = 2)

        val result = AdvancePurchases.purchase(player, AdvanceId.SEAWORTHY_VESSELS, turnTwo)

        assertThat(result.player.ships).isEqualTo(ShippingCapability.SEAWORTHY_10)
        assertThat(result.player.lastShipUpgradeTurn).isEqualTo(2)
    }

    @Test
    fun oceanNavigationStartsAtOneRegardlessOfSeaworthyUpgradeLevel() {
        val player =
            buyer(AdvanceId.THE_HEAVENS, AdvanceId.SEAWORTHY_VESSELS)
                .copy(ships = ShippingCapability.SEAWORTHY_16)

        val result = AdvancePurchases.purchase(player, AdvanceId.OCEAN_NAVIGATION, turnTwo)

        assertThat(result.player.ships).isEqualTo(ShippingCapability.OCEAN_1)
    }

    @Test
    fun noShipsUpgradeToGalleyTwoForTenDollars() {
        val player = buyer().copy(cash = 10)

        val result = ShippingPurchases.upgrade(player, currentTurn = 2)

        assertThat(result.player.ships).isEqualTo(ShippingCapability.GALLEY_2)
        assertThat(result.player.cash).isEqualTo(0)
        assertThat(result.player.lastShipUpgradeTurn).isEqualTo(2)
    }

    @Test
    fun paidUpgradesCannotCrossFleetTypes() {
        for (fleet in
            listOf(
                ShippingCapability.GALLEY_8,
                ShippingCapability.SEAWORTHY_16,
                ShippingCapability.OCEAN_4,
            )) {
            val failure =
                assertFailsWith<RuleViolation> {
                    ShippingPurchases.upgrade(buyer().copy(ships = fleet), currentTurn = 2)
                }

            assertThat(failure.code).isEqualTo(ViolationCode.SHIP_UPGRADE_UNAVAILABLE)
        }
    }

    @Test
    fun aSecondPaidShippingUpgradeInTheSameTurnIsRejected() {
        val player = ShippingPurchases.upgrade(buyer(), currentTurn = 2).player

        val failure =
            assertFailsWith<RuleViolation> { ShippingPurchases.upgrade(player, currentTurn = 2) }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIP_UPGRADE_ALREADY_BOUGHT)
    }

    @Test
    fun aNewTurnAllowsTheNextPaidShippingUpgrade() {
        val player = ShippingPurchases.upgrade(buyer(), currentTurn = 1).player

        val result = ShippingPurchases.upgrade(player, currentTurn = 2)

        assertThat(result.player.ships).isEqualTo(ShippingCapability.GALLEY_4)
    }

    @Test
    fun playersInChaosCannotPurchaseAdvances() {
        val failure =
            assertFailsWith<RuleViolation> {
                AdvancePurchases.purchase(
                    buyer().copy(misery = MiseryStep.CHAOS),
                    AdvanceId.THE_HEAVENS,
                    turnTwo,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.PLAYER_IN_CHAOS)
    }

    private fun buyer(vararg owned: AdvanceId): PlayerState =
        PlayerState(
            player = Player(PlayerId("p1"), "Buyer"),
            cash = 10_000,
            advances =
                PlayerAdvances(
                    owned.map { advance -> AdvanceAcquisition(advance, turnNumber = 1) }
                ),
        )
}
