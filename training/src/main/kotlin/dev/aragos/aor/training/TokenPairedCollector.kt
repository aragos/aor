package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.TerritoryObservation

internal enum class TokenPairedStatus {
    READY,
    SINGLETON,
    MISSING,
}

/** Every own token window in the assigned epoch counts, including a singleton. */
internal class TokenPairedWindow(private val request: TokenPairedRequest) {
    var observed: Int = 0
        private set

    fun observe(epoch: Int, decision: String): Boolean {
        if (epoch != request.epoch || decision != "buy-tokens") return false
        observed++
        return observed == request.ordinal
    }
}

/** No authority state, hidden commitments, canonical entries or returns enter this record. */
internal data class TokenPairedPublicRoot(
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val actorSeed: Int,
    val observation: TerritoryObservation,
    val referenceIndex: Int,
    val sampledCommandIndex: Int,
    val behaviorLogProbability: Double,
    val behaviorValue: Double,
    val currentProbabilities: List<Double>,
    val originalProbabilities: List<Double>,
    val proposedIndices: List<Int>,
    val inclusionProbability: Double,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val anchor94Probabilities: List<Double>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val rawReferenceIndex: Int? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val mandatoryIndices: List<Int>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val inclusionProbabilities: List<Double>? = null,
)

/**
 * Original iteration-one TRAIN backbone states are retained even when a requested root is absent.
 */
internal data class TokenPairedAnchor(
    val iteration: Int,
    val globalIndex: Int,
    val number: Int,
    val batchStartNumber: Int,
    val observation: TerritoryObservation,
    val originalProbabilities: List<Double>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val anchor94Probabilities: List<Double>? = null,
)

internal data class TokenPairedCanonical(
    val header: GameHeader,
    val entries: List<String>,
    val canonicalSha256: String,
    val playerDecisions: Int,
    val terminal: Boolean,
    val replayVerified: Boolean = true,
)

internal data class TokenPairedTail(
    val replicate: Int,
    val terminal: TokenValueOutcome,
    val canonicalEntries: List<String>,
    val canonicalSha256: String,
    val replayVerified: Boolean = true,
)

internal data class TokenPairedArm(
    val actionIndex: Int,
    val command: String,
    val inclusionProbability: Double,
    val outcomes: List<TokenPairedTail>,
)

/** Complete executor evidence. Public and outcome-only sidecars are written separately. */
internal data class TokenPairedOrigin(
    val kind: String = "token-paired-origin-1",
    val signature: String,
    val scopeSha256: String,
    val scienceSha256: String,
    val iteration: Int,
    val request: TokenPairedRequest,
    val streams: TokenPairedSeeds,
    val status: TokenPairedStatus,
    val missingReason: String?,
    val observedWindows: Int,
    val publicRoot: TokenPairedPublicRoot?,
    val anchors: List<TokenPairedAnchor>,
    val prefix: TokenPairedCanonical,
    val backbone: TokenPairedCanonical,
    val backboneTerminal: TokenValueOutcome,
    val arms: List<TokenPairedArm>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val backbonePublicCounterDiagnostics: Map<String, Any>? = null,
)

/**
 * Frozen current-policy full backbones and paired first-action interventions, without winner
 * labels.
 */
