package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class VisualProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val florence = ProvinceId("florence")

    @Test
    fun markerQuoteAgreesWithExecutionAndDoesNotChangeTheSource() {
        val state = state()
        val offer = offer(state)
        val placed =
            state.step(
                Entry.Action(p1, TextCodec.parseCommand("place ${offer.requiredTokens} florence"))
            )

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.PLACE)
        assertThat(offer.requiredTokens).isEqualTo(4)
        assertThat(offer.resultingPool).isEqualTo(placed.playerState(p1).poolTokens)
        assertThat(offer.incomeAfter)
            .isEqualTo(placed.viewForPlayer(p1).players.first().income?.ordinary)
        assertThat(state.board.pieces).isEmpty()
        assertThat(state.playerState(p1).poolTokens).isEqualTo(20)
        assertThat(placed.board.pieces[florence])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
    }

    @Test
    fun partialOccupationReducesRequiredNewTokens() {
        val board =
            BoardState(
                persistentMapOf(
                    florence to
                        ProvincePieces.Tokens(persistentMapOf(p1 to TokenHolding(coloured = 2)))
                )
            )
        val offer = offer(state(board))

        assertThat(offer.requiredTokens).isEqualTo(2)
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1)
    }

    @Test
    fun insufficientPoolBlocksMarkerButKeepsPartialPlacement() {
        val offer = offer(state(pool = 2))

        assertThat(offer.blockers).containsExactly(ViolationCode.INSUFFICIENT_EXPANSION_TOKENS)
        assertThat(offer.resultingPool).isNull()
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1, 2)
    }

    @Test
    fun contestedMarketUsesExactAdditionalCommitmentWithoutResolvingCompetition() {
        val board =
            BoardState(
                persistentMapOf(
                    florence to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )
        val state = state(board)
        val offer = offer(state)
        val pending = state.step(Entry.Action(p1, TextCodec.parseCommand("attack florence")))

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.ATTACK)
        assertThat(offer.requiredTokens).isEqualTo(4)
        assertThat(offer.resultingPool).isEqualTo(pending.playerState(p1).poolTokens)
        assertThat(offer.incomeAfter).isNull()
        assertThat(offer.uncertain).isTrue()
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1, 2)
        assertThat(state.flow.decisions).isEmpty()
        assertThat(pending.flow.decisions).isNotEmpty()
    }

    @Test
    fun friendlyWhiteMarkerIsInspectionOnly() {
        val offer = offer(state(BoardState().placeMarker(florence, p1, PieceSide.WHITE).board))

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.INSPECT)
        assertThat(offer.partialPlacements).isEmpty()
    }

    @Test
    fun otherPlayerGetsNoExpansionActions() {
        assertThat(state().viewForPlayer(p2).expansionOffers).isEmpty()
    }

    @Test
    fun accessRequirementsAreTypedBlockers() {
        val offer =
            state().viewForPlayer(p1).expansionOffers.single {
                it.destination == ProvinceId("china")
            }

        assertThat(offer.blockers).containsExactly(ViolationCode.PROVINCE_ACCESS_DENIED)
    }

    @Test
    fun whiteMarkersDoNotQualifyForCurrentCommodityPayment() {
        val board = BoardState().placeMarker(florence, p1, PieceSide.WHITE).board
        val view = state(board).viewForPlayer(p1)
        val commodities = ProvinceData.definition(florence).commodities

        assertThat(
                view.commodityPayouts
                    .filter { it.commodity in commodities }
                    .flatMap(CommodityPayoutView::owners)
            )
            .isEmpty()
        assertThat(view.players.first().income?.ordinary)
            .isGreaterThan(state().viewForPlayer(p1).players.first().income?.ordinary ?: -1)
    }

    @Test
    fun zeroPayingOwnersRemainAndOnlyOneQueuedAdjustmentApplies() {
        val commodity = ProvinceData.definition(florence).commodities.first()
        val board = BoardState().placeMarker(florence, p1).board
        val state =
            state(board)
                .copy(
                    adjustments =
                        mapOf(
                            commodity to
                                listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS)
                        )
                )
        val payout = state.viewForPlayer(p1).commodityPayouts.single { it.commodity == commodity }

        assertThat(payout.owners).hasSize(1)
        assertThat(payout.owners.single().payout).isEqualTo(0)
        assertThat(payout.owners.single().marketCount).isEqualTo(1)
        assertThat(payout.adjustments).hasSize(2)
    }

    @Test
    fun industryAndFirstShortageAgreeWithTheActualPayment() {
        val commodity = ProvinceData.definition(florence).commodities.first()
        val board = BoardState().placeMarker(florence, p1).board
        val state =
            state(board, advances = setOf(AdvanceId.INDUSTRY))
                .copy(
                    adjustments =
                        mapOf(
                            commodity to
                                listOf(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SHORTAGE)
                        )
                )
        val owner =
            state
                .viewForPlayer(p1)
                .commodityPayouts
                .single { it.commodity == commodity }
                .owners
                .single()

        assertThat(owner.effectiveBox).isEqualTo(3)
        assertThat(owner.payout)
            .isEqualTo(CardRules.commodityPayment(state.cardContext(), commodity).payments[p1])
    }

    @Test
    fun incomeSeparatesConditionalProfitFromOrdinaryIncome() {
        val state = state(advances = setOf(AdvanceId.MIDDLE_CLASS, AdvanceId.INTEREST_PROFIT))
        val player = state.playerState(p1)
        val quote = PlayerEconomy.incomeQuote(player, 3, 0)
        val income = state.viewForPlayer(p1).players.first().income

        assertThat(income?.ordinary).isEqualTo(quote.income)
        assertThat(income?.middleClassBonus).isEqualTo(10)
        assertThat(income?.interestProfit).isEqualTo(quote.interestProfit)
    }

    @Test
    fun setupIncomeIsExplicitlyUnavailable() {
        val setup = GameState(state().header)

        assertThat(setup.viewForPlayer(p1).players.map(PlayerView::income))
            .containsExactly(null, null, null)
    }

    @Test
    fun chaosIncomeIsExplicitlyUnavailable() {
        val state = state()
        val chaos =
            state.copy(
                players =
                    state.players + (p2 to state.playerState(p2).copy(misery = MiseryStep.CHAOS))
            )

        assertThat(chaos.viewForPlayer(p1).players.single { it.id == p2 }.income).isNull()
    }

    @Test
    fun potentialLeaderDiscountDoesNotReduceAnUnplayedAdvanceOffer() {
        val state = withHand(state(advances = setOf(AdvanceId.WRITTEN_RECORD)), "charlemagne")
        val view = state.viewForPlayer(p1)
        val discount = view.cardOffers.single().leaderDiscounts.single()
        val advance = view.advanceOffers.single { it.advance == AdvanceId.NATIONALISM }

        assertThat(discount.printedAmount).isEqualTo(20)
        assertThat(discount.effectiveDiscount).isEqualTo(30)
        assertThat(advance.price)
            .isEqualTo(
                AdvancePurchases.quote(
                        state.playerState(p1),
                        AdvanceId.NATIONALISM,
                        state.purchaseContext(p1),
                    )
                    .price
            )
        assertThat(view.cardOffers.single().playable).isFalse()
    }

    @Test
    fun dualCommodityCardOffersBothPrintedCommodities() {
        val view = withHand(state().copy(stage = Stage.PLAY_CARDS), "cloth-wine").viewForPlayer(p1)

        assertThat(view.cardOffers.single().commodities)
            .containsExactly(Commodity.CLOTH, Commodity.WINE)
        assertThat(view.cardOffers.single().choices.map(CardChoiceView::kind))
            .containsExactly(CardChoiceKind.COMMODITY, CardChoiceKind.COMMODITY)
        assertThat(view.cardOffers.single().playable).isTrue()
    }

    @Test
    fun anotherPlayerCannotUseHandChoicesOutsideTheirDecision() {
        val state =
            withHand(
                state().copy(stage = Stage.PLAY_CARDS, flow = TurnFlow(actor = p2)),
                "cloth-wine",
            )
        val offer = state.viewForPlayer(p1).cardOffers.single()

        assertThat(offer.choices).isEmpty()
        assertThat(offer.blockers).containsExactly(CardOfferBlocker.WRONG_PLAYER)
    }

    @Test
    fun arbitraryOverStockTokenPurchaseUsesFullCostWithoutEnumeratingCash() {
        val state = state().copy(stage = Stage.TOKEN_PURCHASE)
        val buyer = state.playerState(p1).copy(cash = 1_000_000)
        val rich = state.copy(players = state.players + (p1 to buyer))
        val quote = TokenPurchaseProjection.quote(rich, p1, 500_000)

        assertThat(quote.cost).isEqualTo(500_000)
        assertThat(quote.tokensReceived).isEqualTo(buyer.stockTokens)
        assertThat(quote.cashAfter).isEqualTo(500_000)
        assertThat(rich.viewForPlayer(p1).tokenPurchaseOffers.size).isAtMost(75)
    }

    @Test
    fun negativeTokenPurchaseSpendsCashWithoutReceivingTokens() {
        val quote =
            TokenPurchaseProjection.quote(state().copy(stage = Stage.TOKEN_PURCHASE), p1, -7)

        assertThat(quote.cost).isEqualTo(7)
        assertThat(quote.tokensReceived).isEqualTo(0)
        assertThat(quote.poolAfter).isEqualTo(20)
    }

    @Test
    fun tokenQuoteRejectsWrongPhase() {
        val failure =
            assertFailsWith<RuleViolation> { TokenPurchaseProjection.quote(state(), p1, 1) }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    private fun offer(state: GameState): ExpansionOfferView =
        state.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

    private fun withHand(state: GameState, card: String): GameState =
        state.copy(
            players =
                state.players +
                    (p1 to
                        state
                            .playerState(p1)
                            .copy(hand = listOf(HandCard("hand-1", CardData.definition(card)))))
        )

    private fun state(
        board: BoardState = BoardState(),
        pool: Int = 20,
        advances: Set<AdvanceId> = emptySet(),
    ): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 100,
                                poolTokens = if (player.id == p1) pool else 0,
                                stockTokens =
                                    36 -
                                        (if (player.id == p1) pool else 0) -
                                        board.tokenCount(player.id),
                                advances =
                                    PlayerAdvances(
                                        (if (player.id == p1) advances else emptySet()).map {
                                            AdvanceAcquisition(it, 1)
                                        }
                                    ),
                            )
                    }
                    .toMap(),
        )
    }
}
