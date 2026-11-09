package dev.aragos.aor

/** Public positions sampled at the start of each turn and once more at game completion. */
data class GameStatisticsPoint(
    val turnNumber: Int,
    val finished: Boolean,
    val players: List<PlayerStatisticsView>,
) {
    companion object {
        internal fun from(state: GameState): GameStatisticsPoint =
            GameStatisticsPoint(
                turnNumber = requireNotNull(state.turnNumber) { "Statistics require a game turn." },
                finished = state.stage == Stage.FINISHED,
                players =
                    ScoreView.from(state).map { score ->
                        PlayerStatisticsView(
                            player = score.player,
                            score = score.total,
                            advances = state.playerState(score.player).advances.acquisitions.size,
                            cash = score.cash,
                            misery = score.misery,
                        )
                    },
            )
    }
}

/**
 * Chaos has neither a numeric score nor a numeric misery value. Advances is a count, not points.
 */
data class PlayerStatisticsView(
    val player: PlayerId,
    val score: Int?,
    val advances: Int,
    val cash: Int,
    val misery: Int?,
)
