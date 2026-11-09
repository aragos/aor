package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class GameStatisticsTest {
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
    private val owner = players.first().id
    private val header = GameHeader(players, owner)

    @Test
    fun finalPointUsesAuthoritativeScoresAndKeepsChaosNull() {
        val state =
            GameState(header, stage = Stage.FINISHED, turnNumber = 4).let { initial ->
                initial.copy(
                    players =
                        initial.players.mapValues { (id, player) ->
                            player.copy(
                                cash = 25,
                                misery = if (id == owner) MiseryStep.CHAOS else MiseryStep.ZERO,
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.CARAVAN, 1))
                                    ),
                            )
                        }
                )
            }

        val point = GameStatisticsPoint.from(state)

        assertThat(point.finished).isTrue()
        assertThat(point.turnNumber).isEqualTo(4)
        assertThat(point.players.map(PlayerStatisticsView::score))
            .containsExactly(null, 45, 45)
            .inOrder()
        assertThat(point.players.map(PlayerStatisticsView::advances)).containsExactly(1, 1, 1)
        assertThat(point.players.first().misery).isNull()
    }

    @Test
    fun historicalPointsArePublicAndRetainEarlierPositionsAfterPurchases() {
        val stored = thirdTurn()

        val ownerHistory = HistoryProjection.forPlayer(stored, owner)
        val publicHistory = HistoryProjection.forPlayer(stored, null)

        assertThat(publicHistory.statistics).isEqualTo(ownerHistory.statistics)
        assertThat(publicHistory.statistics.map(GameStatisticsPoint::turnNumber))
            .containsExactly(1, 2, 3)
            .inOrder()
        assertThat(publicHistory.statistics.first().players.map(PlayerStatisticsView::advances))
            .containsExactly(0, 0, 0)
        assertThat(publicHistory.statistics[1].players.first().advances).isGreaterThan(0)
        assertThat(publicHistory.statistics.none(GameStatisticsPoint::finished)).isTrue()
    }

    @Test
    fun rewindRemovesStatisticsFromTheDiscardedFuture() {
        val stored = thirdTurn()
        val destination =
            LinearHistory.targets(stored, owner).last { it.turnNumber == 1 }.entryCount
        val rewound = HistoryEdits.undo(stored, owner, destination)

        val statistics = HistoryProjection.forPlayer(rewound, owner).statistics

        assertThat(statistics.map(GameStatisticsPoint::turnNumber)).containsExactly(1)
        assertThat(statistics)
            .isEqualTo(HistoryProjection.forPlayer(stored, owner).statistics.take(1))
    }

    private fun thirdTurn(): StoredGame {
        val driver = GameDriver(FixedRandom())
        var stored =
            driver.create("statistics", header, emptyMap(), undoPolicy = UndoPolicy.LINEAR_GAME)
        repeat(200) {
            val state = stored.state()
            if (state.turnNumber == 3) return stored
            val actor =
                when (val prompt = state.prompts().single()) {
                    is Prompt.SinglePlayer -> prompt.player
                    is Prompt.MultiPlayer -> prompt.players.first { it !in stored.pendingBids }
                    is Prompt.System -> error("The driver must resolve random outcomes.")
                }
            val choices = state.legalChoices(actor).commands
            val command =
                when {
                    state.stage == Stage.BIDDING -> "bid-capital 0"
                    state.stage == Stage.TOKEN_PURCHASE -> "buy-tokens 0"
                    actor == owner && "buy-advance I" in choices -> "buy-advance I"
                    "stabilize cash" in choices -> "stabilize cash"
                    "pass" in choices -> "pass"
                    else -> choices.first()
                }
            stored = driver.submit(stored, actor, command)
        }
        error("Statistics fixture must reach turn three.")
    }
}
