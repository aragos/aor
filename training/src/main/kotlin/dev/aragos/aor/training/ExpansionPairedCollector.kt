package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.ExpansionBonusProjection
import dev.aragos.aor.ExpansionBonusView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.ExpansionPairedEncoder
import dev.aragos.aor.ai.ExpansionPairedObservation

internal enum class ExpansionWindowSelection {
    BRANCHING,
    NONBRANCHING,
}

internal enum class ExpansionOriginStatus {
    COLLECTED,
    MISSING,
}

internal enum class ExpansionMissingReason {
    TARGET_WINDOW_NONBRANCHING,
    TARGET_WINDOW_NOT_REACHED,
}

/** Per-origin chronological tracker. Pass-only ordinary windows count; nested decisions do not. */
internal class ExpansionPairedWindow(private val request: ExpansionPairedRequest) {
    var firstTurn: Int? = null
        private set

    var observedWindows: Int = 0
        private set

    var selectedNonbranching: Boolean = false
        private set

    fun observe(
        epoch: Int,
        seat: Int,
        turn: Int,
        decision: String,
        actions: Int,
    ): ExpansionWindowSelection? {
        if (epoch != request.epoch || seat != request.seat || decision != "expand") return null
        require(turn > 0 && actions > 0) {
            "An ordinary expansion window requires its turn and offers."
        }
        if (firstTurn == null) firstTurn = turn
        if (firstTurn != turn) return null
        val ordinal = observedWindows++
        if (ordinal != request.ordinal) return null
        return if (actions > 1) {
            ExpansionWindowSelection.BRANCHING
        } else {
            selectedNonbranching = true
            ExpansionWindowSelection.NONBRANCHING
        }
    }
}

internal data class ExpansionPairedPrefix(
    val header: GameHeader,
    val entries: List<String>,
    val canonicalSha256: String,
    val playerDecisions: Int,
    val terminal: Boolean,
    val replayVerified: Boolean = true,
)

/** Authority identifiers/seeds/canonical evidence and outcomes never enter this actor input. */
internal data class ExpansionPairedActorInput(
    val view: GameView,
    val observation: BotObservation,
    val bonus: ExpansionBonusView,
    val encoded: ExpansionPairedObservation,
)

internal data class ExpansionPairedTail(
    val replicate: Int,
    val terminal: TokenValueOutcome,
    val canonicalEntries: List<String>,
    val canonicalSha256: String,
    val replayVerified: Boolean = true,
)

internal data class ExpansionPairedArm(
    val proposal: ExpansionPairedProposal,
    val outcomes: List<ExpansionPairedTail>,
)

internal data class ExpansionPairedOrigin(
    val kind: String = "expansion-paired-origin-1",
    val signature: String,
    val request: ExpansionPairedRequest,
    val streams: ExpansionPairedSeeds,
    val status: ExpansionOriginStatus,
    val missingReason: ExpansionMissingReason?,
    val firstExpansionTurn: Int?,
    val observedWindows: Int,
    val prefix: ExpansionPairedPrefix,
    val actorInput: ExpansionPairedActorInput?,
    val incumbentCommand: String?,
    val exactIncumbentVerified: Boolean,
    val arms: List<ExpansionPairedArm>,
)

