package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/** Independent proposal draws and shared sibling chance streams are executor provenance only. */
internal data class PurchasePlanStreams(val proposals: List<Long>, val futures: List<Long>) {
    init {
        require(proposals.size == 6 && futures.size == PurchasePlanProtocol.STREAMS) {
            "An origin requires six proposal streams and four paired future streams."
        }
        require((proposals + futures).distinct().size == 10) {
            "Proposal and future streams must be distinct within an origin."
        }
    }
}

/**
 * Fixed normal joint ecology. Raw bundle components and E/T are composed by their existing owner.
 */
internal class PurchasePlanStudy(
    val collection: JointCollectionInput,
    val streams: List<PurchasePlanStreams>,
) {
    init {
        require(
            collection.configuration.playerCount == 4 &&
                collection.seeds.size == 256 &&
                streams.size == 256 &&
                collection.ecology != null
        ) {
            "Plan ranking requires exactly256 normal four-player origins and explicit ecology."
        }
        require(collection.opponents.all { it.sha256 == collection.bundle.sha256 }) {
            "Every plan-ranking opponent must retain the same fixed joint actor bundle."
        }
        val all = streams.flatMap { it.proposals + it.futures } + collection.seeds.map(Int::toLong)
        require(all.distinct().size == all.size) {
            "Origin, proposal and future streams must be independent across whole worlds."
        }
    }

    fun epoch(index: Int): Int = 1 + (index / 4) % 3

    fun controllers(index: Int): Map<Int, SimulationController> {
        val actors =
            (0 until 4).associateWith {
                collection.bundle.controller(
                    collection.purchaseBaseline,
                    ordinary = collection.baseline.model,
                )
            }
        return requireNotNull(collection.ecology) { "The study requires explicit phase ecology." }
            .controllers(collection, index, actors)
    }

    fun policy(index: Int): PurchasePlanPolicy =
        FrozenPurchasePlanPolicy(
            collection.baseline.model,
            requireNotNull(controllers(index)[collection.seat(index)]) {
                "The controlled seat requires its complete frozen controller."
            },
            collection.purchaseBaseline.model,
            collection.bundle.purchase.model,
        )
}

internal enum class PurchasePlanOriginStatus {
    READY,
    MISSING,
    INCUMBENT_UNSUPPORTED,
}

/** This exact small object is the entire Python predictor payload; provenance stays separate. */
internal data class PublicPurchasePlanOrigin(
    val version: String = PurchasePlanProtocol.VERSION,
    val originIndex: Int,
    val status: PurchasePlanOriginStatus,
    val plans: List<PublicPurchasePlan>,
) {
    init {
        require(originIndex in 0 until 256) {
            "A public origin must belong to the fixed256-world bank."
        }
        require(
            if (status == PurchasePlanOriginStatus.MISSING) {
                plans.isEmpty()
            } else {
                plans.map { it.index } == (0 until PurchasePlanProtocol.PLANS).toList()
            }
        ) {
            "Missing origins have no plans; captured origins retain all eight ordered logical plans."
        }
        require(
            status == PurchasePlanOriginStatus.MISSING ||
                (status == PurchasePlanOriginStatus.READY) ==
                    (plans.first().status == PurchasePlanStatus.SUPPORTED)
        ) {
            "A ready origin requires its complete incumbent plan."
        }
        val kinds =
            listOf(PurchasePlanKind.INCUMBENT, PurchasePlanKind.ADVANCE_PASS) +
                List(3) { PurchasePlanKind.SAMPLED } +
                List(3) { PurchasePlanKind.UNIFORM }
        require(plans.isEmpty() || plans.map { it.kind } == kinds) {
            "The eight logical proposal kinds retain their fixed order."
        }
        for (plan in plans) {
            plan.aliasOf?.let { alias ->
                val original = plans[alias]
                require(
                    original.aliasOf == null &&
                        plan.actions == original.actions &&
                        plan.status == original.status &&
                        plan.exclusion == original.exclusion &&
                        plan.leaf == original.leaf
                ) {
                    "A logical alias must preserve exactly the same complete public plan."
                }
            }
        }
    }
}

/** Private canonical history never enters the leaf predictor. No tail is produced in this stage. */
internal data class PurchasePlanBackbone(
    val index: Int,
    val gameSeed: Int,
    val seat: Int,
    val assignedEpoch: Int,
    val header: GameHeader,
    val entries: List<String>,
    val rootNumber: Int?,
    val rootEntryCount: Int?,
    val actualRootCommand: String?,
    val rootObservation: BotObservation?,
)

internal data class PurchasePlanPublicResult(
    val public: PublicPurchasePlanOrigin,
    val backbone: PurchasePlanBackbone,
)

