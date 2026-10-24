package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardEffectDataTest {
    @Test
    fun everyEpochOneCardHasTypedRules() {
        assertThat(CardEffectData.epochOne.keys)
            .containsExactlyElementsIn(CardData.epochOne.map(CardDefinition::id))
        assertThat(CardEffectData.epochOne.values.filterIsInstance<CardEffectSpec.Event>())
            .hasSize(13)
        assertThat(CardEffectData.epochOne.values.filterIsInstance<CardEffectSpec.Leader>())
            .hasSize(5)
    }

    @Test
    fun duplicateCommodityCardsHaveTheSameChoices() {
        assertThat(CardEffectData.forCard("stone-1")).isEqualTo(CardEffectData.forCard("stone-2"))
    }

    @Test
    fun dualCommodityCardOffersOnlyItsPrintedChoices() {
        val effect = CardEffectData.forCard("ivory-gold") as CardEffectSpec.CommodityChoices

        assertThat(effect.choices).containsExactly(Commodity.IVORY, Commodity.GOLD)
    }

    @Test
    fun allFiveLeadersHaveTheirPrintedCredits() {
        assertThat(
                LeaderData.definitions.filter { leader ->
                    CardData.definition(leader.id.cardId).epoch == Epoch.ONE
                }
            )
            .containsExactly(
                LeaderDefinition(
                    LeaderId.CHARLEMAGNE,
                    listOf(LeaderDiscount(AdvanceId.NATIONALISM, amount = 20)),
                ),
                LeaderDefinition(
                    LeaderId.DIONYSUS_EXIGUUS,
                    listOf(LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 20)),
                ),
                LeaderDefinition(
                    LeaderId.RASHID_AD_DIN,
                    listOf(
                        LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 10),
                        LeaderDiscount(AdvanceId.OVERLAND_EAST, amount = 10),
                    ),
                ),
                LeaderDefinition(
                    LeaderId.ST_BENEDICT,
                    listOf(
                        LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 10),
                        LeaderDiscount(AdvanceId.PATRONAGE, amount = 10),
                    ),
                ),
                LeaderDefinition(
                    LeaderId.WALTER_THE_PENNILESS,
                    listOf(
                        LeaderDiscount(
                            AdvanceId.OVERLAND_EAST,
                            amount = 20,
                            condition = LeaderDiscountCondition.CRUSADES_THIS_TURN,
                            increasedAmount = 30,
                        )
                    ),
                ),
            )
    }

    @Test
    fun crusadesIncreaseWaltersCredit() {
        val discount = LeaderData.definition(LeaderId.WALTER_THE_PENNILESS).discounts.single()

        assertThat(discount.amountFor(crusadesPlayed = true)).isEqualTo(30)
    }
}
