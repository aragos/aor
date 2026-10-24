package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class GameEndingTest {
    private val p1 = PlayerId("p1")

    @Test
    fun twentySixthAdvanceMarksTheCurrentTurnAsFinal() {
        val state = almostComplete().step(TextCodec.parse("p1: buy-advance Z"))
        assertThat(state.endingTriggered).isTrue()
        assertThat(state.currentEpoch).isEqualTo(Epoch.ONE)
        assertThat(state.stage).isEqualTo(Stage.PURCHASE)
        assertThat(state.deck).isNotEmpty()
    }

    @Test
    fun advanceEndingFinishesTheTurnAndScoresAllAdvances() {
        val completed = Scenario(almostComplete()).play("p1: buy-advance Z").complete().state
        val score = completed.viewForPlayer(p1).scores.single { score -> score.player == p1 }
        assertThat(completed.stage).isEqualTo(Stage.FINISHED)
        assertThat(completed.currentEpoch).isEqualTo(Epoch.ONE)
        assertThat(completed.deck).isNotEmpty()
        assertThat(score.advancePoints).isEqualTo(2100)
        assertThat(score.total)
            .isEqualTo(score.advancePoints + score.cash - requireNotNull(score.misery))
        assertThat(score.winner).isTrue()
    }

    @Test
    fun finalScoresUseMiseryValuesAndAllowTiedWinners() {
        val finished =
            almostComplete().copy(stage = Stage.FINISHED).let { state ->
                state.copy(
                    players =
                        state.players.mapValues { (_, player) ->
                            player.copy(
                                advances = PlayerAdvances(),
                                cash = 200,
                                misery = MiseryStep.HUNDRED,
                            )
                        }
                )
            }
        val scores = finished.viewForPlayer(p1).scores
        assertThat(scores.map(ScoreView::total)).containsExactly(100, 100, 100)
        assertThat(scores.all(ScoreView::winner)).isTrue()
    }

    private fun almostComplete(): GameState {
        val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
        val capitals = listOf("venice", "genoa", "barcelona")
        val owned =
            AdvanceId.entries
                .filterNot { advance -> advance == AdvanceId.MIDDLE_CLASS }
                .fold(PlayerAdvances()) { advances, advance -> advances.acquire(advance, 1) }
        val states =
            players
                .mapIndexed { index, player ->
                    player.id to
                        PlayerState(
                            player,
                            capital = capitals[index],
                            cash = if (player.id == p1) 1000 else 40,
                            advances = if (player.id == p1) owned else PlayerAdvances(),
                        )
                }
                .toMap()
        val board =
            players.fold(BoardState()) { board, player ->
                val capital =
                    requireNotNull(states[player.id]) {
                            "Every registered player must have a state: ${player.id}."
                        }
                        .capital
                board
                    .placeMarker(
                        ProvinceData.definitions
                            .single { province -> province.capital == capital }
                            .id,
                        player.id,
                    )
                    .board
            }
        return GameState(
            GameHeader(players, p1),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            deck = CardData.epochOne.map(CardDefinition::id),
            players = states,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow =
                TurnFlow(
                    actor = p1,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
        )
    }
}
