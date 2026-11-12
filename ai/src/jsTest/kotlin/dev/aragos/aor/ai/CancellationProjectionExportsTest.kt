package dev.aragos.aor.ai

import kotlin.js.JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CancellationProjectionExportsTest {
    private val context =
        """{
        "actor":{"value":"p1"},"cash":40,
        "offers":[{"command":"cancel-adjustment cloth shortage","commodities":["CLOTH"],"cost":5,"cashAfter":35}],
        "payouts":[{"commodity":"CLOTH","adjustments":["SHORTAGE","SURPLUS"],"owners":[
            {"player":{"value":"p1"},"provinces":[{"value":"florence"}],"marketCount":1,"effectiveBox":2,"payout":20,"industryBonus":0},
            {"player":{"value":"p2"},"provinces":[{"value":"london"}],"marketCount":1,"effectiveBox":3,"payout":45,"industryBonus":1}
        ]}]
    }"""

    @Test
    fun jsonQuotesRetainSeparatePublicOwnersAndOnlyRemoveOneMarker() {
        val quotes = JSON.parse<dynamic>(quoteAdjustmentCancellations(context))
        assertEquals("pass", quotes[0].command as String)
        assertEquals(40, quotes[0].cashAfter as Int)
        assertEquals("SHORTAGE", quotes[1].adjustment as String)
        assertEquals(35, quotes[1].cashAfter as Int)
        assertEquals("SURPLUS", quotes[1].commodities[0].after[0] as String)
        assertEquals(0, quotes[1].commodities[0].owners[0].payoutAfter as Int)
        assertEquals(5, quotes[1].commodities[0].owners[1].payoutAfter as Int)
        assertEquals("p2", quotes[1].commodities[0].owners[1].player.value as String)
    }

    @Test
    fun hiddenStateFieldsCannotEnterTheJsonProjectionBoundary() {
        val altered = JSON.parse<dynamic>(context)
        altered.deck = arrayOf("unknown-private-card")
        assertFailsWith<IllegalArgumentException> {
            quoteAdjustmentCancellations(JSON.stringify(altered))
        }
    }

    @Test
    fun fractionalCashCannotBeSilentlyTruncated() {
        val altered = JSON.parse<dynamic>(context)
        altered.cash = 40.5
        assertFailsWith<IllegalArgumentException> {
            quoteAdjustmentCancellations(JSON.stringify(altered))
        }
    }

    @Test
    fun inconsistentCashQuoteCannotBeRepricedByTheAdapter() {
        val altered = JSON.parse<dynamic>(context)
        altered.offers[0].cashAfter = 39
        assertFailsWith<IllegalArgumentException> {
            quoteAdjustmentCancellations(JSON.stringify(altered))
        }
    }
}
