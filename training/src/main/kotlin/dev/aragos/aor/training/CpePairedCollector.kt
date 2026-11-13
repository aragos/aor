package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec

internal enum class CpePairedStatus {
    READY,
    MISSING,
}

/** The reference is the retained actual sample; the observation's prior index stays unchanged. */
internal data class CpePairedPublicRoot(
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val actorSeed: Int,
    val observation: CpePairedObservation,
    val referenceIndex: Int,
    val sampledCommandIndex: Int,
    val behaviorLogProbability: Double,
    val behaviorValue: Double,
    val currentProbabilities: List<Double>,
    val originalProbabilities: List<Double>,
    val proposedIndices: List<Int>,
    val inclusionProbability: Double,
) {
    companion object {
        fun from(frame: CpePairedDecision, seed: Long): CpePairedPublicRoot {
            val proposals =
                CpePairedProposals.sample(
                    frame.observation.commands.size,
                    frame.sampledCommandIndex,
                    seed,
                )
            return CpePairedPublicRoot(
                frame.number,
                frame.batchStartNumber,
                frame.botSeed,
                frame.actorSeed,
                frame.observation,
                frame.sampledCommandIndex,
                frame.sampledCommandIndex,
                frame.behaviorLogProbability,
                frame.behaviorValue,
                frame.currentProbabilities,
                frame.originalProbabilities,
                proposals.indices,
                proposals.inclusionProbability,
            )
        }
    }
}

internal data class CpePairedAnchor(
    val iteration: Int,
    val head: CpeHead,
    val globalIndex: Int,
    val number: Int,
    val batchStartNumber: Int,
    val observation: CpePairedObservation,
    val originalProbabilities: List<Double>,
)

/** Canonical/tail records reuse the existing paired evidence wire; public rows have a new kind. */
internal data class CpePairedOrigin(
    val kind: String = "cpe-paired-origin-1",
    val signature: String,
    val bundleScopeSha256: String,
    val scopeSha256: String,
    val scienceSha256: String,
    val iteration: Int,
    val request: CpePairedRequest,
    val streams: CpePairedSeeds,
    val status: CpePairedStatus,
    val missingReason: String?,
    val observedWindows: Int,
    val branchingWindows: Int,
    val selectedOrdinal: Int?,
    val publicRoot: CpePairedPublicRoot?,
    val anchors: List<CpePairedAnchor>,
    val prefix: TokenPairedCanonical,
    val backbone: TokenPairedCanonical,
    val backboneTerminal: TokenValueOutcome,
    val arms: List<TokenPairedArm>,
)

