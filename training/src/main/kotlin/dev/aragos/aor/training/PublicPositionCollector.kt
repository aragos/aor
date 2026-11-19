package dev.aragos.aor.training

import dev.aragos.aor.GameState
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.PublicWinningPosition

/** One public table snapshot per specified turn of each original simulated game. */
internal class PublicPositionCollector(override val seat: Int) : SimulationStateObserver {
    private val snapshots = mutableMapOf<Int, Map<String, Any>>()

    override fun chosen(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ) {
        if (observation.decision != "expand") return
        val turn = state.turnNumber ?: return
        if (turn !in TURNS || turn in snapshots) return
        val view = state.viewForDecision(state.playerIds[seat])
        snapshots[turn] =
            mapOf(
                "turn" to turn,
                "epoch" to view.currentEpoch,
                "ending" to view.endingTriggered,
                "observedSeats" to
                    PublicWinningPosition.seats(view).map { position ->
                        mapOf(
                            "player" to position.player.value,
                            "active" to position.active,
                            "features" to position.features,
                        )
                    },
            )
    }

    fun result(): Map<String, Any> =
        mapOf(
            "kind" to PublicWinningPosition.VERSION,
            "columns" to PublicWinningPosition.columns,
            "snapshots" to snapshots.values.toList(),
        )

    companion object {
        val TURNS = setOf(3, 5, 7, 9)
    }
}
