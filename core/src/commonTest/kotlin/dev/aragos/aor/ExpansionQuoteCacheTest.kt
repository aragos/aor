package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExpansionQuoteCacheTest {
    private val first = PlayerId("p1")
    private val second = PlayerId("p2")
    private val florence = ProvinceId("florence")

    @Test
    fun bothViewsReuseOneSetOfActiveQuotes() {
        val state = LegalChoicesCacheFixture.expansion()
        val decision = state.viewForDecision(first)
        val player = state.viewForPlayer(first)

        assertTrue(decision.expansionOffers.isNotEmpty())
        assertSame(decision.expansionInspections, player.expansionInspections)
        assertSame(decision.expansionOffers, player.expansionOffers)
    }

    @Test
    fun activeQuotesStayPrivateToTheirViewerAfterAQuery() {
        val state = LegalChoicesCacheFixture.expansion()
        val active = state.viewForDecision(first)

        assertTrue(active.expansionInspections.isNotEmpty())
        assertTrue(state.viewForPlayer(second).expansionInspections.isEmpty())
        assertTrue(state.publicView().expansionInspections.isEmpty())
    }

    @Test
    fun spendingPoolUpdatesTheNextSnapshotsQuotes() {
        val state = LegalChoicesCacheFixture.expansion()
        val before = state.viewForDecision(first)
        val after = state.step(TextCodec.parse("p1: place 1 florence")).viewForDecision(first)

        assertEquals(
            20,
            before.expansionInspections.single { it.destination == florence }.availablePool,
        )
        assertEquals(
            19,
            after.expansionInspections.single { it.destination == florence }.availablePool,
        )
    }
}
