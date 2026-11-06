package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityCardCountView
import dev.aragos.aor.ExpansionForecastView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.HandCard
import dev.aragos.aor.PeacefulExpansionProspectView
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import kotlin.test.Test

class BotTokenPlanProjectionTest {
    private val p1 = PlayerId("p1")
    private val ids = (1..4).map { PlayerId("p$it") }

    @Test
    fun tokenDecisionViewsExposePublicCommodityFacts() {
        val state = fixture("wool-1")

        val view = state.viewForDecision(p1)

        assertThat(view.commodityPayouts).isNotEmpty()
        assertThat(view.commodityCards).isNotEmpty()
        assertThat(view.commodityPayouts).isEqualTo(state.viewForPlayer(p1).commodityPayouts)
        assertThat(view.commodityCards).isEqualTo(state.viewForPlayer(p1).commodityCards)
    }

    @Test
    fun jointMarketGainsAndOutsideExposureBelongToOnePlan() {
        val original = fixture("wool-1").viewForDecision(p1)
        val destinations = markets(original, Commodity.WOOL, 2)
        val view =
            original.copy(
                expansionForecast = forecast(destinations.map { prospect(it, 2) }),
                commodityCards = listOf(CommodityCardCountView(Commodity.WOOL, listOf(3, 0, 0), 3)),
            )
        val projection = BotTokenPlanProjection(view, p1, 0.5)
        val quote = view.tokenPurchaseOffers.single { it.amount == 4 }

        val plan = projection.plan(quote.poolAfter)
        val features = projection.features(quote)

        assertThat(plan.destinations).containsExactlyElementsIn(destinations)
        assertThat(plan.heldGain).isEqualTo(8.0)
        assertThat(plan.publicGain).isEqualTo(16.0)
        assertThat(plan.incomeGain).isEqualTo(8)
        assertThat(plan.commitment).isEqualTo(4)
        assertThat(features).containsExactly(0.08, 0.08, 0.08, 0.04, 0.0, 0.0, 0.0).inOrder()
    }

    @Test
    fun oneSharedChoiceCardPaysOnlyItsBestCommodityAfterTheWholePlan() {
        val original = fixture("cloth-wine").viewForDecision(p1)
        val destinations =
            markets(original, Commodity.CLOTH, 1) + markets(original, Commodity.WINE, 1)
        val view = original.copy(expansionForecast = forecast(destinations.map { prospect(it, 2) }))

        val plan = BotTokenPlanProjection(view, p1, 0.0).plan(4)

        // Venice supplies the initial cloth market: 5 before, max(20 cloth, 5 wine) after.
        assertThat(plan.heldGain).isEqualTo(15.0)
        assertThat(plan.destinations).containsExactlyElementsIn(destinations)
    }

    @Test
    fun aPlanCannotSpendMoreMarkersThanTheOwnerHas() {
        val original = fixture("wool-1").viewForDecision(p1)
        val destinations = markets(original, Commodity.WOOL, 2)
        val view =
            original.copy(
                players =
                    original.players.map { if (it.id == p1) it.copy(boardMarkers = 24) else it },
                expansionForecast = forecast(destinations.map { prospect(it, 2) }),
            )

        val plan = BotTokenPlanProjection(view, p1, 0.0).plan(10)

        assertThat(plan.destinations).hasSize(1)
        assertThat(plan.commitment).isEqualTo(2)
        assertThat(plan.incomeGain).isEqualTo(4)
    }

    @Test
    fun aPlanSharesOverseasSlotsAcrossAllDestinations() {
        val original = fixture("wool-1").viewForDecision(p1)
        val view =
            original.copy(
                expansionForecast =
                    forecast(
                        listOf(
                            prospect(ProvinceId("india"), 4, overseas = true),
                            prospect(ProvinceId("china"), 4, overseas = true),
                        ),
                        slots = 1,
                    )
            )

        val plan = BotTokenPlanProjection(view, p1, 0.0).plan(10)

        assertThat(plan.destinations).hasSize(1)
        assertThat(plan.overseasSlots).isEqualTo(1)
        assertThat(plan.commitment).isEqualTo(4)
    }

    @Test
    fun repeatedProspectsNeverSpendASecondMarkerOnTheSameDestination() {
        val original = fixture("wool-1").viewForDecision(p1)
        val destination = markets(original, Commodity.WOOL, 1).single()
        val view = original.copy(expansionForecast = forecast(List(2) { prospect(destination, 2) }))

        val plan = BotTokenPlanProjection(view, p1, 0.0).plan(10)

        assertThat(plan.destinations).containsExactly(destination)
        assertThat(plan.heldGain).isEqualTo(2.0)
    }

    @Test
    fun unaffordableMarketsLeaveTheEmptyPlanAndUncommittedTokens() {
        val original = fixture("wool-1").viewForDecision(p1)
        val destination = markets(original, Commodity.WOOL, 1).single()
        val view = original.copy(expansionForecast = forecast(listOf(prospect(destination, 5))))
        val projection = BotTokenPlanProjection(view, p1, 0.0)
        val quote = view.tokenPurchaseOffers.single { it.amount == 4 }

        val plan = projection.plan(4)
        val features = projection.features(quote)

        assertThat(plan).isEqualTo(BotTokenPlan())
        assertThat(features.last()).isEqualTo(4.0 / 32)
    }