/** Full current-bundle backbones with a public-eligibility reservoir and paired first actions. */
internal class CpePairedCollector(
    private val input: CpePairedInput,
    private val deadlineNanos: Long,
) {
    private data class Root(
        val state: GameState,
        val frozen: FrozenDecisionRoot,
        val entries: List<String>,
        val decisions: Int,
        val frame: CpePairedDecision,
    )

    fun collect(index: Int): CpePairedOrigin = run(index, includeTails = true)

    fun reproduce(index: Int): CpePairedOrigin = run(index, includeTails = false)

    private fun run(index: Int, includeTails: Boolean): CpePairedOrigin {
        require(index in input.range.indices) { "C/P/E origin must belong to its global shard." }
        val request = input.request(index)
        val streams = input.origins[index]
        val session = ExperimentSession.start(input.configuration, streams.gameSeed.toLong(), true)
        val actor = session.state.playerIds[request.seat]
        val reservoir = CpePairedReservoir(request, streams.reservoirSeed)
        val anchors = mutableListOf<CpePairedAnchor>()
        var frame: CpePairedDecision? = null
        val normal =
            continuation(index) { decision ->
                frame = decision
                if (input.iteration == 1 && request.split == TokenPairedSplit.TRAIN) {
                    anchors +=
                        CpePairedAnchor(
                            input.iteration,
                            input.head,
                            index,
                            decision.number,
                            decision.batchStartNumber,
                            decision.observation,
                            decision.originalProbabilities,
                        )
                }
            }
        var root: Root? = null
        while (session.state.stage != Stage.FINISHED) {
            normal.check(session)
            val observations = session.actors().associateWith(session::observation)
            frame = null
            // Every actor commits from the same untouched pre-batch view.
            val commands = normal.commands(session, observations)
            val own = observations[actor]
            val selected =
                if (own?.decision in input.head.decisions) {
                    requireNotNull(frame) {
                        "The assigned current head must record its actual command."
                    }
                } else null
            if (
                own != null &&
                    selected != null &&
                    reservoir.observe(
                        session.state.currentEpoch.number,
                        own.decision,
                        selected.observation.commands.size,
                    )
            ) {
                val fresh = continuation(index, offset = session.decisions)
                val prototype = ExperimentSession(session.state, streams.future.first())
                check(fresh.commands(prototype, observations) == commands) {
                    "Fresh complete C/P/E controllers must reproduce the entire original command batch."
                }
                check(
                    selected.observation.commands[selected.sampledCommandIndex] == commands[actor]
                ) {
                    "The reservoir reference must be the actual current-bundle command."
                }
                root =
                    Root(
                        session.state,
                        FrozenDecisionRoot(session, actor, commands),
                        session.entries.map(Entry::toText),
                        session.decisions,
                        selected,
                    )
            }
            session.apply(commands, deadlineNanos)
        }
        val backbone =
            canonical(session.state, session.entries.map(Entry::toText), session.decisions)
        val terminal =
            normal.finish(session, request.seat, input.configuration.id, streams.gameSeed.toLong())
        val captured = root
        val prefix = captured?.let { canonical(it.state, it.entries, it.decisions) } ?: backbone
        val public = captured?.let { CpePairedPublicRoot.from(it.frame, streams.proposalSeed) }
        return CpePairedOrigin(
            signature = input.signature,
            bundleScopeSha256 = input.bundleScopeSha256,
            scopeSha256 = input.scopeSha256,
            scienceSha256 = input.scienceSha256,
            iteration = input.iteration,
            request = request,
            streams = streams,
            status = if (captured == null) CpePairedStatus.MISSING else CpePairedStatus.READY,
            missingReason = if (captured == null) "NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH" else null,
            observedWindows = reservoir.observedWindows,
            branchingWindows = reservoir.branchingWindows,
            selectedOrdinal = reservoir.selectedOrdinal,
            publicRoot = public,
            anchors = anchors.toList(),
            prefix = prefix,
            backbone = backbone,
            backboneTerminal = terminal,
            arms =
                if (includeTails && captured != null && public != null)
                    tails(index, captured, public)
                else emptyList(),
        )
    }

    private fun tails(index: Int, root: Root, public: CpePairedPublicRoot): List<TokenPairedArm> =
        public.proposedIndices.map { actionIndex ->
            val command = public.observation.commands[actionIndex]
            val outcomes =
                input.origins[index].future.mapIndexed { replicate, chance ->
                    val branch = root.frozen.branch(command, chance, deadlineNanos)
                    val terminal =
                        continuation(index)
                            .finish(
                                branch,
                                input.request(index).seat,
                                input.configuration.id,
                                chance,
                            )
                    val suffix = branch.entries.drop(root.entries.size).map(Entry::toText)
                    check(
                        replay(root.state, suffix) == branch.state &&
                            branch.state.stage == Stage.FINISHED
                    ) {
                        "Every C/P/E paired suffix must reproduce its exact terminal state."
                    }
                    TokenPairedTail(replicate, terminal, suffix, CardAdvantageExecutor.hash(suffix))
                }
            TokenPairedArm(
                actionIndex,
                command,
                if (actionIndex == public.referenceIndex) 1.0 else public.inclusionProbability,
                outcomes,
            )
        }

    private fun continuation(
        index: Int,
        offset: Int = 0,
        record: ((CpePairedDecision) -> Unit)? = null,
    ) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(index, record),
            input.origins[index].gameSeed,
            deadlineNanos,
            decisionOffset = offset,
        )

    private fun canonical(
        state: GameState,
        entries: List<String>,
        decisions: Int,
    ): TokenPairedCanonical {
        check(replay(GameState(state.header), entries) == state) {
            "The C/P/E canonical history must reproduce its exact state."
        }
        return TokenPairedCanonical(
            state.header,
            entries,
            CardAdvantageExecutor.hash(entries),
            decisions,
            state.stage == Stage.FINISHED,
        )
    }

    private fun replay(state: GameState, entries: List<String>): GameState =
        entries.fold(state) { current, text ->
            checkTokenValueDeadline(deadlineNanos)
            current.step(TextCodec.parse(text))
        }
}
