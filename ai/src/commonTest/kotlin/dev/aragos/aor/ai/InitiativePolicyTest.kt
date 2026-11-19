package dev.aragos.aor.ai

import dev.aragos.aor.ContestedExpansionProspectView
import dev.aragos.aor.ExpansionForecastView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.PeacefulExpansionProspectView
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.test.Test
import kotlin.test.assertEquals

class InitiativePolicyTest {
    private val players = (1..4).map { PlayerId("p$it") }
    private val seat = players.first()
    private val state =
        GameState(
                GameHeader(players.map { Player(it, it.value) }, creatorId = seat),
                turnNumber = 3,
                stage = Stage.TOKEN_PURCHASE,
            )
            .let { initial ->
                val capitals = players.zip(initial.catalog.capitals).toMap()
                initial.copy(
                    players =
                        initial.players.mapValues { (player, current) ->
                            current.copy(
                                capital =
                                    requireNotNull(capitals[player]) {
                                        "Every seat has a test capital."
                                    }
                            )
                        }
                )
            }
    private val view =
        state
            .viewForDecision(seat)
            .copy(
                capitalOrder = players,
                tokenPurchaseOffers =
                    (18..21).map { amount ->
                        TokenPurchaseOfferView(
                            amount,
                            amount,
                            amount,
                            40 - amount,
                            36 - amount,
                            amount,
                        )
                    },
                pastTokenPurchases = listOf(players.associateWith { 21 }),
            )
    private val config = InitiativePolicyConfig(2, .5, 1.0, false, 0.0)

    @Test
    fun usesTheCheapestUsefulUndercut() {
        assertEquals("buy-tokens 20", InitiativePolicy.bid(view, seat, "buy-tokens 21", 7, config))
    }

    @Test
    fun weightsARecentRivalBidShiftMoreThanOldRows() {
        val shifted =
            view.copy(
                pastTokenPurchases =
                    List(3) { players.associateWith { 21 } } + players.associateWith { 20 }
            )

        assertEquals(
            "buy-tokens 19",
            InitiativePolicy.bid(
                shifted,
                seat,
                "buy-tokens 21",
                7,
                config.copy(tokenPenalty = 1.5, historyDecay = .5),
            ),
        )
    }

    @Test
    fun mixesSimilarBidsWithoutGivingUpAClearOrderAdvantage() {
        val mixed = config.copy(tokenPenalty = .15, bidScoreTolerance = .2)
        val bids = (1..128).map { InitiativePolicy.bid(view, seat, "buy-tokens 21", it, mixed) }

        assertEquals(setOf("buy-tokens 19", "buy-tokens 20"), bids.toSet())
    }

    @Test
    fun openingMixUsesOnlyBidsWithinItsDeclaredSpread() {
        val mixed = config.copy(openingSpread = 1)
        val opening = view.copy(pastTokenPurchases = emptyList())
        val bids = (1..128).map { InitiativePolicy.bid(opening, seat, "buy-tokens 21", it, mixed) }

        assertEquals(setOf("buy-tokens 20", "buy-tokens 21"), bids.toSet())
    }

    @Test
    fun firstTurnUsesLegalBidsForItsCapitalPriority() {
        val opening = view.copy(turnNumber = 1, pastTokenPurchases = emptyList())
        val tested = config.copy(openingMix = OpeningBidMix(listOf(17, 18), listOf(20)))
        val earlier = InitiativePolicy.bid(opening, seat, "buy-tokens 21", 7, tested)
        val later =
            InitiativePolicy.bid(
                opening.copy(capitalOrder = players.reversed()),
                seat,
                "buy-tokens 21",
                7,
                tested,
            )

        assertEquals("buy-tokens 18", earlier)
        assertEquals("buy-tokens 20", later)
    }

    @Test
    fun anEarlierCapitalMixPreservesEveryRegularLaterCapitalBid() {
        val opening =
            view.copy(
                turnNumber = 1,
                capitalOrder = players.reversed(),
                pastTokenPurchases = emptyList(),
            )
        val ordinary = config.copy(openingSpread = 2)
        val earlyMix =
            ordinary.copy(
                openingMix = OpeningBidMix(listOf(16, 17), retainOrdinaryForLaterCapital = true)
            )
        val seeds = 1..32

        assertEquals(
            seeds.map { InitiativePolicy.bid(opening, seat, "buy-tokens 21", it, ordinary) },
            seeds.map { InitiativePolicy.bid(opening, seat, "buy-tokens 21", it, earlyMix) },
        )
    }

