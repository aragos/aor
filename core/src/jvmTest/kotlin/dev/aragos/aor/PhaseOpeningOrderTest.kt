package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class PhaseOpeningOrderTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun renaissancePrecedesHolyIndulgenceDebtsAndTheirChaosElimination() {
        val pending =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p2, AdvanceId.HOLY_INDULGENCE)
                .updatePlayer(p1) { player -> player.copy(cash = 0, misery = MiseryStep.THOUSAND) }
                .normalise()

        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
        assertThat(pending.playerState(p1).misery).isEqualTo(MiseryStep.THOUSAND)
        assertThat(pending.playerState(p2).poolTokens).isEqualTo(0)
    }

    @Test
    fun holyIndulgenceRunsOnceWhenTheLastRenaissanceWindowCloses() {
        val pending =
            game(Stage.EXPANSION)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .withAdvances(p2, AdvanceId.HOLY_INDULGENCE)
                .normalise()

        val expanded = pending.step(TextCodec.parse("p1: pass")).normalise()

        assertThat(expanded.playerState(p2).poolTokens).isEqualTo(4)
        assertThat(expanded.playerState(p1).cash).isEqualTo(38)
        assertThat(expanded.flow.phaseBookkeepingApplied).isTrue()
    }

    @Test
    fun incomeIsNotPaidUntilItsRenaissanceWindowCloses() {
        val pending = game(Stage.INCOME).withAdvances(p1, AdvanceId.RENAISSANCE).normalise()

        assertThat(pending.playerState(p1).cash).isEqualTo(40)
        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p1, "renaissance"))
    }

    @Test
    fun incomeAndTheWhitePieceFlipOccurOnceAfterRenaissance() {
        val pending =
            game(Stage.INCOME)
                .withAdvances(p1, AdvanceId.RENAISSANCE)
                .copy(
                    board =
                        BoardState().placeMarker(ProvinceId("venice"), p1, PieceSide.WHITE).board
                )
                .normalise()

        val paid = pending.step(TextCodec.parse("p1: pass")).normalise()

        assertThat(paid.playerState(p1).cash).isEqualTo(58)
        assertThat(paid.board.pieces[ProvinceId("venice")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(paid.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.SHORTAGE, 3)))
    }

    private fun game(stage: Stage): GameState {
        val ids = listOf(p1, p2, p3)
        return GameState(
            header = GameHeader(ids.map { id -> Player(id, id.value) }, p1),
            stage = stage,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
        )
    }

    private fun GameState.withAdvances(player: PlayerId, vararg advances: AdvanceId): GameState =
        updatePlayer(player) { owner ->
            owner.copy(
                advances =
                    PlayerAdvances(
                        advances.map { advance ->
                            AdvanceAcquisition(advance, turnNumber = 1)
                        }
                    )
            )
        }
}
