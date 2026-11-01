package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf

class LiveCommodityForecastTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val florence = ProvinceId("florence")

    @Test
    fun newGameListsEveryCommodityBeforeCapitalsAndTurnsExist() {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val initial = GameState(header = GameHeader(players, p1))

        val payouts = initial.viewForPlayer(p1).commodityPayouts

        assertThat(payouts.map(CommodityPayoutView::commodity))
            .containsExactlyElementsIn(Commodity.entries)
            .inOrder()
        assertThat(payouts.flatMap(CommodityPayoutView::owners)).isEmpty()
        assertThat(payouts.flatMap(CommodityPayoutView::adjustments)).isEmpty()
    }

    @Test
    fun winningCompetitionImmediatelyReplacesTheForecastOwnerBeforeIncome() {
        val before = state(BoardState().placeMarker(florence, p2).board)

        val after =
            before
                .step(TextCodec.parse("p1: attack florence"))
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
                    )
                )
        val owner =
            after
                .viewForPlayer(p3)
                .commodityPayouts
                .single { it.commodity == Commodity.CLOTH }
                .owners
                .single()

        assertThat(after.stage).isEqualTo(Stage.EXPANSION)
        assertThat(after.board.pieces[florence])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(owner.player).isEqualTo(p1)
        assertThat(owner.provinces).containsExactly(florence)
        assertThat(owner.marketCount).isEqualTo(1)
        assertThat(owner.payout).isEqualTo(5)
        assertThat(
                before
                    .viewForPlayer(p3)
                    .commodityPayouts
                    .single { it.commodity == Commodity.CLOTH }
                    .owners
                    .single()
                    .player
            )
            .isEqualTo(p2)
    }

    @Test
    fun losingCompetitionPreservesTheDefendersForecast() {
        val before = state(BoardState().placeMarker(florence, p2).board)

        val after =
            before
                .step(TextCodec.parse("p1: attack florence"))
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(6), DieRoll(1), DieRoll(1)),
                    )
                )

        assertThat(after.viewForPlayer(p3).commodityPayouts)
            .isEqualTo(before.viewForPlayer(p3).commodityPayouts)
    }

    @Test
    fun reclaimingAWhiteMarkerCreditsOnlyItsCurrentOwner() {
        val before = state(BoardState().placeMarker(florence, p2).board)
        val occupied =
            before
                .step(TextCodec.parse("p1: attack florence"))
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
                    )
                )
                .step(TextCodec.parse("p1: pass"))

        val after =
            occupied
                .step(TextCodec.parse("p2: attack florence"))
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
                    )
                )
        val owner =
            after
                .viewForPlayer(p3)
                .commodityPayouts
                .single { it.commodity == Commodity.CLOTH }
                .owners
                .single()

        assertThat(after.stage).isEqualTo(Stage.EXPANSION)
        assertThat(after.board.pieces[florence])
            .isEqualTo(ProvincePieces.Marker(p2, PieceSide.WHITE))
        assertThat(owner.player).isEqualTo(p2)
        assertThat(owner.provinces).containsExactly(florence)
        assertThat(owner.payout).isEqualTo(5)
    }

    @Test
    fun liveValueCombinesWhiteAndColouredMarkersWithIndustryAndOnlyTheFirstAdjustment() {
        val second =
            CatalogConfiguration(3)
                .provinces
                .first { Commodity.CLOTH in it.commodities && it.id != florence }
                .id
        val board =
            BoardState()
                .placeMarker(florence, p1)
                .board
                .placeMarker(second, p1, PieceSide.WHITE)
                .board
        val before =
            state(board, industry = setOf(p1))
                .copy(
                    adjustments =
                        mapOf(
                            Commodity.CLOTH to
                                listOf(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SURPLUS)
                        )
                )

        val forecast =
            before.viewForPlayer(p3).commodityPayouts.single { it.commodity == Commodity.CLOTH }
        val owner = forecast.owners.single()

        assertThat(owner.marketCount).isEqualTo(2)
        assertThat(owner.industryBonus).isEqualTo(1)
        assertThat(owner.effectiveBox).isEqualTo(4)
        assertThat(owner.payout).isEqualTo(80)
        assertThat(forecast.adjustments)
            .containsExactly(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SURPLUS)
            .inOrder()
        assertThat(CardRules.commodityPayment(before.cardContext(), Commodity.CLOTH).payments[p1])
            .isEqualTo(45)
    }

    @Test
    fun surplusKeepsAZeroPayingWhiteOwnerVisibleWithoutGivingIndustryToNonOwners() {
        val before =
            state(
                    BoardState().placeMarker(florence, p1, PieceSide.WHITE).board,
                    industry = setOf(p2),
                )
                .copy(
                    adjustments =
                        mapOf(
                            Commodity.CLOTH to
                                listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SHORTAGE)
                        )
                )

        val owner =
            before
                .viewForPlayer(p3)
                .commodityPayouts
                .single { it.commodity == Commodity.CLOTH }
                .owners
                .single()

        assertThat(owner.player).isEqualTo(p1)
        assertThat(owner.marketCount).isEqualTo(1)
        assertThat(owner.effectiveBox).isEqualTo(0)
        assertThat(owner.payout).isEqualTo(0)
    }

    @Test
    fun filledSquareTokensDoNotCountAsLiveCommodityControl() {
        val second =
            CatalogConfiguration(3).provinces.first {
                Commodity.CLOTH in it.commodities && it.id != florence
            }
        val markers = BoardState().placeMarker(florence, p1, PieceSide.WHITE).board
        val board =
            markers.copy(
                pieces =
                    markers.pieces.putting(
                        second.id,
                        ProvincePieces.Tokens(
                            persistentMapOf(p2 to TokenHolding(coloured = second.marketNumber))
                        ),
                    )
            )

        val owners =
            state(board)
                .viewForPlayer(p3)
                .commodityPayouts
                .single { it.commodity == Commodity.CLOTH }
                .owners

        assertThat(owners.map(CommodityOwnerView::player)).containsExactly(p1)
    }

    @Test
    fun aChaosPlayersMarkersDoNotEarnForecastPayments() {
        val initial = state(BoardState().placeMarker(florence, p1, PieceSide.WHITE).board)
        val before =
            initial.copy(
                players =
                    initial.players +
                        (p1 to initial.playerState(p1).copy(misery = MiseryStep.CHAOS))
            )

        val owners =
            before
                .viewForPlayer(p3)
                .commodityPayouts
                .single { it.commodity == Commodity.CLOTH }
                .owners

        assertThat(owners).isEmpty()
    }

    @Test
    fun whiteForecastControlDoesNotChangeActualCardPayments() {
        val initial = state(BoardState().placeMarker(florence, p1, PieceSide.WHITE).board)
        val before =
            initial.copy(
                stage = Stage.PLAY_CARDS,
                players =
                    initial.players +
                        (p1 to
                            initial
                                .playerState(p1)
                                .copy(
                                    hand =
                                        listOf(HandCard("cloth", CardData.definition("cloth-wine")))
                                )),
            )

        val paid = CardGame.play(before, p1, "cloth", CardChoice.CommodityChoice(Commodity.CLOTH))

        assertThat(
                before
                    .viewForPlayer(p1)
                    .commodityPayouts
                    .single { it.commodity == Commodity.CLOTH }
                    .owners
                    .single()
                    .payout
            )
            .isEqualTo(5)
        assertThat(paid.playerState(p1).cash).isEqualTo(before.playerState(p1).cash)
    }

    @Test
    fun whiteGrainDoesNotReduceActualFamineMisery() {
        val grain = CatalogConfiguration(3).provinces.first { Commodity.GRAIN in it.commodities }.id
        val initial = state(BoardState().placeMarker(grain, p1, PieceSide.WHITE).board)
        val before =
            initial.copy(
                stage = Stage.PLAY_CARDS,
                players =
                    initial.players +
                        (p1 to
                            initial
                                .playerState(p1)
                                .copy(
                                    hand = listOf(HandCard("famine", CardData.definition("famine")))
                                )),
            )

        val played = CardGame.play(before, p1, "famine", CardChoice.None)

        assertThat(played.playerState(p1).misery).isEqualTo(MiseryStep.FORTY)
        assertThat(
                before
                    .viewForPlayer(p1)
                    .commodityPayouts
                    .single { it.commodity == Commodity.GRAIN }
                    .owners
                    .single()
                    .marketCount
            )
            .isEqualTo(1)
    }

    private fun state(board: BoardState, industry: Set<PlayerId> = emptySet()): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 1,
            board = board,
            deck = listOf("stone-1", "stone-2"),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        val pool = if (player.id == p1) 20 else 10
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = pool,
                                stockTokens = 36 - pool - board.tokenCount(player.id),
                                advances =
                                    if (player.id in industry)
                                        PlayerAdvances(
                                            listOf(AdvanceAcquisition(AdvanceId.INDUSTRY, 1))
                                        )
                                    else PlayerAdvances(),
                            )
                    }
                    .toMap(),
        )
    }
}
