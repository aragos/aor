package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class QueuedDrawTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun twoShortageBonusesWithOneRemainingCardDoNotRequestADrawFromAnEmptyDeck() {
        val state = income(cards = 1).pushDecision(ShortageDecision)

        val drawn = state.step(TextCodec.parse("!roll shortage 1 2 1")).acceptDraw()

        assertThat(drawn.deck).isEmpty()
        assertThat(drawn.endingTriggered).isTrue()
        assertThat(drawn.playerState(p1).hand).hasSize(1)
        assertThat(drawn.playerState(p2).hand).isEmpty()
        assertThat(drawn.flow.decisions.filterIsInstance<DrawDecision>()).isEmpty()
        assertThat(drawn.stage).isEqualTo(Stage.FINAL_CARD_PLAY)
    }

    @Test
    fun queuedShortageDrawsResolveInTheOriginalMarkerOrder() {
        val state = income(cards = 2).pushDecision(ShortageDecision)

        val first = state.step(TextCodec.parse("!roll shortage 1 2 1")).acceptDraw()

        assertThat(first.playerState(p1).hand).hasSize(1)
        assertThat(first.playerState(p2).hand).isEmpty()
        assertThat((first.prompts().single() as Prompt.System).need)
            .isEqualTo(SystemNeed.Draw(p2, first.nextHandReference()))
    }

    @Test
    fun resolvingAllQueuedDrawsConsumesEachCardExactlyOnce() {
        val state = income(cards = 2).pushDecision(ShortageDecision)

        val drawn = state.step(TextCodec.parse("!roll shortage 1 2 1")).acceptDraw().acceptDraw()

        assertThat(drawn.playerState(p1).hand.map(HandCard::definition))
            .containsExactly(CardData.epochOne[0])
        assertThat(drawn.playerState(p2).hand.map(HandCard::definition))
            .containsExactly(CardData.epochOne[1])
        assertThat(drawn.deck).isEmpty()
        assertThat(drawn.flow.decisions.filterIsInstance<DrawDecision>()).isEmpty()
    }

    @Test
    fun queuedDrawsKeepTheOuterInterruptionBelowTheirFifoSequence() {
        val outer = RenaissanceDecision(p3)
        val state = income(cards = 2).pushDecision(outer).queueDraw(p1).queueDraw(p2)

        val drawn = state.acceptDraw().acceptDraw()

        assertThat(drawn.flow.decisions).containsExactly(outer)
        assertThat(drawn.playerState(p1).hand).hasSize(1)
        assertThat(drawn.playerState(p2).hand).hasSize(1)
    }

    @Test
    fun queuingMoreDrawsThanCardsKeepsEveryBeneficiaryUntilARecordedDraw() {
        val state = income(cards = 1)

        val queued = state.queueDraw(p1).queueDraw(p2)

        assertThat(queued.endingTriggered).isFalse()
        assertThat(queued.flow.decisions.filterIsInstance<DrawDecision>())
            .containsExactly(DrawDecision(p2), DrawDecision(p1))
            .inOrder()
    }

    @Test
    fun aLaterBeneficiaryReceivesTheLastCardWhenTheFirstBeneficiaryEntersChaos() {
        val initial = income(cards = 1)
        val state =
            initial
                .copy(
                    adjustments =
                        mapOf(
                            Commodity.GOLD to listOf(CommodityAdjustment.SHORTAGE),
                            Commodity.IVORY to listOf(CommodityAdjustment.SHORTAGE),
                        ),
                    board =
                        BoardState()
                            .placeMarker(ProvinceId("suez"), p1)
                            .board
                            .placeMarker(ProvinceId("cairo"), p2)
                            .board
                            .placeMarker(ProvinceId("smyrna"), p1)
                            .board,
                    players =
                        initial.players +
                            (p1 to
                                initial
                                    .playerState(p1)
                                    .copy(cash = 0, misery = MiseryStep.THOUSAND)),
                )
                .pushDecision(ShortageDecision)

        val drawn = state.step(TextCodec.parse("!roll shortage 1 2 6")).acceptDraw()

        assertThat(drawn.playerState(p1).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(drawn.playerState(p1).hand).isEmpty()
        assertThat(drawn.playerState(p2).hand.map(HandCard::definition))
            .containsExactly(CardData.epochOne[0])
        assertThat(drawn.deck).isEmpty()
        assertThat(drawn.endingTriggered).isTrue()
        assertThat(drawn.flow.decisions.filterIsInstance<DrawDecision>()).isEmpty()
    }

    @Test
    fun consumingTheLastCardPreservesAnOuterDecisionWhileDroppingOtherDraws() {
        val outer = RenaissanceDecision(p3)
        val state = income(cards = 1).pushDecision(outer).queueDraw(p1).queueDraw(p2)

        val drawn = state.acceptDraw()

        assertThat(drawn.flow.decisions).containsExactly(outer)
        assertThat(drawn.playerState(p1).hand).hasSize(1)
        assertThat(drawn.playerState(p2).hand).isEmpty()
        assertThat(drawn.endingTriggered).isTrue()
    }

    @Test
    fun aLaterSurplusDebtCancelsTheEliminatedPlayersEarlierShortageDraw() {
        val initial = income(cards = 2)
        val state =
            initial
                .copy(
                    adjustments = mapOf(Commodity.GOLD to listOf(CommodityAdjustment.SHORTAGE)),
                    board =
                        BoardState()
                            .placeMarker(ProvinceId("suez"), p1)
                            .board
                            .placeMarker(ProvinceId("smyrna"), p1)
                            .board,
                    players =
                        initial.players +
                            (p1 to
                                initial
                                    .playerState(p1)
                                    .copy(cash = 0, misery = MiseryStep.THOUSAND)),
                )
                .pushDecision(ShortageDecision)

        val eliminated = state.step(TextCodec.parse("!roll shortage 1 2 6"))

        assertThat(eliminated.playerState(p1).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(eliminated.playerState(p1).hand).isEmpty()
        assertThat(eliminated.deck).hasSize(2)
        assertThat(eliminated.flow.decisions.filterIsInstance<DrawDecision>()).isEmpty()
    }

    private fun income(cards: Int): GameState {
        val ids = listOf(p1, p2, p3)
        val capitals = listOf("venice", "genoa", "barcelona")
        val players = ids.map { id -> Player(id, id.value) }
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.INCOME,
            turnNumber = 1,
            deck = CardData.epochOne.take(cards).map(CardDefinition::id),
            turnOrderSelection = TurnOrderSelection(ids),
            flow =
                TurnFlow(
                    actor = p1,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    shortageRolls = 1,
                ),
            adjustments = mapOf(Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE)),
            board =
                BoardState()
                    .placeMarker(ProvinceId("rome"), p1)
                    .board
                    .placeMarker(ProvinceId("smyrna"), p2)
                    .board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to PlayerState(player = player, capital = capitals[index])
                    }
                    .toMap(),
        )
    }

    private fun GameState.acceptDraw(): GameState {
        val need = (prompts().single() as Prompt.System).need as SystemNeed.Draw
        return step(GameOutcome.Draw(need.player, need.reference, deck.first()))
    }
}
