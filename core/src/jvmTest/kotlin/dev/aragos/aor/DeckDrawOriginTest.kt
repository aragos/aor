package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/** Every action that earns a card retains its own bookkeeping through an epoch shuffle. */
class DeckDrawOriginTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun regularDrawResumesTheSamePlayersTurnAtEitherBoundary() {
        for (epoch in listOf(Epoch.ONE, Epoch.TWO)) {
            val waiting =
                game(epoch, Stage.DRAW_CARD)
                    .copy(flow = TurnFlow(actor = p1, phaseStarted = true, cancelComplete = true))
                    .normalise()

            val drawn = waiting.shuffleAndDraw()

            assertThat(drawn.currentEpoch).isEqualTo(epoch.next)
            assertThat(drawn.playerState(p1).hand).hasSize(1)
            assertThat(drawn.flow.drawn).contains(p1)
            assertThat(drawn.pendingDraw()?.player).isEqualTo(p2)
            assertThat(drawn.endingTriggered).isFalse()
        }
    }

    @Test
    fun urbanAscendancyChargesOnceAndRetainsTheOptionalDiscardAtEitherBoundary() {
        for (epoch in listOf(Epoch.ONE, Epoch.TWO)) {
            val initial =
                game(epoch, Stage.BUY_CARD).updatePlayer(p1) { owner ->
                    owner.copy(
                        hand = listOf(HandCard("held", CardData.definition("stone-1"))),
                        advances =
                            PlayerAdvances(
                                listOf(
                                    AdvanceAcquisition(AdvanceId.URBAN_ASCENDANCY, 1),
                                    AdvanceAcquisition(AdvanceId.MASTER_ART, 1),
                                )
                            ),
                    )
                }
            val waiting = initial.step(Entry.Action(p1, TurnCommand.BuyCard))

            val drawn = waiting.shuffleAndDraw()

            assertThat(drawn.currentEpoch).isEqualTo(epoch.next)
            assertThat(drawn.playerState(p1).cash).isEqualTo(30)
            assertThat(drawn.playerState(p1).hand).hasSize(2)
            assertThat(drawn.flow.boughtCards).containsExactly(p1)
            assertThat(drawn.stage).isEqualTo(Stage.BUY_CARD)
            assertThat(drawn.flow.actor).isEqualTo(p1)
            assertThat(drawn.legalChoices(p1).commands).contains("discard-card held")
        }
    }

    @Test
    fun expansionCardRetainsItsPriceAndOuterActorAtEitherBoundary() {
        for (epoch in listOf(Epoch.ONE, Epoch.TWO)) {
            val initial = game(epoch, Stage.EXPANSION)
            val waiting = initial.step(TextCodec.parse("p1: buy-expansion-card"))

            val drawn = waiting.shuffleAndDraw()

            assertThat(drawn.currentEpoch).isEqualTo(epoch.next)
            assertThat(drawn.playerState(p1).poolTokens).isEqualTo(17)
            assertThat(drawn.playerState(p1).stockTokens).isEqualTo(19)
            assertThat(drawn.playerState(p1).hand).hasSize(1)
            assertThat(drawn.flow.expansionCardCount).isEqualTo(1)
            assertThat(drawn.flow.expansionCards).containsExactly(p1)
            assertThat(drawn.stage).isEqualTo(Stage.EXPANSION)
            assertThat(drawn.flow.actor).isEqualTo(p1)
        }
    }

    @Test
    fun expansionBonusResumesIncomeExactlyOnceAtEitherBoundary() {
        for (epoch in listOf(Epoch.ONE, Epoch.TWO)) {
            val initial =
                game(epoch, Stage.EXPANSION)
                    .copy(
                        board =
                            BoardState()
                                .placeMarker(ProvinceId("venice"), p1, PieceSide.WHITE)
                                .board,
                        flow = TurnFlow(actor = p3, cursor = 2, phaseStarted = true),
                    )
            val waiting = initial.step(TextCodec.parse("p3: pass"))

            val drawn = waiting.shuffleAndDraw()

            assertThat(drawn.currentEpoch).isEqualTo(epoch.next)
            assertThat(drawn.playerState(p1).hand).hasSize(1)
            assertThat(drawn.playerState(p1).cash).isEqualTo(58)
            assertThat(drawn.board.pieces[ProvinceId("venice")])
                .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
            assertThat(drawn.stage).isEqualTo(Stage.INCOME)
            assertThat(drawn.pendingRoll()?.kind).isEqualTo(GameRollKind.SHORTAGE)
        }
    }

    @Test
    fun doubleShortagePreservesBothEarnedCardsAtEitherBoundary() {
        for (epoch in listOf(Epoch.ONE, Epoch.TWO)) {
            val initial =
                game(epoch, Stage.INCOME)
                    .copy(
                        board = BoardState().placeMarker(ProvinceId("rome"), p1).board,
                        adjustments =
                            mapOf(Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE)),
                        flow =
                            TurnFlow(
                                actor = p1,
                                phaseStarted = true,
                                phaseBookkeepingApplied = true,
                                shortageRolls = 1,
                                decisions = listOf(ShortageDecision),
                            ),
                    )
            val waiting = initial.step(TextCodec.parse("!roll shortage 1 1 1"))
            val first = waiting.shuffleAndDraw()
            val secondNeed = requireNotNull(first.pendingDraw())

            val drawn =
                first.step(
                    GameOutcome.Draw(secondNeed.player, secondNeed.reference, secondNeed.card)
                )

            assertThat(drawn.currentEpoch).isEqualTo(epoch.next)
            assertThat(drawn.playerState(p1).hand).hasSize(2)
            assertThat(drawn.playerState(p1).hand.map(HandCard::reference)).containsNoDuplicates()
            assertThat(drawn.stage).isEqualTo(Stage.TOKEN_PURCHASE)
            assertThat(drawn.turnNumber).isEqualTo(2)
            assertThat(drawn.endingTriggered).isFalse()
        }
    }

    private fun game(epoch: Epoch, stage: Stage): GameState {
        val ids = listOf(p1, p2, p3)
        val players = ids.map { id -> Player(id, id.value) }
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            currentEpoch = epoch,
            stage = stage,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(ids),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                stockTokens = 16,
                                poolTokens = 20,
                            )
                    }
                    .toMap(),
        )
    }

    private fun GameState.shuffleAndDraw(): GameState {
        val request = requireNotNull(pendingShuffle())
        val shuffled = step(Entry.Shuffle(request.cards))
        val need = requireNotNull(shuffled.pendingDraw())
        return shuffled.step(GameOutcome.Draw(need.player, need.reference, need.card))
    }
}
