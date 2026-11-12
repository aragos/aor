package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchaseProjection

/**
 * Explicit future streams are shared by every sibling; they do not resample existing hidden cards.
 */
internal data class PurchaseSequenceOrigin(
    val gameSeed: Int,
    val seat: Int,
    val opponentIndices: List<Int>,
    val continuationSeeds: List<Long>,
) {
    init {
        require(seat in 0..3 && opponentIndices.size == 4) {
            "A teacher origin requires four explicit seat roles."
        }
        require(
            continuationSeeds.size == PurchaseSequenceProtocol.STREAMS &&
                continuationSeeds.distinct().size == continuationSeeds.size
        ) {
            "Every origin requires exactly two distinct shared continuation streams."
        }
    }
}

/** A fixed parsed study; seed allocation, campaign supervision and fitting remain external. */
internal class PurchaseSequenceStudy(
    val arena: ArenaSpecification,
    val origins: List<PurchaseSequenceOrigin>,
) {
    val configuration: TrainingConfiguration =
        requireNotNull(arena.configurations.singleOrNull()) {
            "The teacher profile requires exactly one frozen configuration."
        }

    init {
        require(configuration.playerCount == 4) {
            "This bounded teacher profile requires four players."
        }
        require(
            origins.size == PurchaseSequenceProtocol.ORIGINS &&
                origins.map { it.gameSeed }.distinct().size == origins.size
        ) {
            "The study requires 32 independent prospective origins, including any missing roots."
        }
        require(
            origins.all { origin -> origin.opponentIndices.all { it in arena.opponents.indices } }
        ) {
            "Every rival index must resolve in the frozen ordinary-model pool."
        }
        require(
            origins.flatMap { it.continuationSeeds }.distinct().size ==
                origins.size * PurchaseSequenceProtocol.STREAMS
        ) {
            "Continuation streams may be shared among siblings, never across independent origins."
        }
    }

    fun models(origin: PurchaseSequenceOrigin): List<BotModel> =
        origin.opponentIndices.mapIndexed { seat, index ->
            if (seat == origin.seat) arena.incumbent else arena.opponents[index]
        }

    fun controllers(origin: PurchaseSequenceOrigin): Map<Int, SimulationController> =
        arena.controllers(
            4,
            origin.seat,
            ArenaSide.INCUMBENT,
            arena.incumbent,
            origin.opponentIndices,
        )
}

/** The control applies only the first command, then the same full frozen continuation. */
internal enum class PurchaseSequenceArm {
    COMPLETE_PREFIX,
    FIRST_ACTION_ONLY,
}

internal enum class PurchaseSequenceStop {
    PREFIX_COMPLETE,
    INFORMATION_BOUNDARY,
    CHANGED_DECISION,
    CHANGED_OFFER,
    OWNER_FINISHED,
}

/**
 * Existing actor-safe observations only; authority state and private rival facts are never fields.
 */
internal data class PurchaseSequenceStep(
    val number: Int,
    val observation: BotObservation,
    val purchaseProjection: PurchaseProjection?,
    val chosenCommand: String,
    val forced: Boolean,
)

internal data class PurchaseSequenceEvidenceRef(val file: String, val sha256: String)

internal data class PurchaseSequenceTail(
    val arm: PurchaseSequenceArm,
    val commands: List<String>,
    val continuationSeed: Long,
    val stop: PurchaseSequenceStop,
    val steps: List<PurchaseSequenceStep>,
    val outcome: TokenValueOutcome,
    val canonicalSha256: String,
    val replayVerified: Boolean,
    val canonicalEvidence: PurchaseSequenceEvidenceRef,
)

internal data class PurchaseSequenceRoot(
    val number: Int,
    val observation: BotObservation,
    val projection: PurchaseProjection,
    val proposals: PurchaseSequenceProposals,
    val tails: List<PurchaseSequenceTail>,
)

/**
 * Missing roots remain explicit; every sibling return is retained without a privileged winner
 * label.
 */
internal data class PurchaseSequenceResult(
    val version: String = PurchaseSequenceProtocol.VERSION,
    val index: Int,
    val origin: PurchaseSequenceOrigin,
    val environmentSha256: String,
    val backboneCanonicalSha256: String,
    val backboneDecisions: Int,
    val backboneEvidence: PurchaseSequenceEvidenceRef,
    val exclusions: Map<PurchaseRootExclusion, Int>,
    val missingRoot: Boolean,
    val root: PurchaseSequenceRoot?,
)

