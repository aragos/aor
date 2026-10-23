package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardRulePolicyGuardsTest {
    @Test
    fun crusadesCannotInventAnExtraPhysicalMarkerWhileTheRulingIsPending() {
        val original = cardContext("the-crusades")
        val markets =
            original.provinces.values
                .filter { province ->
                    province.area != null &&
                        !province.isSatellite &&
                        province.id != ProvinceId("acre")
                }
                .take(25)
        val context =
            original.copy(
                markers =
                    markets.associate { province ->
                        province.id to CardMarker(cardPlayerOne)
                    }
            )

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.resolve(
                    context,
                    cardPlayerOne,
                    cardEvent(EventId.THE_CRUSADES, CardChoice.ProvinceChoice(ProvinceId("acre"))),
                )
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.MARKER_RULING_REQUIRED)
    }

    @Test
    fun warCannotInventMarkersBeyondTheWinnersSupply() {
        val original = cardContext("war")
        val markets =
            original.provinces.values
                .filter { province ->
                    province.area != null &&
                        !province.isSatellite &&
                        province.id != ProvinceId("tunis")
                }
                .take(25)
        val context =
            original.copy(
                markers =
                    markets.associate { province ->
                        province.id to CardMarker(cardPlayerOne)
                    } + (ProvinceId("tunis") to CardMarker(cardPlayerTwo))
            )
        val victory =
            WarResult.Victory(
                cardPlayerOne,
                cardPlayerTwo,
                requiredTransfers = 1,
                totals = mapOf(cardPlayerOne to 4, cardPlayerTwo to 3),
            )

        val failure =
            assertFailsWith<CardRuleViolation> {
                WarRules.surrender(context, victory, setOf(ProvinceId("tunis")))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.MARKER_RULING_REQUIRED)
    }

    @Test
    fun woolBeyondTheTenPrintedCardBoxesRequiresTheOverflowRuling() {
        val players =
            CapitalData.definitions
                .mapIndexed { index, capital ->
                    PlayerId("p${index + 1}") to
                        CardPlayerContext(
                            cash = 40,
                            writtenCash = 40,
                            poolTokens = 0,
                            stockTokens = 36,
                            advances = setOf(AdvanceId.INDUSTRY),
                            capital = ProvinceId(capital.id),
                            misery = MiseryStep.ZERO,
                        )
                }
                .toMap()
        val wool =
            ProvinceData.definitions.filter { province -> Commodity.WOOL in province.commodities }
        val context =
            CardContext(
                players = players,
                markers = wool.associate { province -> province.id to CardMarker(cardPlayerOne) },
                adjustments = mapOf(Commodity.WOOL to listOf(CommodityAdjustment.SHORTAGE)),
            )

        val failure =
            assertFailsWith<CardRuleViolation> {
                CardRules.commodityPayment(context, Commodity.WOOL)
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.PAYOUT_RULING_REQUIRED)
    }
}
