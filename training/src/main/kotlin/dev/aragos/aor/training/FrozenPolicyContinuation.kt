package dev.aragos.aor.training

import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy

/** Shared controller routing for every prefix/tail; sealed choices use one untouched state. */
internal class FrozenPolicyContinuation(
    private val models: List<BotModel>,
    private val controllers: Map<Int, SimulationController>,
    private val seed: Int,
    private val deadlineNanos: Long,
    /** Absolute PLAYER-command offset of a fork; canonical/system entries do not count. */
    private val decisionOffset: Int = 0,
) {
    init {
        require(decisionOffset in 0 until 12000) {
            "A continuation offset must be an original player-command number."
        }
    }

    fun check(session: ExperimentSession) {
        checkTokenValueDeadline(deadlineNanos)
        check(decisionOffset + session.decisions < 12000) {
            "A frozen-policy continuation exceeded 12000 decisions."
        }
    }

    fun commands(
        session: ExperimentSession,
        observations: Map<PlayerId, BotObservation>,
    ): Map<PlayerId, String> =
        observations.entries
            .mapIndexed { actorIndex, (actor, observation) ->
                val seat = session.state.playerIds.indexOf(actor)
                val number = decisionOffset + session.decisions
                val policySeed = simulationPolicySeed(seed, number, seat)
                val controller = controllers[seat]?.takeIf { it.controls(observation.decision) }
                val command =
                    controller?.choose(
                        SimulationDecision(
                            number = number + actorIndex,
                            seat = seat,
                            seed = policySeed,
                            view = session.state.viewForPlayer(actor),
                            observation = observation,
                            batchStartNumber = number,
                        )
                    ) ?: BotPolicy.choose(observation, BotDifficulty.HARD, policySeed, models[seat])
                actor to command
            }
            .toMap()

    fun finish(
        session: ExperimentSession,
        seat: Int,
        configuration: String,
        chanceSeed: Long,
        onNewTurn: ((GameState, Int) -> Unit)? = null,
    ): TokenValueOutcome {
        advance(session, null, onNewTurn)
        val game =
            SimulatedGame(
                configuration,
                seed,
                decisionOffset + session.decisions,
                session.state.publicView().scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0,
            )
        return TokenValueOutcome(
            game.reward(seat),
            game.winShare(seat),
            game.rankReward(seat),
            game.scores.map(PilotReplayScore::from),
            game.steps,
            chanceSeed,
        )
    }

    /** Stop at the first decision of the requested turn or an earlier terminal. */
    fun reachTurn(session: ExperimentSession, turn: Int) {
        require(turn > 0) { "An abbreviated continuation requires a positive target turn." }
        advance(session, turn, null)
    }

    private fun advance(
        session: ExperimentSession,
        target: Int?,
        onNewTurn: ((GameState, Int) -> Unit)?,
    ) {
        var observedTurn = session.state.turnNumber
        while (
            session.state.stage != Stage.FINISHED &&
                (target == null || (session.state.turnNumber ?: 0) < target)
        ) {
            check(session)
            session.apply(
                commands(session, session.actors().associateWith(session::observation)),
                deadlineNanos,
            )
            if (onNewTurn != null && session.state.turnNumber != observedTurn) {
                observedTurn = session.state.turnNumber
                onNewTurn(session.state, session.decisions)
            }
        }
    }
}
