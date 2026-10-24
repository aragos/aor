package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TurnBoundaryTest {
    private val ids = (1..3).map { number -> PlayerId("p$number") }
    private val p1 = ids.first()
    private val p2 = ids[1]

    @Test
    fun holyIndulgenceTakesPoolTokensBeforeCash() {
        val state =
            game().withAdvance(p1, AdvanceId.HOLY_INDULGENCE).updatePlayer(p2) { player ->
                player.copy(stockTokens = 35, poolTokens = 1)
            }
        val applied = state.applyHolyIndulgence()
        assertThat(applied.playerState(p2).poolTokens).isEqualTo(0)
        assertThat(applied.playerState(p2).cash).isEqualTo(39)
        assertThat(applied.playerState(p1).poolTokens).isEqualTo(4)
    }

    @Test
    fun holyIndulgencePaysOwnerForInsufficientStock() {
        val state =
            game().withAdvance(p1, AdvanceId.HOLY_INDULGENCE).updatePlayer(p1) { player ->
                player.copy(stockTokens = 1)
            }
        val applied = state.applyHolyIndulgence()
        assertThat(applied.playerState(p1).poolTokens).isEqualTo(1)
        assertThat(applied.playerState(p1).cash).isEqualTo(43)
    }

    @Test
    fun holyIndulgenceDoesNotActAgainstChaos() {
        val state =
            game().withAdvance(p1, AdvanceId.HOLY_INDULGENCE).updatePlayer(p2) { player ->
                player.copy(misery = MiseryStep.CHAOS)
            }
        val applied = state.applyHolyIndulgence()
        assertThat(applied.playerState(p1).poolTokens).isEqualTo(2)
        assertThat(applied.playerState(p2).cash).isEqualTo(40)
    }

    @Test
    fun enteringChaosDiscardsHandAndReturnsUnusedPool() {
        val state =
            game().updatePlayer(p1) { player ->
                player.copy(
                    stockTokens = 32,
                    poolTokens = 4,
                    hand = listOf(HandCard("hand-1", CardData.epochOne.first())),
                )
            }
        val applied = state.replacePlayer(state.playerState(p1).copy(misery = MiseryStep.CHAOS))
        assertThat(applied.playerState(p1).hand).isEmpty()
        assertThat(applied.playerState(p1).stockTokens).isEqualTo(36)
        assertThat(applied.playerState(p1).poolTokens).isEqualTo(0)
    }

    @Test
    fun allChaosEndsWithoutAnotherSealedRound() {
        val state =
            game()
                .copy(
                    players =
                        game().players.mapValues { (_, player) ->
                            player.copy(misery = MiseryStep.CHAOS)
                        }
                )
        assertThat(state.normalise().stage).isEqualTo(Stage.FINISHED)
    }

    @Test
    fun doubleShortageMarkersAreBoughtOffSeparately() {
        val state =
            game()
                .copy(
                    stage = Stage.DRAW_CARD,
                    flow = TurnFlow(actor = p1, phaseStarted = true),
                    adjustments =
                        mapOf(
                            Commodity.STONE to
                                listOf(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SHORTAGE),
                            Commodity.WOOL to listOf(CommodityAdjustment.SURPLUS),
                        ),
                )
        val cancelled =
            state
                .step(TextCodec.parse("p1: cancel-adjustment stone shortage"))
                .step(TextCodec.parse("p1: cancel-adjustment stone shortage"))
        assertThat(cancelled.adjustments[Commodity.STONE]).isEmpty()
        assertThat(cancelled.playerState(p1).cash).isEqualTo(38)
    }

    @Test
    fun renaissanceOffersAWindowBeforeSealedBidsInLaterTurns() {
        val state =
            game()
                .withAdvance(p1, AdvanceId.RENAISSANCE)
                .copy(stage = Stage.TOKEN_PURCHASE, flow = TurnFlow())
                .normalise()
        assertThat(state.prompts().single()).isEqualTo(Prompt.SinglePlayer(p1, "renaissance"))
        assertThat(
                assertFailsWith<RuleViolation> { state.step(TextCodec.parse("p1: buy-tokens 1")) }
                    .code
            )
            .isEqualTo(ViolationCode.WRONG_PHASE)
    }

    private fun game(): GameState {
        val header = GameHeader(ids.map { id -> Player(id, id.value) }, p1)
        return GameState(
            header,
            stage = Stage.PURCHASE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            flow = TurnFlow(actor = p1, phaseStarted = true),
        )
    }

    private fun GameState.withAdvance(player: PlayerId, advance: AdvanceId): GameState =
        updatePlayer(player) { owner -> owner.copy(advances = owner.advances.acquire(advance, 1)) }
}