/** One true first-action intervention followed by fresh exact normal E/T/G3 controllers. */
internal class ExpansionPairedCollector(
    private val input: ExpansionPairedInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): ExpansionPairedOrigin {
        val request = ExpansionPairedRequest(index)
        val streams = input.origins[index]
        val session = ExperimentSession.start(input.configuration, streams.gameSeed.toLong(), true)
        val normal = continuation(streams, request.seat)
        val window = ExpansionPairedWindow(request)
        while (session.state.stage != Stage.FINISHED) {
            normal.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = normal.commands(session, observations)
            for ((actor, observation) in observations) {
                val selection =
                    window.observe(
                        session.state.currentEpoch.number,
                        session.state.playerIds.indexOf(actor),
                        session.state.turnNumber ?: 0,
                        observation.decision,
                        observation.candidates.size,
                    )
                if (selection == ExpansionWindowSelection.BRANCHING) {
                    check(session.actors() == listOf(actor)) { "An expansion root has one actor." }
                    return evaluate(
                        request,
                        streams,
                        session,
                        window,
                        requireNotNull(commands[actor]) { "The exact E incumbent is required." },
                    )
                }
            }
            session.apply(commands, deadlineNanos)
        }
        return ExpansionPairedOrigin(
            signature = input.signature,
            request = request,
            streams = streams,
            status = ExpansionOriginStatus.MISSING,
            missingReason =
                if (window.selectedNonbranching) ExpansionMissingReason.TARGET_WINDOW_NONBRANCHING
                else ExpansionMissingReason.TARGET_WINDOW_NOT_REACHED,
            firstExpansionTurn = window.firstTurn,
            observedWindows = window.observedWindows,
            prefix = prefix(session),
            actorInput = null,
            incumbentCommand = null,
            exactIncumbentVerified = false,
            arms = emptyList(),
        )
    }

    private fun continuation(streams: ExpansionPairedSeeds, seat: Int, offset: Int = 0) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(seat),
            streams.gameSeed,
            deadlineNanos,
            decisionOffset = offset,
        )

    private fun prefix(session: ExperimentSession): ExpansionPairedPrefix {
        val entries = session.entries.map(Entry::toText)
        check(replay(GameState(session.state.header), entries) == session.state) {
            "Recorded canonical prefix must reproduce the exact expansion root."
        }
        return ExpansionPairedPrefix(
            session.state.header,
            entries,
            CardAdvantageExecutor.hash(entries),
            session.decisions,
            session.state.stage == Stage.FINISHED,
        )
    }

    private fun replay(initial: GameState, entries: List<String>): GameState =
        entries.fold(initial) { state, text ->
            checkTokenValueDeadline(deadlineNanos)
            state.step(TextCodec.parse(text))
        }

    private fun evaluate(
        request: ExpansionPairedRequest,
        streams: ExpansionPairedSeeds,
        root: ExperimentSession,
        window: ExpansionPairedWindow,
        incumbent: String,
    ): ExpansionPairedOrigin {
        val state = root.state
        val actor = state.playerIds[request.seat]
        val observation = root.observation(actor)
        val view = state.viewForPlayer(actor)
        val bonus = ExpansionBonusProjection.from(state)
        val encoded = ExpansionPairedEncoder.from(view, actor, observation, incumbent, bonus)
        val actorInput = ExpansionPairedActorInput(view, observation, bonus, encoded)
        // All public proposals and inputs are fixed before any future chance stream is consumed.
        val proposals =
            ExpansionPairedProposals.from(
                encoded.actions,
                encoded.baselineIndex,
                streams.proposalSeed,
            )
        val prefix = prefix(root)
        val prototype = ExperimentSession(state, streams.future.first(), recordEntries = true)
        check(
            continuation(streams, request.seat, root.decisions)
                .commands(prototype, mapOf(actor to observation))[actor] == incumbent
        ) {
            "Fresh normal E at the original absolute offset must reproduce the selected command."
        }
        val arms = proposals.map { proposal ->
            val outcomes =
                streams.future.mapIndexed { replicate, chance ->
                    val session = prototype.fork(chance)
                    session.apply(mapOf(actor to proposal.command), deadlineNanos)
                    // E has no saved plan queue. Every next decision re-plans from the actual view.
                    val normal = continuation(streams, request.seat, root.decisions)
                    val terminal =
                        normal.finish(session, request.seat, input.configuration.id, chance)
                    val entries = session.entries.map(Entry::toText)
                    check(
                        replay(state, entries) == session.state &&
                            session.state.stage == Stage.FINISHED
                    ) {
                        "Each terminal expansion suffix must replay exactly under its recorded outcomes."
                    }
                    ExpansionPairedTail(
                        replicate,
                        terminal,
                        entries,
                        CardAdvantageExecutor.hash(entries),
                    )
                }
            ExpansionPairedArm(proposal, outcomes)
        }
        return ExpansionPairedOrigin(
            signature = input.signature,
            request = request,
            streams = streams,
            status = ExpansionOriginStatus.COLLECTED,
            missingReason = null,
            firstExpansionTurn = window.firstTurn,
            observedWindows = window.observedWindows,
            prefix = prefix,
            actorInput = actorInput,
            incumbentCommand = incumbent,
            exactIncumbentVerified = true,
            arms = arms,
        )
    }
}