internal class TokenPairedCollector(
    private val input: TokenPairedInput,
    private val deadlineNanos: Long,
) {
    private data class Root(
        val state: GameState,
        val frozen: TokenValueFrozenRoot,
        val prefix: TokenPairedCanonical,
        val public: TokenPairedPublicRoot,
        val league: RevealedTokenCounterLeague?,
    )

    fun collect(globalIndex: Int): TokenPairedOrigin = run(globalIndex, includeTails = true)

    /** One normal whole-game reproduction; no counterfactual tail is repeated. */
    fun reproduce(globalIndex: Int): TokenPairedOrigin = run(globalIndex, includeTails = false)

    private fun run(globalIndex: Int, includeTails: Boolean): TokenPairedOrigin {
        require(globalIndex in input.range.indices) {
            "The origin must belong to this global shard."
        }
        val request = input.request(globalIndex)
        val streams = input.origins[globalIndex]
        val session = ExperimentSession.start(input.configuration, streams.gameSeed.toLong(), true)
        val actor = session.state.playerIds[request.seat]
        val window = TokenPairedWindow(request)
        val anchors = mutableListOf<TokenPairedAnchor>()
        val league = input.counterLeague(globalIndex)
        var frame: TokenPairedDecision? = null
        val normal =
            continuation(
                globalIndex,
                league = league,
                record = { decision ->
                    frame = decision
                    if (input.iteration == 1 && request.split == TokenPairedSplit.TRAIN) {
                        anchors +=
                            TokenPairedAnchor(
                                input.iteration,
                                globalIndex,
                                decision.number,
                                decision.batchStartNumber,
                                decision.observation,
                                decision.originalProbabilities,
                                decision.anchor94Probabilities,
                            )
                    }
                },
            )
        var root: Root? = null
        while (session.state.stage != Stage.FINISHED) {
            normal.check(session)
            val observations = session.actors().associateWith(session::observation)
            frame = null
            // All players commit from this same pre-batch view before any branch is made.
            val commands = normal.commands(session, observations)
            val own = observations[actor]
            if (own != null && window.observe(session.state.currentEpoch.number, own.decision)) {
                val selected = requireNotNull(frame) { "The current actor must record the root." }
                val fresh =
                    continuation(globalIndex, offset = session.decisions, league = league?.fork())
                val prototype = ExperimentSession(session.state, streams.future.first())
                check(fresh.commands(prototype, observations) == commands) {
                    "Fresh complete controllers must reproduce every sealed command at its absolute offset."
                }
                val public = publicRoot(selected, streams.proposalSeed)
                check(
                    public.observation.actions[public.sampledCommandIndex].command ==
                        commands[actor]
                ) {
                    "The retained sampled action must be the actual current-policy command."
                }
                root =
                    Root(
                        session.state,
                        TokenValueFrozenRoot(session, actor, commands),
                        canonical(session),
                        public,
                        league?.fork(),
                    )
            }
            session.apply(commands, deadlineNanos)
        }
        val backbone = canonical(session)
        val terminal =
            normal.finish(session, request.seat, input.configuration.id, streams.gameSeed.toLong())
        val captured = root
        val status =
            when {
                captured == null -> TokenPairedStatus.MISSING
                captured.public.observation.actions.size == 1 -> TokenPairedStatus.SINGLETON
                else -> TokenPairedStatus.READY
            }
        val arms =
            if (includeTails && status == TokenPairedStatus.READY) {
                tails(
                    globalIndex,
                    requireNotNull(captured) { "A ready origin requires its frozen root." },
                )
            } else emptyList()
        return TokenPairedOrigin(
            signature = input.signature,
            scopeSha256 = input.scopeSha256,
            scienceSha256 = input.scienceSha256,
            iteration = input.iteration,
            request = request,
            streams = streams,
            status = status,
            missingReason = if (captured == null) "TARGET_WINDOW_NOT_REACHED" else null,
            observedWindows = window.observed,
            publicRoot = captured?.public,
            anchors = anchors.toList(),
            prefix = captured?.prefix ?: backbone,
            backbone = backbone,
            backboneTerminal = terminal,
            arms = arms,
            backbonePublicCounterDiagnostics = league?.diagnostics(),
        )
    }

    private fun publicRoot(frame: TokenPairedDecision, seed: Long): TokenPairedPublicRoot {
        val focused =
            frame.anchorIndex?.let {
                TokenAnchoredProposals.sample(
                    frame.observation.actions.map { action -> action.command },
                    frame.observation.baselineIndex,
                    it,
                    frame.currentProbabilities,
                    frame.sampled.index,
                    input.proposalBudget,
                    seed,
                )
            }
        val proposal =
            if (focused != null) null
            else
                TokenPairedProposals.sample(
                    frame.observation.actions.size,
                    frame.anchorIndex ?: frame.observation.baselineIndex,
                    seed,
                )
        return TokenPairedPublicRoot(
            frame.number,
            frame.batchStartNumber,
            frame.botSeed,
            frame.actorSeed,
            frame.observation,
            frame.anchorIndex ?: frame.observation.baselineIndex,
            frame.sampled.index,
            frame.sampled.logProbability,
            frame.sampled.value,
            frame.currentProbabilities,
            frame.originalProbabilities,
            focused?.indices
                ?: requireNotNull(proposal) { "Legacy proposals are required." }.indices,
            proposal?.inclusionProbability ?: 0.0,
            frame.anchor94Probabilities,
            frame.anchor94Probabilities?.let { frame.observation.baselineIndex },
            focused?.mandatoryIndices,
            focused?.inclusionProbabilities,
        )
    }

    private fun tails(globalIndex: Int, root: Root): List<TokenPairedArm> {
        val request = input.request(globalIndex)
        val streams = input.origins[globalIndex]
        return root.public.proposedIndices.map { actionIndex ->
            val command = root.public.observation.actions[actionIndex].command
            val outcomes =
                streams.future.mapIndexed { replicate, chance ->
                    // This fork already retains the original absolute PLAYER count and prefix.
                    val branch = root.frozen.branch(command, chance, deadlineNanos)
                    val terminal =
                        continuation(globalIndex, league = root.league?.fork())
                            .finish(
                                branch,
                                request.seat,
                                input.configuration.id,
                                chance,
                            )
                    val suffix = branch.entries.drop(root.prefix.entries.size).map(Entry::toText)
                    check(
                        replay(root.state, suffix) == branch.state &&
                            branch.state.stage == Stage.FINISHED
                    ) {
                        "Every paired suffix must reproduce its exact terminal state."
                    }
                    TokenPairedTail(replicate, terminal, suffix, CardAdvantageExecutor.hash(suffix))
                }
            TokenPairedArm(
                actionIndex,
                command,
                root.public.inclusionProbabilities?.getOrNull(actionIndex)
                    ?: if (actionIndex == root.public.referenceIndex) 1.0
                    else root.public.inclusionProbability,
                outcomes,
            )
        }
    }

    private fun continuation(
        globalIndex: Int,
        offset: Int = 0,
        record: ((TokenPairedDecision) -> Unit)? = null,
        league: RevealedTokenCounterLeague? = input.counterLeague(globalIndex),
    ) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(globalIndex, record, league),
            input.origins[globalIndex].gameSeed,
            deadlineNanos,
            decisionOffset = offset,
        )

    private fun canonical(session: ExperimentSession): TokenPairedCanonical {
        val entries = session.entries.map(Entry::toText)
        check(replay(GameState(session.state.header), entries) == session.state) {
            "The complete recorded token backbone/prefix must replay exactly."
        }
        return TokenPairedCanonical(
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
}
