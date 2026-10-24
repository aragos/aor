package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LaterCardRulesTest {
    @Test
    fun allPrintedDecksHaveDistinctCardsAndCompleteEffects() {
        assertThat(Epoch.entries.map { epoch -> CardData.forEpoch(epoch).size })
            .containsExactly(29, 22, 13)
            .inOrder()
        assertThat(CardData.all.map(CardDefinition::id).toSet()).hasSize(64)
        assertThat(CardEffectData.all.keys)
            .containsExactlyElementsIn(CardData.all.map(CardDefinition::id))
        assertThat(CardData.all.groupingBy(CardDefinition::kind).eachCount())
            .containsExactly(CardKind.COMMODITY, 27, CardKind.EVENT, 18, CardKind.LEADER, 19)
        assertThat(LeaderData.definitions.map(LeaderDefinition::id))
            .containsExactlyElementsIn(LeaderId.entries)
    }

    @Test
    fun onlyLeadersAndTheFivePrintedSingleUseEventsAreRemoved() {
        val removed =
            CardData.all.filter { card -> card.shape == CardShape.SQUARE }.map(CardDefinition::id)
        assertThat(removed)
            .containsExactlyElementsIn(
                LeaderId.entries.map(LeaderId::cardId) +
                    listOf("armor", "stirrups", "gunpowder", "long-bow", "mongol-armies")
            )
    }

    @Test
    fun allLaterLeadersHaveTheirPrintedAdvanceCredits() {
        val expected =
            mapOf(
                LeaderId.CHRISTOPHER_COLUMBUS to
                    mapOf(AdvanceId.NEW_WORLD to 30, AdvanceId.OCEAN_NAVIGATION to 30),
                LeaderId.DESIDERIUS_ERASMUS to
                    mapOf(AdvanceId.PRINTED_WORD to 20, AdvanceId.RENAISSANCE to 20),
                LeaderId.IBN_MAJID to
                    mapOf(AdvanceId.OCEAN_NAVIGATION to 20, AdvanceId.COSMOPOLITAN to 20),
                LeaderId.JOHANN_GUTENBERG to mapOf(AdvanceId.PRINTED_WORD to 30),
                LeaderId.MARCO_POLO to
                    mapOf(AdvanceId.COSMOPOLITAN to 20, AdvanceId.OVERLAND_EAST to 20),
                LeaderId.NICOLAUS_COPERNICUS to
                    mapOf(AdvanceId.THE_HEAVENS to 20, AdvanceId.INSTITUTIONAL_RESEARCH to 20),
                LeaderId.PRINCE_HENRY to
                    mapOf(AdvanceId.OCEAN_NAVIGATION to 20, AdvanceId.INSTITUTIONAL_RESEARCH to 20),
                LeaderId.WILLIAM_CAXTON to mapOf(AdvanceId.PRINTED_WORD to 20),
                LeaderId.ANDREAS_VESALIUS to
                    mapOf(AdvanceId.HUMAN_BODY to 20, AdvanceId.ENLIGHTENMENT to 20),
                LeaderId.BARTOLOME_DE_LAS_CASAS to mapOf(AdvanceId.COSMOPOLITAN to 30),
                LeaderId.GALILEO_GALILEI to
                    mapOf(AdvanceId.THE_HEAVENS to 20, AdvanceId.RENAISSANCE to 20),
                LeaderId.HENRY_OLDENBURG to mapOf(AdvanceId.ENLIGHTENMENT to 30),
                LeaderId.LEONARDO_DA_VINCI to
                    mapOf(
                        AdvanceId.MASTER_ART to 20,
                        AdvanceId.HUMAN_BODY to 20,
                        AdvanceId.RENAISSANCE to 20,
                    ),
                LeaderId.SIR_ISAAC_NEWTON to
                    mapOf(AdvanceId.LAWS_OF_MATTER to 20, AdvanceId.ENLIGHTENMENT to 20),
            )
        val actual =
            LeaderData.definitions
                .filter { leader -> leader.id in expected }
                .associate { leader ->
                    leader.id to
                        leader.discounts.associate { credit -> credit.advance to credit.amount }
                }
        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun newCommodityCopiesKeepTheirPrintedEpochAndCommonEffect() {
        assertThat(CardData.definition("timber-2-1").epoch).isEqualTo(Epoch.TWO)
        assertThat(CardEffectData.forCard("timber-2-1")).isEqualTo(CardEffectData.forCard("timber"))
        assertThat(CardData.definition("gold-3").epoch).isEqualTo(Epoch.THREE)
    }

    @Test
    fun heldPiratesUsesTheCurrentEpoch() {
        val context =
            cardContext("pirates-vikings")
                .withMarkers(
                    "genoa" to cardPlayerOne,
                    "venice" to cardPlayerTwo,
                    "tunis" to cardPlayerThree,
                )
                .copy(epoch = Epoch.THREE)
        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay(
                    "pirates-vikings",
                    CardChoice.ProvincesChoice(
                        listOf(ProvinceId("genoa"), ProvinceId("venice"), ProvinceId("tunis"))
                    ),
                ),
            ) as CardResolution.MarkerReductions
        assertThat(result.reductions.map(CardResolution.MarkerReduction::province))
            .containsExactly(ProvinceId("genoa"), ProvinceId("venice"), ProvinceId("tunis"))
    }

    @Test
    fun piratesRejectsRepeatedTargetsBeforeResolvingAnyReduction() {
        val context =
            cardContext("pirates-vikings")
                .withMarkers("tunis" to cardPlayerTwo)
                .copy(epoch = Epoch.TWO)
        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    CardPlay(
                        "pirates-vikings",
                        CardChoice.ProvincesChoice(
                            listOf(ProvinceId("tunis"), ProvinceId("tunis"))
                        ),
                    ),
                )
            }
        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun religiousStrifeCountsReligionAndRespectsEnlightenedRuler() {
        val context =
            cardContext("religious-strife")
                .withAdvances(cardPlayerOne, AdvanceId.PATRONAGE, AdvanceId.CATHEDRAL)
                .withAdvances(cardPlayerTwo, AdvanceId.HOLY_INDULGENCE)
                .copy(
                    effects = CardTurnEffects(enlightenedRulers = setOf(cardPlayerOne)),
                    epoch = Epoch.THREE,
                )
        val result =
            CardRules.resolve(context, cardPlayerOne, CardPlay("religious-strife"))
                as CardResolution.ReligiousStrife
        assertThat(result.spaces)
            .containsExactly(cardPlayerOne, 0, cardPlayerTwo, 1, cardPlayerThree, 0)
        assertThat(result.permanent).isTrue()
    }

    @Test
    fun epochTwoStrifeDoesNotPermanentlyVoidPapalDecree() {
        val result =
            CardRules.resolve(
                cardContext("religious-strife").copy(epoch = Epoch.TWO),
                cardPlayerOne,
                CardPlay("religious-strife"),
            ) as CardResolution.ReligiousStrife
        assertThat(result.permanent).isFalse()
    }

    @Test
    fun aPapalDecreeAfterStrifeCannotReestablishTheBan() {
        val context =
            cardContext("papal-decree")
                .copy(effects = CardTurnEffects(religiousStrifePlayed = true))
        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay(
                    "papal-decree",
                    CardChoice.CategoryChoice(AdvanceCategory.SCIENCE),
                ),
            ) as CardResolution.PapalDecree
        assertThat(result.effects.papalCategory).isNull()
    }

    @Test
    fun blackDeathAffectsOnlyItsSelectedAvailableArea() {
        val context =
            cardContext("black-death")
                .withMarkers("genoa" to cardPlayerOne, "tunis" to cardPlayerTwo)
                .copy(tokenProvinces = setOf(ProvinceId("venice"), ProvinceId("tunis")))
        val result =
            CardRules.resolve(
                context,
                cardPlayerOne,
                CardPlay("black-death", CardChoice.AreaChoice(GeographicalArea.VII)),
            ) as CardResolution.BlackDeath
        assertThat(result.tokenProvinces).containsExactly(ProvinceId("venice"))
        assertThat(result.reductions.map(CardResolution.MarkerReduction::province))
            .containsExactly(ProvinceId("genoa"))
    }

    @Test
    fun blackDeathCannotChooseAnAreaOutsideTheGame() {
        assertFailsWith<CardRuleViolation> {
            CardRules.resolve(
                cardContext("black-death"),
                cardPlayerOne,
                CardPlay("black-death", CardChoice.AreaChoice(GeographicalArea.I)),
            )
        }
    }

    @Test
    fun olderMilitaryRemainsEffectiveAgainstThirdParties() {
        val effects =
            CardTurnEffects(
                military =
                    listOf(
                        CardMilitaryPlay(cardPlayerOne, EventId.ARMOR),
                        CardMilitaryPlay(cardPlayerTwo, EventId.LONG_BOW),
                    )
            )
        assertThat(effects.militaryBonus(cardPlayerOne, cardPlayerTwo)).isEqualTo(0)
        assertThat(effects.militaryBonus(cardPlayerOne, cardPlayerThree)).isEqualTo(1)
        assertThat(effects.militaryBonus(cardPlayerTwo, cardPlayerOne)).isEqualTo(1)
    }

    @Test
    fun gunpowderWinsAModifiedWarTieAgainstLongBow() {
        val context =
            cardContext("silk")
                .copy(
                    effects =
                        CardTurnEffects(
                            military =
                                listOf(
                                    CardMilitaryPlay(cardPlayerOne, EventId.GUNPOWDER),
                                    CardMilitaryPlay(cardPlayerTwo, EventId.LONG_BOW),
                                )
                        )
                )
        val result =
            WarRules.roll(
                context,
                WarState(cardPlayerOne, cardPlayerTwo, 1),
                WarDice(DieRoll(3), DieRoll(4)),
            ) as WarResult.Victory
        assertThat(result.winner).isEqualTo(cardPlayerOne)
        assertThat(result.requiredTransfers).isEqualTo(0)
    }

    @Test
    fun marcoPoloSnapshotsMongolArmiesWhenPlayed() {
        val original = cardContext("marco-polo")
        val play = LeaderRules.play(original, cardPlayerOne, LeaderId.MARCO_POLO)
        val afterMongols = original.copy(voids = CardVoids(crusades = true), effects = play.effects)
        assertThat(LeaderRules.discount(afterMongols, cardPlayerOne, AdvanceId.COSMOPOLITAN))
            .isEqualTo(20)
    }

    @Test
    fun marcoPoloPlayedAfterMongolsRebatesBothEnhancedCredits() {
        val context =
            cardContext("marco-polo")
                .withAdvances(
                    cardPlayerOne,
                    AdvanceId.PRINTED_WORD,
                    AdvanceId.COSMOPOLITAN,
                    AdvanceId.OVERLAND_EAST,
                )
                .copy(voids = CardVoids(crusades = true))
        val result = LeaderRules.play(context, cardPlayerOne, LeaderId.MARCO_POLO)
        assertThat(result.rebate).isEqualTo(80)
        assertThat(result.leader.mongolEnhanced).isTrue()
    }

    @Test
    fun secondDisplayBoxAllowsOnlyOneOtherPatronageClaim() {
        val rules = RuleOptions(patronage = PatronageRule.THREE_BOX_DISPLAY)
        val first =
            LeaderRules.play(
                cardContext("silk").copy(rules = rules),
                cardPlayerOne,
                LeaderId.CHARLEMAGNE,
            )
        val second =
            LeaderRules.play(
                cardContext("silk").copy(rules = rules, effects = first.effects),
                cardPlayerOne,
                LeaderId.RASHID_AD_DIN,
            )
        val claimed =
            LeaderRules.claim(
                cardContext("silk")
                    .withAdvances(cardPlayerTwo, AdvanceId.PATRONAGE)
                    .copy(rules = rules, effects = second.effects),
                cardPlayerTwo,
                LeaderId.RASHID_AD_DIN,
            )
        val failure =
            assertFailsWith<CardRuleViolation> {
                LeaderRules.claim(
                    cardContext("silk")
                        .withAdvances(cardPlayerThree, AdvanceId.PATRONAGE)
                        .copy(rules = rules, effects = claimed.effects),
                    cardPlayerThree,
                    LeaderId.RASHID_AD_DIN,
                )
            }
        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_LEADER_CLAIM)
        assertThat(claimed.cash).isEqualTo(0)
    }

    @Test
    fun alternateWrittenRecordDoesNotEnhanceLeaderDiscounts() {
        val played = LeaderRules.play(cardContext("silk"), cardPlayerOne, LeaderId.CHARLEMAGNE)
        val context =
            cardContext("silk")
                .withAdvances(cardPlayerOne, AdvanceId.WRITTEN_RECORD)
                .copy(
                    rules = RuleOptions(writtenRecord = WrittenRecordRule.TAKEOVER_TRADE),
                    effects = played.effects,
                )
        assertThat(LeaderRules.discount(context, cardPlayerOne, AdvanceId.NATIONALISM))
            .isEqualTo(20)
    }
}
