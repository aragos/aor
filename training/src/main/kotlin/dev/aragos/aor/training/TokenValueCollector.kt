package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue

/** Commands are fixed from the shared pre-reveal view before the session is forked. */
internal class TokenValueFrozenRoot(
    session: ExperimentSession,
    actor: PlayerId,
    commands: Map<PlayerId, String>,
) {
    private val frozen: FrozenDecisionRoot

    init {
        require(
            session.state.stage == Stage.TOKEN_PURCHASE &&
                actor in commands &&
                commands.keys == session.actors().toSet()
        ) {
            "A token-value fork requires the complete sealed token-purchase batch."
        }
        frozen = FrozenDecisionRoot(session, actor, commands)
    }

    fun branch(command: String, seed: Long, deadlineNanos: Long): ExperimentSession =
        frozen.branch(command, seed, deadlineNanos)
}

/** Real full-game origin collection; authority worlds produce labels and never policy features. */
internal class TokenValueCollector(
    private val specification: TokenValueSpecification,
    private val deadlineNanos: Long,
) {
    internal data class Root(
        val frozen: TokenValueFrozenRoot,
        val observation: BotObservation,
        val baseline: BotCandidate,
        val epoch: Int,
        val turn: Int,
        val decision: Int,
    )

    /** A collected backbone stops before its selected sealed batch; skips finish the game. */
    internal data class Backbone(
        val root: Root?,
        val decisions: Int,
        val skipReason: TokenValueSkipReason?,
    )

    fun collect(index: Int): TokenValueOrigin {
        val lineup = specification.lineup(index)
        val request = specification.rootRequest(index)
        val models =
            lineup.opponentIndices.mapIndexed { seat, opponent ->
                if (seat == lineup.seat) specification.arena.candidate
                else specification.arena.opponents[opponent]
            }
        val session = ExperimentSession.start(lineup.configuration, lineup.seed.toLong())
        val actor = session.state.playerIds[lineup.seat]
        val backbone = locate(session, actor, models, lineup.seed, request)
        val root = backbone.root
        val provenance =
            TokenValueOrigin(
                index = index,
                split = specification.split(index),
                configuration = lineup.configuration.id,
                gameSeed = lineup.seed,
                seat = lineup.seat,
                opponentIndices = lineup.opponentIndices,
                requestedEpoch = request.epoch,
                originDecisions = backbone.decisions,
                status = TokenValueOriginStatus.SKIPPED,
                skipReason = backbone.skipReason ?: TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED,
                requestedOrdinal = request.ordinal,
                learningMode = specification.learningMode,
            )
        if (root == null) return provenance
        val alternatives =
            BotTokenValue.alternatives(root.observation, root.baseline).map { option ->
                val outcomes =
                    List(specification.continuationReplicates) { replicate ->
                        val chanceSeed = continuationSeed(lineup.seed, replicate)
                        val branch =
                            root.frozen.branch(
                                option.command,
                                chanceSeed,
                                deadlineNanos,
                            )
                        finish(
                            branch,
                            models,
                            lineup.seed,
                            lineup.seat,
                            lineup.configuration.id,
                            chanceSeed,
                        )
                    }
                TokenValueAlternative(
                    option.command,
                    BotTokenValue.difference(root.observation, option, root.baseline),
                    outcomes,
                )
            }
        return provenance.copy(
            status = TokenValueOriginStatus.COLLECTED,
            skipReason = null,
            actualEpoch = root.epoch,
            turn = root.turn,
            rootDecision = root.decision,
            baselineCommand = root.baseline.command,
            alternatives = alternatives,
        )
    }

    /**
     * Follow the unchanged policy only until a precommitted root. The ordinal counts every own
     * token decision in the requested epoch, including ineligible ones. An ineligible target is
     * never replaced by a later decision; skipped origins finish solely to retain full-game
     * provenance. Production callers pass a fresh session, while tests may start at a resolved
     * decision.
     */
    internal fun locate(
        session: ExperimentSession,
        actor: PlayerId,
        models: List<BotModel>,
        seed: Int,
        request: TokenValueRootRequest,
    ): Backbone {
        var ordinal = 0
        var skipReason = TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED
        while (session.state.stage != Stage.FINISHED) {
            checkSession(session)
            val observations = session.actors().associateWith(session::observation)
            // All sealed opponents choose from the same pre-batch state before any fork.
            val commands = choose(session, observations, models, seed)
            val observation = observations[actor]
            if (
                session.state.stage == Stage.TOKEN_PURCHASE &&
                    session.state.currentEpoch.number == request.epoch &&
                    observation != null &&
                    ++ordinal == request.ordinal
            ) {
                val baseline = observation.candidates.single { it.command == commands[actor] }
                if (
                    BotTokenValue.available(observation) &&
                        BotTokenValue.alternatives(observation, baseline).size >= 2
                ) {
                    return Backbone(
                        Root(
                            TokenValueFrozenRoot(session, actor, commands),
                            observation,
                            baseline,
                            request.epoch,
                            checkNotNull(session.state.turnNumber) {
                                "A token-purchase root requires its current turn."
                            },
                            session.decisions,
                        ),
                        session.decisions,
                        null,
                    )
                }
                skipReason = TokenValueSkipReason.PRECOMMITTED_ROOT_INELIGIBLE
            }
            session.apply(commands, deadlineNanos)
        }
        return Backbone(null, session.decisions, skipReason)
    }

    private fun finish(
        session: ExperimentSession,
        models: List<BotModel>,
        seed: Int,
        seat: Int,
        configuration: String,
        continuationSeed: Long,
    ): TokenValueOutcome {
        while (session.state.stage != Stage.FINISHED) {
            checkSession(session)
            val observations = session.actors().associateWith(session::observation)
            session.apply(choose(session, observations, models, seed), deadlineNanos)
        }
        val game =
            SimulatedGame(
                configuration,
                seed,
                session.decisions,
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
            continuationSeed,
        )
    }

    private fun choose(
        session: ExperimentSession,
        observations: Map<PlayerId, BotObservation>,
        models: List<BotModel>,
        seed: Int,
    ): Map<PlayerId, String> = observations.mapValues { (actor, observation) ->
        val seat = session.state.playerIds.indexOf(actor)
        BotPolicy.choose(
            observation,
            BotDifficulty.HARD,
            seed xor (session.decisions * 65537 + seat * 8191),
            models[seat],
        )
    }

    private fun checkSession(session: ExperimentSession) {
        checkTokenValueDeadline(deadlineNanos)
        check(session.decisions < 12000) {
            "A token-value continuation exceeded 12000 decisions at ${session.state.stage}."
        }
    }

    companion object {
        /**
         * Distinct streams per origin/replicate, independently seeded from the origin's RNG.
         * Shifting the signed 32-bit origin leaves three low bits for disjoint replica lanes.
         * Extending the allowed count preserves the existing replica-zero and replica-one seeds.
         */
        internal fun continuationSeed(originSeed: Int, replicate: Int): Long {
            require(replicate in 0 until TOKEN_VALUE_MAX_CONTINUATION_REPLICATES) {
                "Token-value continuation replicate must be between zero and three."
            }
            return (originSeed.toLong() shl 3) xor replicate.toLong() xor 0x6f20a5319dL
        }
    }
}