/** Exactly one unchanged normal backbone; canonical traversal is validation, not another game. */
internal class PurchasePlanPublicCollector(
    private val study: PurchasePlanStudy,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): PurchasePlanPublicResult {
        require(index in 0 until 256) {
            "The assigned origin must belong to the fixed256-world bank."
        }
        val input = study.collection
        val seat = input.seat(index)
        val trace = Capture(seat, study.epoch(index))
        Simulation()
            .play(
                input.configuration,
                input.seeds[index],
                List(4) { Competitor(input.baseline.model, BotDifficulty.HARD) },
                deadlineNanos,
                trace = trace,
                controllers = study.controllers(index),
            )
        val recorded = trace.root
        val plans =
            if (recorded == null) {
                emptyList()
            } else {
                val state =
                    trace.entries.take(recorded.entryCount).fold(GameState(trace.header)) {
                        state,
                        text ->
                        checkTokenValueDeadline(deadlineNanos)
                        state.step(TextCodec.parse(text))
                    }
                require(
                    BotObservations.from(state, state.playerIds[seat]) == recorded.observation
                ) {
                    "The public root must reconstruct exactly from its canonical prefix."
                }
                require(
                    trace.entries[recorded.entryCount] ==
                        "${state.playerIds[seat].value}: ${recorded.command}"
                ) {
                    "The retained root command must be the actual canonical normal-policy choice."
                }
                val proposals =
                    PurchasePlanProposals(study.policy(index), deadlineNanos)
                        .propose(
                            state,
                            seat,
                            input.seeds[index],
                            recorded.number,
                            recorded.command,
                            study.streams[index].proposals,
                        )
                if (proposals.first().status == PurchasePlanStatus.SUPPORTED) {
                    PurchasePlanReference.verify(
                        state,
                        state.playerIds[seat],
                        proposals.first(),
                        trace.entries,
                        recorded.entryCount,
                        deadlineNanos,
                    )
                }
                proposals
            }
        val status =
            when {
                plans.isEmpty() -> PurchasePlanOriginStatus.MISSING
                plans.first().status != PurchasePlanStatus.SUPPORTED ->
                    PurchasePlanOriginStatus.INCUMBENT_UNSUPPORTED
                else -> PurchasePlanOriginStatus.READY
            }
        return PurchasePlanPublicResult(
            PublicPurchasePlanOrigin(originIndex = index, status = status, plans = plans),
            PurchasePlanBackbone(
                index,
                input.seeds[index],
                seat,
                study.epoch(index),
                trace.header,
                trace.entries,
                recorded?.number,
                recorded?.entryCount,
                recorded?.command,
                recorded?.observation,
            ),
        )
    }

    private data class Root(
        val number: Int,
        val entryCount: Int,
        val command: String,
        val observation: BotObservation,
    )

    private class Capture(override val seat: Int, private val epoch: Int) : SimulationTrace {
        var root: Root? = null
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
            if (
                root != null ||
                    view.currentEpoch != epoch ||
                    observation.decision != "purchase-advances" ||
                    observation.candidates.size <= 1
            ) {
                return
            }
            require(snapshotEntryCount == commandEntryIndex) {
                "A purchase root must be a single-owner decision."
            }
            root = Root(number, snapshotEntryCount, chosen.command, observation)
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }
}

/**
 * Post-proposal validation only: complete incumbent commands must match the already recorded
 * unchanged normal backbone through its genuine owner boundary. These entries never propose plans.
 */
internal object PurchasePlanReference {
    fun verify(
        initial: GameState,
        owner: PlayerId,
        plan: PublicPurchasePlan,
        entries: List<String>,
        entryCount: Int,
        deadline: Long,
    ) {
        require(
            plan.index == 0 &&
                plan.kind == PurchasePlanKind.INCUMBENT &&
                plan.status == PurchasePlanStatus.SUPPORTED
        ) {
            "Full-reference validation requires the supported incumbent plan."
        }
        var state = initial
        var compared = 0
        while (
            state.stage == Stage.PURCHASE &&
                state.flow.actor == owner &&
                state.playerState(owner).misery != MiseryStep.CHAOS
        ) {
            checkTokenValueDeadline(deadline)
            require(compared < plan.actions.size && entryCount + compared in entries.indices) {
                "The normal backbone continued beyond the projected complete incumbent plan."
            }
            val entry = TextCodec.parse(entries[entryCount + compared])
            val expected = plan.actions[compared]
            require(
                entry is Entry.Action &&
                    entry.actor == owner &&
                    entry.command.toText() == expected.command &&
                    BotObservations.from(state, owner).decision == expected.decision
            ) {
                "Every projected incumbent choice must match the unchanged normal backbone."
            }
            state = state.step(entry)
            compared++
        }
        require(compared == plan.actions.size) {
            "The normal owner boundary occurred before the projected incumbent plan ended."
        }
    }
}

/**
 * Live forcing never assumes a preview authorized a changed decision or hidden-information step.
 */
internal object PurchasePlanExecution {
    fun force(
        session: ExperimentSession,
        owner: PlayerId,
        plan: PublicPurchasePlan,
        deadline: Long,
    ) {
        require(plan.status == PurchasePlanStatus.SUPPORTED) {
            "Only a complete supported plan may be executed."
        }
        for ((index, action) in plan.actions.withIndex()) {
            checkTokenValueDeadline(deadline)
            require(session.state.stage == Stage.PURCHASE && session.state.flow.actor == owner) {
                "The frozen plan owner finished before its recorded final command."
            }
            val observation = session.observation(owner)
            require(
                observation.decision == action.decision &&
                    action.command in session.state.legalChoices(owner).commands
            ) {
                "A frozen plan no longer matches its fresh authoritative decision and offers."
            }
            val preview = ActivityPlanning.preview(session.state, owner, listOf(action))
            require(
                !preview.hypothetical &&
                    preview.boundary !in
                        setOf(
                            PlanBoundary.INVALID,
                            PlanBoundary.INFORMATION,
                        )
            ) {
                "A live plan cannot cross an invalid, hypothetical or information boundary."
            }
            if (index == plan.actions.lastIndex) {
                require(preview.boundary == PlanBoundary.FINISHED) {
                    "The complete plan must end at its actual owner boundary."
                }
                require(
                    plan.leaf?.stateFeatures ==
                        AfterstateValueEncoder.from(
                            preview.game,
                            owner,
                            AfterstateContext.OWNER_EXIT,
                        )
                ) {
                    "The live complete endpoint differs from its frozen public leaf."
                }
            } else {
                require(preview.boundary == PlanBoundary.INCOMPLETE) {
                    "A complete plan cannot conceal an earlier owner finish."
                }
            }
            session.apply(mapOf(owner to action.command), deadline)
        }
    }
}
