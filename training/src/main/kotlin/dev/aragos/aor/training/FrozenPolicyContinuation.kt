package dev.aragos.aor.training

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
    /**
     * Prior absolute PLAYER commands, including capital bids/choices. Excludes authority outcomes
     * and the simulator's automatic configuration/start entries; never a controlled-seat count.
     */
    private val decisionOffset: Int = 0,
) {
    init {
        require(decisionOffset in 0 until 12000) {
            "A continuation offset must be an absolute player-command count below 12000."
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
                val batchStart = decisionOffset + session.decisions
                val policySeed = simulationPolicySeed(seed, batchStart, seat)
                val controller = controllers[seat]?.takeIf { it.controls(observation.decision) }
                val command =
                    controller?.choose(
                        SimulationDecision(
                            number = batchStart + actorIndex,
                            seat = seat,
                            seed = policySeed,
                            view = session.state.viewForPlayer(actor),
                            observation = observation,
                            batchStartNumber = batchStart,
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
    ): TokenValueOutcome {
        while (session.state.stage != Stage.FINISHED) {
            check(session)
            session.apply(
                commands(session, session.actors().associateWith(session::observation)),
                deadlineNanos,
            )
        }
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
}