internal class PurchaseSequenceTeacher(
    private val study: PurchaseSequenceStudy,
    private val deadlineNanos: Long,
    /** Executor-only canonical files remain separate from all actor observations and labels. */
    private val evidence: TrainingArtifacts,
) {
    fun collect(index: Int): PurchaseSequenceResult {
        require(index in study.origins.indices) {
            "Teacher origin index is outside the fixed study."
        }
        val origin = study.origins[index]
        val backboneFile = "origin-$index-backbone.json"
        require(evidence.read(backboneFile) == null) {
            "A retained origin cannot be silently rerun or overwritten."
        }
        val trace = Capture(origin.seat)
        val models = study.models(origin)
        val controllers = study.controllers(origin)
        val backbone =
            Simulation()
                .play(
                    configuration = study.configuration,
                    seed = origin.gameSeed,
                    competitors = models.map { Competitor(it, BotDifficulty.HARD) },
                    deadlineNanos = deadlineNanos,
                    verifyReplay = true,
                    trace = trace,
                    controllers = controllers,
                )
        val captured = trace.root
        val backboneEvidence =
            retain(
                backboneFile,
                mapOf(
                    "header" to trace.header,
                    "entries" to trace.entries,
                    "rootPlayerDecision" to captured?.number,
                    "rootEntryCount" to captured?.entryCount,
                ),
            )
        val root = captured?.let {
            val state =
                trace.entries.take(it.entryCount).fold(GameState(trace.header)) { current, text ->
                    checkTokenValueDeadline(deadlineNanos)
                    current.step(TextCodec.parse(text))
                }
            val actor = state.playerIds[origin.seat]
            check(BotObservations.from(state, actor) == it.observation) {
                "The prospective root must regenerate exactly."
            }
            check(trace.entries[it.entryCount] == "${actor.value}: pass") {
                "The retained canonical next command must be the incumbent's actual pass."
            }
            val reference =
                FrozenPolicyContinuation(
                        models,
                        study.controllers(origin),
                        origin.gameSeed,
                        deadlineNanos,
                        it.number,
                    )
                    .commands(
                        ExperimentSession(state, origin.continuationSeeds.first()),
                        mapOf(actor to it.observation),
                    )
            check(reference[actor] == "pass") {
                "Fresh complete controller routing at the absolute root index must reproduce pass."
            }
            val proposals = PurchaseSequencePlans.propose(state, actor, it.observation)
            val controls = proposals.plans.map { plan -> listOf(plan.commands.first()) }.distinct()
            val tails =
                listOf(
                        PurchaseSequenceArm.COMPLETE_PREFIX to
                            proposals.plans.map { plan -> plan.commands },
                        PurchaseSequenceArm.FIRST_ACTION_ONLY to controls,
                    )
                    .flatMap { (arm, prefixes) ->
                        prefixes.flatMapIndexed { planIndex, commands ->
                            origin.continuationSeeds.mapIndexed { replica, stream ->
                                val name =
                                    "origin-$index-${arm.name.lowercase()}-$planIndex-$replica.json"
                                tail(
                                    state,
                                    origin,
                                    it.number,
                                    arm,
                                    commands,
                                    stream,
                                    models,
                                    study.controllers(origin),
                                    name,
                                    backboneEvidence,
                                )
                            }
                        }
                    }
            check(
                tails.size <= PurchaseSequenceProtocol.PLANS * 2 * PurchaseSequenceProtocol.STREAMS
            ) {
                "An origin cannot exceed the declared conservative terminal-tail cap."
            }
            tails
                .groupBy { tail -> tail.commands to tail.continuationSeed }
                .values
                .forEach { duplicates ->
                    check(
                        duplicates
                            .map { tail -> tail.outcome to tail.canonicalSha256 }
                            .distinct()
                            .size == 1
                    ) {
                        "Identical forced commands and shared streams must have identical frozen continuations."
                    }
                }
            PurchaseSequenceRoot(
                it.number,
                it.observation,
                PurchaseObservationEncoder.project(
                    state.viewForPlayer(actor),
                    actor,
                    it.observation,
                ),
                proposals,
                tails,
            )
        }
        return PurchaseSequenceResult(
            index = index,
            origin = origin,
            environmentSha256 = CardAdvantageExecutor.hash(listOf(study.arena.identity())),
            backboneCanonicalSha256 = CardAdvantageExecutor.hash(trace.entries),
            backboneDecisions = backbone.steps,
            backboneEvidence = backboneEvidence,
            exclusions = trace.exclusions.toMap(),
            missingRoot = root == null,
            root = root,
        )
    }

    private fun tail(
        initial: GameState,
        origin: PurchaseSequenceOrigin,
        number: Int,
        arm: PurchaseSequenceArm,
        commands: List<String>,
        stream: Long,
        models: List<BotModel>,
        controllers: Map<Int, SimulationController>,
        evidenceFile: String,
        backboneEvidence: PurchaseSequenceEvidenceRef,
    ): PurchaseSequenceTail {
        val session = ExperimentSession(initial, stream, recordEntries = true)
        val actor = initial.playerIds[origin.seat]
        val continuation =
            FrozenPolicyContinuation(models, controllers, origin.gameSeed, deadlineNanos, number)
        val prefix =
            PurchaseSequenceExecution.force(session, actor, number, commands, deadlineNanos)
        val steps = prefix.steps.toMutableList()
        // These choices remain exactly normal: they are recorded but never added to a forced plan.
        while (session.state.stage == Stage.PURCHASE && session.state.flow.actor == actor) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val chosen = continuation.commands(session, observations)
            observations[actor]?.let { observation ->
                steps.add(
                    PurchaseSequenceExecution.step(
                        session,
                        actor,
                        number,
                        observation,
                        requireNotNull(chosen[actor]) {
                            "The frozen continuation must choose the acting player's command."
                        },
                        forced = false,
                    )
                )
            }
            session.apply(chosen, deadlineNanos)
        }
        val outcome = continuation.finish(session, origin.seat, study.configuration.id, stream)
        val canonical = session.entries.map(Entry::toText)
        val replay =
            canonical.fold(initial) { state, text ->
                checkTokenValueDeadline(deadlineNanos)
                state.step(TextCodec.parse(text))
            }
        check(replay == session.state) {
            "A completed purchase tail must replay through authoritative entries."
        }
        val canonicalEvidence =
            retain(
                evidenceFile,
                mapOf(
                    "backbone" to backboneEvidence,
                    "rootPlayerDecision" to number,
                    "arm" to arm,
                    "commands" to commands,
                    "continuationSeed" to stream,
                    "entries" to canonical,
                    "replayVerified" to true,
                ),
            )
        return PurchaseSequenceTail(
            arm,
            commands,
            stream,
            prefix.stop,
            steps.toList(),
            outcome,
            CardAdvantageExecutor.hash(canonical),
            true,
            canonicalEvidence,
        )
    }

    private fun retain(name: String, value: Any): PurchaseSequenceEvidenceRef {
        require(evidence.read(name) == null) {
            "Canonical teacher evidence must never overwrite an earlier attempt."
        }
        evidence.write(name, value)
        return PurchaseSequenceEvidenceRef(
            name,
            PurchaseJson.hash(evidence.directory.resolve(name)),
        )
    }

    private data class Captured(
        val number: Int,
        val entryCount: Int,
        val observation: BotObservation,
    )

    private class Capture(override val seat: Int) : SimulationTrace {
        var root: Captured? = null
        val exclusions = mutableMapOf<PurchaseRootExclusion, Int>()
        lateinit var header: GameHeader
        lateinit var entries: List<String>

        override fun decision(
            number: Int,
            snapshotEntryCount: Int,
            commandEntryIndex: Int,
            view: GameView,
            observation: BotObservation,
            chosen: BotCandidate,
            model: BotModel,
        ) {
            if (root != null) return
            val exclusion = PurchaseSequenceEligibility.exclusion(view, observation, chosen.command)
            if (exclusion != null) {
                exclusions[exclusion] = (exclusions[exclusion] ?: 0) + 1
                return
            }
            check(snapshotEntryCount == commandEntryIndex) {
                "A purchase root must be a single-player decision."
            }
            root = Captured(number, snapshotEntryCount, observation)
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }
}