    @Test
    fun opportunityNeverFallsWhenAnOfferedTokenBudgetIncreases() {
        val original = fixture("wool-1", "cloth-wine").viewForDecision(p1)
        val destinations =
            markets(original, Commodity.WOOL, 2) +
                markets(original, Commodity.CLOTH, 2) +
                markets(original, Commodity.WINE, 2)
        val costs = listOf(2, 5, 3, 7, 4, 6)
        val view =
            original.copy(
                expansionForecast =
                    forecast(destinations.zip(costs).map { (id, cost) -> prospect(id, cost) })
            )
        val projection = BotTokenPlanProjection(view, p1, 0.0)
        val budgets = view.tokenPurchaseOffers.map { it.poolAfter }.distinct().sorted()

        val plans = budgets.map(projection::plan)

        assertThat(
                plans.zipWithNext().all { (before, after) ->
                    after.opportunity >= before.opportunity
                }
            )
            .isTrue()
        assertThat(plans.zip(budgets).all { (plan, budget) -> plan.commitment <= budget }).isTrue()
        assertThat(plans.all { it.destinations.size == it.destinations.distinct().size }).isTrue()
    }

    @Test
    fun aLargerBudgetKeepsTheBetterPortfolioSkippedByItsGreedyPackings() {
        val original = fixture("wool-1", "cloth-wine").viewForDecision(p1)
        val wool = markets(original, Commodity.WOOL, 3)
        val wine = markets(original, Commodity.WINE, 2)
        val timber = markets(original, Commodity.TIMBER, 2)
        val view =
            original.copy(
                players =
                    original.players.map { if (it.id == p1) it.copy(boardMarkers = 22) else it },
                commodityCards =
                    listOf(
                        CommodityCardCountView(Commodity.WOOL, listOf(3, 0, 0), 3),
                        CommodityCardCountView(Commodity.TIMBER, listOf(1, 0, 0), 1),
                    ),
                expansionForecast =
                    forecast(
                        wool.map { prospect(it, 5) } +
                            wine.zip(listOf(1, 2)).map { (id, cost) -> prospect(id, cost) } +
                            timber.zip(listOf(3, 4)).map { (id, cost) -> prospect(id, cost) }
                    ),
            )
        val projection = BotTokenPlanProjection(view, p1, 0.0)

        val smaller = projection.plan(9)
        val larger = projection.plan(10)

        // At ten, the held-first packing takes two wool markets; the other orders use timber.
        // Neither rediscovers the better eight-token wool + two-wine portfolio from smaller pools.
        assertThat(smaller.destinations).containsAtLeastElementsIn(wine)
        assertThat(smaller.commitment).isEqualTo(8)
        assertThat(larger).isEqualTo(smaller)
    }

    @Test
    fun interestUsesTheSamePlanIncomeAndOnlyTheTokenQuoteCash() {
        val original = fixture("wool-1", interest = true).viewForDecision(p1)
        val destination = markets(original, Commodity.WOOL, 1).single()
        val view = original.copy(expansionForecast = forecast(listOf(prospect(destination, 3))))
        val quote = view.tokenPurchaseOffers.single { it.amount == 5 }

        val features = BotTokenPlanProjection(view, p1, 0.0).features(quote)

        // Cash 95 remains above the projected income cap, which rises from 19 to 23.
        assertThat(features[BotTokenPlanFeature.INTEREST.ordinal]).isEqualTo(0.04)
    }

    @Test
    fun stabilizationDeficitUsesCurrentHandWithoutInventingFutureReceipts() {
        val view = fixture("wool-1", "wool-2", "cloth-wine", cash = 10).viewForDecision(p1)
        val quote = view.tokenPurchaseOffers.single { it.amount == 8 }

        val features = BotTokenPlanProjection(view, p1, 0.0).features(quote)

        // Current three-card stabilization costs six; token spending leaves two cash.
        assertThat(features[BotTokenPlanFeature.STABILIZATION_SHORTFALL.ordinal]).isEqualTo(0.04)
    }

    private fun markets(view: GameView, commodity: Commodity, count: Int): List<ProvinceId> {
        val occupied = view.board.map { it.id }.toSet()
        val matches =
            checkNotNull(view.catalog) { "The fixture requires the public map." }
                .provinces
                .filter {
                    !it.isSatellite && it.id !in occupied && it.commodities == setOf(commodity)
                }
                .map { it.id }
                .take(count)
        check(matches.size == count) {
            "The fixture requires $count unoccupied $commodity markets."
        }
        return matches
    }

    private fun forecast(
        markets: List<PeacefulExpansionProspectView>,
        slots: Int = 0,
    ): ExpansionForecastView = ExpansionForecastView(markets, slots)

    private fun prospect(
        destination: ProvinceId,
        tokens: Int,
        overseas: Boolean = false,
    ): PeacefulExpansionProspectView =
        PeacefulExpansionProspectView(destination, tokens, overseas, overseas)

    private fun fixture(
        vararg cards: String,
        cash: Int = 100,
        interest: Boolean = false,
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.TOKEN_PURCHASE,
            turnNumber = 2,
            board =
                ids.zip(capitals).fold(BoardState()) { board, (id, capital) ->
                    board.placeMarker(ProvinceId(capital), id).board
                },
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = cash,
                                hand =
                                    if (id == p1)
                                        cards.mapIndexed { cardIndex, card ->
                                            HandCard("held-$cardIndex", CardData.definition(card))
                                        }
                                    else emptyList(),
                                advances =
                                    PlayerAdvances(
                                        if (interest && id == p1)
                                            listOf(AdvanceAcquisition(AdvanceId.INTEREST_PROFIT, 1))
                                        else emptyList()
                                    ),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
