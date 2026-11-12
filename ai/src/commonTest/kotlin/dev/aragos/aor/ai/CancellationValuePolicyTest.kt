package dev.aragos.aor.ai

import dev.aragos.aor.AdjustmentCancellationContext
import dev.aragos.aor.AdjustmentCancellationOffer
import dev.aragos.aor.CardRules
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.CommodityOwnerView
import dev.aragos.aor.CommodityPayoutView
import dev.aragos.aor.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CancellationValuePolicyTest {
    private val players = (1..4).map { PlayerId("p$it") }

    private fun input(kind: CommodityAdjustment = CommodityAdjustment.SHORTAGE) =
        CancellationValueInput(
            AdjustmentCancellationContext(
                players[0],
                40,
                listOf(
                    AdjustmentCancellationOffer(
                        "cancel-adjustment gold ${kind.name.lowercase()}",
                        setOf(Commodity.GOLD, Commodity.IVORY),
                        10,
                        30,
                    )
                ),
                listOf(Commodity.GOLD, Commodity.IVORY).map { commodity ->
                    CommodityPayoutView(
                        commodity,
                        listOf(kind, kind),
                        listOf(
                            CommodityOwnerView(
                                players[0],
                                emptyList(),
                                2,
                                if (kind == CommodityAdjustment.SHORTAGE) 3 else 1,
                                if (kind == CommodityAdjustment.SHORTAGE)
                                    CardRules.commodityPayout(commodity, 3)
                                else CardRules.commodityPayout(commodity, 1),
                                0,
                            ),
                            CommodityOwnerView(
                                players[1],
                                emptyList(),
                                1,
                                if (kind == CommodityAdjustment.SHORTAGE) 2 else 0,
                                if (kind == CommodityAdjustment.SHORTAGE)
                                    CardRules.commodityPayout(commodity, 2)
                                else 0,
                                0,
                            ),
                        ),
                    )
                },
            ),
            players,
            2,
            4,
            17,
            false,
            2,
            3,
            listOf("ivory-gold", "wool-1"),
        )

    private fun model(intercept: Double = 0.0) =
        CancellationValueModel(
            CancellationValuePolicy.VERSION,
            CancellationValuePolicy.featureNames,
            List(58) { 1.0 },
            List(58) { 0.0 },
            List(58) { 1.0 },
            intercept,
            "a".repeat(64),
        )

    @Test
    fun goldIvoryPhysicalCardCountsOnce() {
        val row = CancellationValuePolicy.features(input()).values.single()
        assertEquals(1.0, row[56])
        assertEquals(2.0, row[14])
        assertEquals(1.0, row[15])
    }

    @Test
    fun doubleMarkerKeepsCurrentHeadAndRemainingOccurrence() {
        val row = CancellationValuePolicy.features(input()).values.single()
        assertEquals(4.0, row[19])
        assertEquals(2.0, row[22])
        assertEquals(2.0, row[23])
        assertEquals(0.0, row[28])
    }

    @Test
    fun shortageAndSurplusHaveDifferentFeatures() {
        assertNotEquals(
            CancellationValuePolicy.features(input()).values.single(),
            CancellationValuePolicy.features(input(CommodityAdjustment.SURPLUS)).values.single(),
        )
    }

    @Test
    fun relativeRivalsStayDistinct() {
        val row = CancellationValuePolicy.features(input()).values.single()
        assertEquals(4.0, row[24])
        assertEquals(2.0, row[29])
        assertEquals(0.0, row[34])
    }

    @Test
    fun zeroTrainingVarianceSuppressesColumn() {
        val value = model().copy(standardDeviation = List(58) { 0.0 }, intercept = 0.25)
        assertEquals(0.25, value.predict(List(58) { 999.0 }))
    }

    @Test
    fun positiveTieUsesCanonicalCommandOrder() {
        assertEquals(
            "cancel-adjustment gold shortage",
            CancellationValuePolicy.choose(
                linkedMapOf(
                    "cancel-adjustment wool surplus" to 0.2,
                    "cancel-adjustment gold shortage" to 0.2,
                )
            ),
        )
    }

    @Test
    fun zeroTieKeepsPass() {
        assertEquals(
            "pass",
            CancellationValuePolicy.choose(mapOf("cancel-adjustment gold shortage" to 0.0)),
        )
    }

    @Test
    fun passOnlyWindowKeepsPass() {
        assertEquals("pass", CancellationValuePolicy.choose(emptyMap()))
    }

    @Test
    fun negativePredictionsKeepPass() {
        assertEquals(
            "pass",
            CancellationValuePolicy.choose(mapOf("cancel-adjustment gold shortage" to -0.1)),
        )
    }

    @Test
    fun rejectsNonfiniteWeights() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(coefficients = List(58) { Double.NaN })
        }
    }

    @Test
    fun rejectsDictionaryMutation() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(featureNames = model().featureNames.reversed())
        }
    }

    @Test
    fun rejectsPartialOwnerHand() {
        assertFailsWith<IllegalArgumentException> { input().copy(ownCardDefinitions = emptyList()) }
    }

    @Test
    fun rejectsHiddenOrUnknownOwner() {
        assertFailsWith<IllegalArgumentException> {
            input().copy(turnOrder = listOf(players[0], PlayerId("p9"), players[2], players[3]))
        }
    }
}