internal data class PurchaseSequencePrefix(
    val stop: PurchaseSequenceStop,
    val steps: List<PurchaseSequenceStep>,
)

/**
 * Live execution rechecks every command; preview boundaries never authorize imagined child views.
 */
internal object PurchaseSequenceExecution {
    fun force(
        session: ExperimentSession,
        actor: PlayerId,
        offset: Int,
        commands: List<String>,
        deadlineNanos: Long,
    ): PurchaseSequencePrefix {
        val steps = mutableListOf<PurchaseSequenceStep>()
        for (command in commands) {
            checkTokenValueDeadline(deadlineNanos)
            if (session.state.stage != Stage.PURCHASE || session.state.flow.actor != actor) {
                return PurchaseSequencePrefix(PurchaseSequenceStop.OWNER_FINISHED, steps.toList())
            }
            val observation = session.observation(actor)
            if (observation.decision != PurchaseSequenceProtocol.DECISION) {
                return PurchaseSequencePrefix(PurchaseSequenceStop.CHANGED_DECISION, steps.toList())
            }
            if (observation.candidates.none { it.command == command }) {
                return PurchaseSequencePrefix(PurchaseSequenceStop.CHANGED_OFFER, steps.toList())
            }
            val preview =
                ActivityPlanning.preview(
                    session.state,
                    actor,
                    listOf(PurchaseSequencePlans.action(command)),
                )
            steps.add(step(session, actor, offset, observation, command, forced = true))
            session.apply(mapOf(actor to command), deadlineNanos)
            if (preview.boundary == PlanBoundary.INFORMATION) {
                return PurchaseSequencePrefix(
                    PurchaseSequenceStop.INFORMATION_BOUNDARY,
                    steps.toList(),
                )
            }
        }
        return PurchaseSequencePrefix(PurchaseSequenceStop.PREFIX_COMPLETE, steps.toList())
    }

    fun step(
        session: ExperimentSession,
        actor: PlayerId,
        offset: Int,
        observation: BotObservation,
        command: String,
        forced: Boolean,
    ): PurchaseSequenceStep =
        PurchaseSequenceStep(
            offset + session.decisions,
            observation,
            if (
                observation.decision in
                    setOf("purchase-shipping", PurchaseSequenceProtocol.DECISION)
            ) {
                PurchaseObservationEncoder.project(
                    session.state.viewForPlayer(actor),
                    actor,
                    observation,
                )
            } else {
                null
            },
            command,
            forced,
        )
}
