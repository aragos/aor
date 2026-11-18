package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalTokenNeighborPolicyTest {
    private fun observation(amounts: List<Int>, cash: Int = 10) =
        BotObservation(
            playerId = "p1",
            decision = "buy-tokens",
            context = emptyMap(),
            availableCash = cash,
            availableTokens = 0,
            candidates = amounts.map { BotCandidate("buy-tokens $it", emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private val offered = LocalTokenNeighborMixture(.25, 1, TokenNeighborAdmission.OFFERED_ONLY)
    private val quoted =
        offered.copy(
            departureProbability = .5,
            admission = TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS,
        )

    @Test
    fun fullMixtureMovesProbabilityFromEveryBaselineAction() {
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(0, 1)),
                listOf(.2, .8),
                offered,
            )
        assertEquals(.35, actual.probabilities[0], 1e-12)
        assertEquals(.65, actual.probabilities[1], 1e-12)
    }

    @Test
    fun quotedNeighborsCanCrossTheStockSuggestionBoundary() {
        val amounts = mutableListOf<Int>()
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(2, 3, 10)),
                listOf(0.0, 1.0, 0.0),
                quoted,
                TokenNeighborQuote { amount ->
                    amounts.add(amount)
                    TokenPurchaseOfferView(amount, amount, 3, 10 - amount, 0, 3)
                },
            )
        assertEquals(listOf(4), amounts)
        assertEquals(listOf(2, 3, 10, 4), actual.amounts)
        assertEquals(listOf(.25, .5, 0.0, .25), actual.probabilities)
        assertEquals(4, actual.quotedOffers.single().amount)
    }

    @Test
    fun offeredOnlyCannotSpendProbabilityOnUnquotedCommands() {
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(2, 3, 10)),
                listOf(0.0, 1.0, 0.0),
                offered,
            )
        assertEquals(listOf(.25, .75, 0.0), actual.probabilities)
        assertTrue(actual.quotedOffers.isEmpty())
    }

    @Test
    fun signedNeighborsAreAffordableAtBothCashBoundaries() {
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(-1, 0, 1), cash = 1),
                listOf(.2, .3, .5),
                quoted.copy(radius = 2),
                TokenNeighborQuote { error("All affordable amounts exist.") },
            )
        assertEquals(listOf(-1, 0, 1), actual.amounts)
        assertEquals(1.0, actual.probabilities.sum(), 1e-12)
        assertTrue(actual.probabilities.all { it > 0.0 })
    }

    @Test
    fun noNeighborFallsBackToTheSameBaselineAction() {
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(0), cash = 0),
                listOf(1.0),
                quoted,
                TokenNeighborQuote { error("No amount is affordable.") },
            )
        assertEquals(listOf(1.0), actual.probabilities)
    }

    @Test
    fun zeroDepartureKeepsExactProbabilityBytesWithoutAnyQuotes() {
        val probabilities = listOf(.1, .2, .7)
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(1, 2, 3)),
                probabilities,
                quoted.copy(departureProbability = 0.0),
            )
        assertTrue(actual.probabilities === probabilities)
        assertTrue(actual.quotedOffers.isEmpty())
    }

    @Test
    fun oneDrawSamplesTheMixtureWithoutSelectingZeroSupport() {
        val actual =
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(0, 1, 10)),
                listOf(.2, .8, 0.0),
                offered,
            )
        assertEquals("buy-tokens 0", actual.command(0.0))
        assertEquals("buy-tokens 1", actual.command(.9999999999999999))
    }

    @Test
    fun quotedAdmissionRequiresAnAuthorityCapability() {
        assertFailsWith<IllegalArgumentException> {
            LocalTokenNeighborPolicy.distribution(observation(listOf(3)), listOf(1.0), quoted)
        }
    }

    @Test
    fun quoteMustBelongToTheExactRequestedNeighbor() {
        assertFailsWith<IllegalArgumentException> {
            LocalTokenNeighborPolicy.distribution(
                observation(listOf(3)),
                listOf(1.0),
                quoted,
                TokenNeighborQuote { TokenPurchaseOfferView(9, 9, 3, 1, 0, 3) },
            )
        }
    }

    @Test
    fun nonCanonicalAmountsCannotEnterTheKernel() {
        assertFailsWith<IllegalArgumentException> {
            LocalTokenNeighborPolicy.amount("buy-tokens +1")
        }
    }

    @Test
    fun invalidMixtureProbabilitiesAreRejectedByTheirOwner() {
        assertFailsWith<IllegalArgumentException> {
            LocalTokenNeighborMixture(Double.NaN, 1, TokenNeighborAdmission.OFFERED_ONLY)
        }
    }
}
