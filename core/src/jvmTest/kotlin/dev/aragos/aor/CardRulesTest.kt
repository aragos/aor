package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardRulesTest {
    @Test
    fun commodityPayoutUsesAllDominatedProvinces() {
        val context =
            cardContext("cloth-wine")
                .withMarkers(
                    "genoa" to cardPlayerOne,
                    "venice" to cardPlayerOne,
                    "florence" to cardPlayerTwo,
                )

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay("cloth-wine", CardChoice.CommodityChoice(Commodity.CLOTH)),
            ) as CardResolution.CommodityPayment

        assertThat(result.payments)
            .containsExactly(cardPlayerOne, 20, cardPlayerTwo, 5, cardPlayerThree, 0)
    }

    @Test
    fun dualCommodityRequiresOneOfItsPrintedChoices() {
        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    cardContext("ivory-gold"),
                    cardPlayerOne,
                    CardPlay("ivory-gold", CardChoice.CommodityChoice(Commodity.STONE)),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_CHOICE)
    }

    @Test
    fun aSingleCommodityNeedsNoAdditionalChoice() {
        val context = cardContext("silk").withMarkers("salonika" to cardPlayerOne)

        val result =
            CardRules.resolve(context, cardPlayerOne, CardPlay("silk"))
                as CardResolution.CommodityPayment

        assertThat(result.payments[cardPlayerOne]).isEqualTo(8)
    }

    @Test
    fun surplusAndIndustryCancelForAnOwnerWithOneProvince() {
        val context =
            cardContext("silk")
                .withMarkers("salonika" to cardPlayerOne)
                .withAdvances(cardPlayerOne, AdvanceId.INDUSTRY)
                .copy(adjustments = mapOf(Commodity.SILK to listOf(CommodityAdjustment.SURPLUS)))

        val result = CardRules.commodityPayment(context, Commodity.SILK)

        assertThat(result.payments[cardPlayerOne]).isEqualTo(8)
    }

    @Test
    fun surplusWithoutIndustryPaysNothingForOneProvince() {
        val context =
            cardContext("silk")
                .withMarkers("salonika" to cardPlayerOne)
                .copy(adjustments = mapOf(Commodity.SILK to listOf(CommodityAdjustment.SURPLUS)))

        val result = CardRules.commodityPayment(context, Commodity.SILK)

        assertThat(result.payments[cardPlayerOne]).isEqualTo(0)
    }

    @Test
    fun industryDoesNotPayAPlayerWithoutTheCommodity() {
        val context = cardContext("silk").withAdvances(cardPlayerOne, AdvanceId.INDUSTRY)

        val result = CardRules.commodityPayment(context, Commodity.SILK)

        assertThat(result.payments[cardPlayerOne]).isEqualTo(0)
    }

    @Test
    fun doubleShortageAdjustsOnePayoutByOneBox() {
        val context =
            cardContext("silk")
                .withMarkers("salonika" to cardPlayerOne)
                .copy(
                    adjustments =
                        mapOf(
                            Commodity.SILK to
                                listOf(
                                    CommodityAdjustment.SHORTAGE,
                                    CommodityAdjustment.SHORTAGE,
                                )
                        )
                )

        val result = CardRules.commodityPayment(context, Commodity.SILK)

        assertThat(result.payments[cardPlayerOne]).isEqualTo(32)
        assertThat(result.consumedAdjustment).isEqualTo(CommodityAdjustment.SHORTAGE)
        assertThat(context.adjustments.getValue(Commodity.SILK)).hasSize(2)
    }

    @Test
    fun overseasBoxCountsOnceForEachCommodityItProduces() {
        val context = cardContext("ivory-gold").withMarkers("india" to cardPlayerOne)

        val result = CardRules.commodityPayment(context, Commodity.GOLD)

        assertThat(result.payments[cardPlayerOne]).isEqualTo(10)
    }

    @Test
    fun alchemyRoundsWrittenCashUpButCannotTakeMoreThanCurrentCash() {
        val context =
            cardContext("alchemists-gold").withPlayer(cardPlayerTwo) { player ->
                player.copy(cash = 8, writtenCash = 23)
            }

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.ALCHEMISTS_GOLD, CardChoice.PlayerChoice(cardPlayerTwo)),
            )

        assertThat(result).isEqualTo(CardResolution.AlchemistLoss(cardPlayerTwo, cash = 8))
    }

    @Test
    fun lawsOfMatterProtectsTheAlchemyTarget() {
        val context =
            cardContext("alchemists-gold").withAdvances(cardPlayerTwo, AdvanceId.LAWS_OF_MATTER)

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(EventId.ALCHEMISTS_GOLD, CardChoice.PlayerChoice(cardPlayerTwo)),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.IMMUNE_TARGET)
    }

    @Test
    fun universalLawsOfMatterMakesAlchemyABurden() {
        val context =
            cardContext("alchemists-gold")
                .withAdvances(cardPlayerOne, AdvanceId.LAWS_OF_MATTER)
                .withAdvances(cardPlayerTwo, AdvanceId.LAWS_OF_MATTER)
                .withAdvances(cardPlayerThree, AdvanceId.LAWS_OF_MATTER)

        assertThat(CardRules.isBurden(context, "alchemists-gold")).isTrue()
    }

    @Test
    fun temporaryUniversalProtectionDoesNotMakeAlchemyAPermanentBurden() {
        val context =
            cardContext("alchemists-gold")
                .withAdvances(cardPlayerTwo, AdvanceId.LAWS_OF_MATTER)
                .withAdvances(cardPlayerThree, AdvanceId.LAWS_OF_MATTER)
                .copy(effects = CardTurnEffects(enlightenedRulers = setOf(cardPlayerOne)))

        assertThat(CardRules.isBurden(context, "alchemists-gold")).isFalse()
    }

    @Test
    fun civilWarVictimGetsBothRoundedLossChoices() {
        val context =
            cardContext("civil-war").withPlayer(cardPlayerTwo) { player ->
                player.copy(writtenCash = 21, poolTokens = 9)
            }

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.CIVIL_WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
            ) as CardResolution.CivilWar

        assertThat(result.losses).isEqualTo(CivilWarLosses(cash = 11, expansionTokens = 5))
    }

    @Test
    fun civilWarReducesWhateverColouredMarkerOccupiesTheVictimsCapital() {
        val context = cardContext("civil-war").withMarkers("genoa" to cardPlayerThree)

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.CIVIL_WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
            ) as CardResolution.CivilWar

        assertThat(result.capitalReduction)
            .isEqualTo(
                CardResolution.MarkerReduction(
                    EventId.CIVIL_WAR,
                    ProvinceId("genoa"),
                    cardPlayerThree,
                    EventTokenReplacement.STOCK,
                )
            )
    }

    @Test
    fun civilWarTurnPenaltyWaitsForExpansion() {
        val result =
            CardRules.resolve(
                cardContext("civil-war"),
                cardPlayerOne,
                cardEvent(EventId.CIVIL_WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
            ) as CardResolution.CivilWar

        assertThat(result.moveLastAtExpansion).isTrue()
    }

    @Test
    fun finalCivilWarLosesNeitherCashNorTokens() {
        val context = cardContext("civil-war").copy(finalCardPlay = true)

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.CIVIL_WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
            ) as CardResolution.CivilWar

        assertThat(result.losses).isEqualTo(CivilWarLosses(cash = 0, expansionTokens = 0))
        assertThat(result.moveLastAtExpansion).isFalse()
    }

    @Test
    fun enlightenedRulerProtectsItsPlayerInFinalCardPlay() {
        val context =
            cardContext("civil-war")
                .copy(
                    finalCardPlay = true,
                    effects = CardTurnEffects(enlightenedRulers = setOf(cardPlayerTwo)),
                )

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(EventId.CIVIL_WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.IMMUNE_TARGET)
    }

    @Test
    fun famineReductionsCannotBecomeMiseryRelief() {
        val context =
            cardContext("famine")
                .withAdvances(cardPlayerOne, AdvanceId.IMPROVED_AGRICULTURE)
                .withMarkers(
                    "kiev" to cardPlayerOne,
                    "seville" to cardPlayerOne,
                    "sicily" to cardPlayerOne,
                    "belgrade" to cardPlayerOne,
                )

        val result =
            CardRules.resolve(context, cardPlayerOne, cardEvent(EventId.FAMINE))
                as CardResolution.MiseryEvent

        assertThat(result.spaces[cardPlayerOne]).isEqualTo(0)
    }

    @Test
    fun enlightenedRulerDoesNotProtectAgainstFamine() {
        val context =
            cardContext("famine")
                .copy(effects = CardTurnEffects(enlightenedRulers = setOf(cardPlayerOne)))

        val result =
            CardRules.resolve(context, cardPlayerOne, cardEvent(EventId.FAMINE))
                as CardResolution.MiseryEvent

        assertThat(result.spaces[cardPlayerOne]).isEqualTo(4)
    }

    @Test
    fun mysticismSubtractsThePlayersScienceAdvances() {
        val context =
            cardContext("mysticism-abounds")
                .withAdvances(cardPlayerOne, AdvanceId.HUMAN_BODY, AdvanceId.LAWS_OF_MATTER)

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.MYSTICISM_ABOUNDS),
            ) as CardResolution.MiseryEvent

        assertThat(result.spaces[cardPlayerOne]).isEqualTo(2)
    }

    @Test
    fun universalFourSciencesMakesMysticismABurden() {
        val sciences =
            arrayOf(
                AdvanceId.THE_HEAVENS,
                AdvanceId.HUMAN_BODY,
                AdvanceId.LAWS_OF_MATTER,
                AdvanceId.ENLIGHTENMENT,
            )
        val context =
            cardContext("mysticism-abounds")
                .withAdvances(cardPlayerOne, *sciences)
                .withAdvances(cardPlayerTwo, *sciences)
                .withAdvances(cardPlayerThree, *sciences)

        assertThat(CardRules.isBurden(context, "mysticism-abounds")).isTrue()
    }

    @Test
    fun revolutionaryUprisingsCountsCommerceAndRespectsProtection() {
        val context =
            cardContext("revolutionary-uprisings")
                .withAdvances(
                    cardPlayerOne,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                    AdvanceId.INDUSTRY,
                )
                .withAdvances(cardPlayerTwo, AdvanceId.CARAVAN)
                .copy(effects = CardTurnEffects(enlightenedRulers = setOf(cardPlayerTwo)))

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.REVOLUTIONARY_UPRISINGS),
            ) as CardResolution.MiseryEvent

        assertThat(result.spaces)
            .containsExactly(cardPlayerOne, 3, cardPlayerTwo, 0, cardPlayerThree, 0)
    }

    @Test
    fun papalDecreeCannotForbidCommerce() {
        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    cardContext("papal-decree"),
                    cardPlayerOne,
                    cardEvent(
                        EventId.PAPAL_DECREE,
                        CardChoice.CategoryChoice(AdvanceCategory.COMMERCE),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_CHOICE)
    }

    @Test
    fun papalDecreeRecordsItsPermittedCategory() {
        val result =
            CardRules.resolve(
                cardContext("papal-decree"),
                cardPlayerOne,
                cardEvent(
                    EventId.PAPAL_DECREE,
                    CardChoice.CategoryChoice(AdvanceCategory.RELIGION),
                ),
            ) as CardResolution.PapalDecree

        assertThat(result.effects.papalCategory).isEqualTo(AdvanceCategory.RELIGION)
    }

    @Test
    fun piratesMayTargetAnOverseasBox() {
        val context = cardContext("pirates-vikings").withMarkers("china" to cardPlayerTwo)

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.PIRATES_VIKINGS, CardChoice.ProvinceChoice(ProvinceId("china"))),
            )

        assertThat(result)
            .isEqualTo(
                CardResolution.MarkerReduction(
                    EventId.PIRATES_VIKINGS,
                    ProvinceId("china"),
                    cardPlayerTwo,
                    EventTokenReplacement.STOCK,
                )
            )
    }

    @Test
    fun emptyStockLeavesReplacementChoiceWithTheMarkerOwner() {
        val context =
            cardContext("pirates-vikings").withMarkers("tunis" to cardPlayerTwo).withPlayer(
                cardPlayerTwo
            ) { player ->
                player.copy(stockTokens = 0, poolTokens = 4)
            }

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.PIRATES_VIKINGS, CardChoice.ProvinceChoice(ProvinceId("tunis"))),
            ) as CardResolution.MarkerReduction

        assertThat(result.replacement).isEqualTo(EventTokenReplacement.POOL_OR_EMPTY)
    }

    @Test
    fun noAvailableReplacementTokenLeavesTheProvinceEmpty() {
        val context =
            cardContext("rebellion").withMarkers("tunis" to cardPlayerTwo).withPlayer(
                cardPlayerTwo
            ) { player ->
                player.copy(stockTokens = 0, poolTokens = 0)
            }

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.REBELLION, CardChoice.ProvinceChoice(ProvinceId("tunis"))),
            ) as CardResolution.MarkerReduction

        assertThat(result.replacement).isEqualTo(EventTokenReplacement.EMPTY)
    }

    @Test
    fun piratesCannotReduceAWhiteMarker() {
        val context =
            cardContext("pirates-vikings")
                .copy(
                    markers =
                        mapOf(ProvinceId("tunis") to CardMarker(cardPlayerTwo, coloured = false))
                )

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(
                        EventId.PIRATES_VIKINGS,
                        CardChoice.ProvinceChoice(ProvinceId("tunis")),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun rebellionCannotTargetAForeignCapital() {
        val context = cardContext("rebellion").withMarkers("genoa" to cardPlayerTwo)

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(EventId.REBELLION, CardChoice.ProvinceChoice(ProvinceId("genoa"))),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun rebellionCannotTargetTheNewWorld() {
        val context = cardContext("rebellion").withMarkers("north-america" to cardPlayerTwo)

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(
                        EventId.REBELLION,
                        CardChoice.ProvinceChoice(ProvinceId("north-america")),
                    ),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun crusadesSelectsAnAreaSixMarketAndRecordsWaltersBonus() {
        val result =
            CardRules.resolve(
                cardContext("the-crusades"),
                cardPlayerOne,
                cardEvent(EventId.THE_CRUSADES, CardChoice.ProvinceChoice(ProvinceId("acre"))),
            ) as CardResolution.CrusadesPlacement

        assertThat(result.province).isEqualTo(ProvinceId("acre"))
        assertThat(result.effects.crusadesPlayed).isTrue()
    }

    @Test
    fun crusadesCannotDominateASatellite() {
        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    cardContext("the-crusades"),
                    cardPlayerOne,
                    cardEvent(EventId.THE_CRUSADES, CardChoice.ProvinceChoice(ProvinceId("libya"))),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun enlightenedRulerPlayRecordsProtectionWithoutReversingHistory() {
        val result =
            CardRules.resolve(
                cardContext("enlightened-ruler"),
                cardPlayerOne,
                cardEvent(EventId.ENLIGHTENED_RULER),
            ) as CardResolution.EnlightenedRuler

        assertThat(result.effects.enlightenedRulers).containsExactly(cardPlayerOne)
    }

    @Test
    fun armorPlayRecordsItsOwner() {
        val result =
            CardRules.resolve(
                cardContext("armor"),
                cardPlayerOne,
                cardEvent(EventId.ARMOR),
            ) as CardResolution.Military

        assertThat(result.effects.military)
            .containsExactly(CardMilitaryPlay(cardPlayerOne, EventId.ARMOR))
    }

    @Test
    fun stirrupsCombinesWithTheSamePlayersArmor() {
        val context =
            cardContext("stirrups")
                .copy(
                    effects =
                        CardTurnEffects(
                            military = listOf(CardMilitaryPlay(cardPlayerOne, EventId.ARMOR))
                        )
                )

        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                cardEvent(EventId.STIRRUPS),
            ) as CardResolution.Military

        assertThat(result.effects.militaryBonus(cardPlayerOne, cardPlayerTwo)).isEqualTo(2)
    }

    @Test
    fun aCardMustBeHeldBeforeItCanBePlayed() {
        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(cardContext("wool-1"), cardPlayerOne, CardPlay("wool-2"))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.CARD_NOT_HELD)
    }

    @Test
    fun obsoleteMilitaryCardsRemainUnplayableBurdens() {
        val context = cardContext("armor").copy(voids = CardVoids(armorAndStirrups = true))

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(context, cardPlayerOne, cardEvent(EventId.ARMOR))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.MISERY_BURDEN)
    }
}
