package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CommodityPayoutBoxTest {
    @Test
    fun industryAndShortageDoNotPayANonOwner() {
        assertThat(
                CardRules.commodityPayoutBox(marketCount = 0, industry = true, adjustmentOffset = 1)
            )
            .isEqualTo(0)
    }

    @Test
    fun firstOwnedMarketReceivesIndustryAndShortage() {
        assertThat(
                CardRules.commodityPayoutBox(marketCount = 1, industry = true, adjustmentOffset = 1)
            )
            .isEqualTo(3)
    }

    @Test
    fun surplusCanReduceTheFirstMarketToNoPayout() {
        assertThat(
                CardRules.commodityPayoutBox(
                    marketCount = 1,
                    industry = false,
                    adjustmentOffset = -1,
                )
            )
            .isEqualTo(0)
    }

    @Test
    fun invalidForecastOffsetsAreRejectedAtTheirOwner() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CardRules.commodityPayoutBox(
                    marketCount = 1,
                    industry = false,
                    adjustmentOffset = 2,
                )
            }

        assertThat(failure).hasMessageThat().contains("adjustment offset")
    }
}