    @Test
    fun openingMixCanShareProbabilityWithTheRegularOpeningBids() {
        val opening =
            view.copy(
                turnNumber = 1,
                pastTokenPurchases = emptyList(),
                tokenPurchaseOffers =
                    (16..21).map { amount ->
                        TokenPurchaseOfferView(
                            amount,
                            amount,
                            amount,
                            40 - amount,
                            36 - amount,
                            amount,
                        )
                    },
            )
        val tested =
            config.copy(
                openingSpread = 2,
                openingMix = OpeningBidMix(listOf(16, 17), participation = .4),
            )

        val bids = (1..128).map { InitiativePolicy.bid(opening, seat, "buy-tokens 21", it, tested) }

        assertEquals(setOf(16, 17, 19, 20, 21).map { "buy-tokens $it" }.toSet(), bids.toSet())
    }

    @Test
    fun absoluteOpeningBidsDoNotApplyOnLaterTurnsWithoutHistory() {
        val oldSave = view.copy(pastTokenPurchases = emptyList())
        val tested = config.copy(openingMix = OpeningBidMix(listOf(18)))

        assertEquals(
            "buy-tokens 21",
            InitiativePolicy.bid(oldSave, seat, "buy-tokens 21", 7, tested),
        )
    }

    @Test
    fun buysOneMoreTokenWhenItUnlocksTheBetterExpansionPlanAtTheSameOrder() {
        val markets =
            state.catalog.provinces.zip(listOf(5, 5, 6, 6)).map { (province, cost) ->
                PeacefulExpansionProspectView(
                    province.id,
                    cost,
                    shipping = false,
                    usesOverseasSlot = false,
                )
            }
        val extended =
            view.copy(
                pastTokenPurchases = listOf(players.associateWith { 20 }),
                tokenPurchaseOffers =
                    view.tokenPurchaseOffers + TokenPurchaseOfferView(22, 22, 22, 18, 14, 22),
                expansionForecast = ExpansionForecastView(markets, overseasSlots = 0),
            )
        val budget =
            config.copy(
                maximumTokenLoss = 0,
                maximumExtraTokens = 1,
                tokenPenalty = 0.0,
                planWeight = 0.1,
            )

        assertEquals(
            "buy-tokens 22",
            InitiativePolicy.bid(extended, seat, "buy-tokens 21", 7, budget),
        )
    }

    @Test
    fun forecastsTheNextBidOfARepeatUndercutter() {
        val bidder = players[1]
        val history =
            responseHistory(bidder, listOf(21, 21, 19, 19, 19), listOf(21, 21, 20, 20, 18))
        val rivals = view.players.filter { it.id != seat }

        assertEquals(mapOf(bidder to 18), BidResponseForecast.next(history, seat, rivals))
    }

    @Test
    fun recognizesWhenARivalTracksTheMostRecentBid() {
        val bidder = players[1]
        val history =
            responseHistory(bidder, listOf(21, 21, 20, 19, 20), listOf(21, 21, 20, 19, 18))
        val rivals = view.players.filter { it.id != seat }

        assertEquals(mapOf(bidder to 19), BidResponseForecast.next(history, seat, rivals))
    }

    private fun responseHistory(bidder: PlayerId, earlier: List<Int>, counters: List<Int>) =
        earlier.zip(counters).map { (own, counter) ->
            players.associateWith { player ->
                when (player) {
                    seat -> own
                    bidder -> counter
                    else -> 21
                }
            }
        }

    @Test
    fun respectsTheContestedPoolRequirement() {
        val forecast =
            ExpansionForecastView(
                peacefulMarkets = emptyList(),
                overseasSlots = 0,
                contestedMarkets =
                    listOf(
                        ContestedExpansionProspectView(
                            ProvinceId(state.catalog.capitals.first()),
                            21,
                            false,
                            false,
                        )
                    ),
            )
        val constrained = view.copy(expansionForecast = forecast)
        assertEquals(
            "buy-tokens 21",
            InitiativePolicy.bid(
                constrained,
                seat,
                "buy-tokens 21",
                7,
                config.copy(requireAffordableContest = true),
            ),
        )
    }

    @Test
    fun ignoresSealedChoicesWhenHistoryIsEmpty() {
        val empty =
            view.copy(
                pastTokenPurchases = emptyList(),
                revealedTokenPurchases = players.associateWith { 21 },
            )

        assertEquals("buy-tokens 21", InitiativePolicy.bid(empty, seat, "buy-tokens 21", 7, config))
    }
}
