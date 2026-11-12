package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/**
 * Existing owner view/observation only. Public white markers, bonus variant and commodity
 * adjustments remain context; no private draw identity, future shortage or terminal label enters.
 */
internal data class AcquisitionActorInput(val view: GameView, val observation: BotObservation)

internal data class AcquisitionCapturedDecision(
    val number: Int,
    val entryCount: Int,
    val incumbentCommand: String,
    val initialExpansionPool: Int?,
    val actorInput: AcquisitionActorInput,
)

/** Pure prospective selector shared by capture and synthetic boundary tests. */
internal class AcquisitionRootSelection(private val request: AcquisitionRequest) {
    private var expansionTurn: Int? = null
    private var initialPool: Int? = null
    private val captures = mutableMapOf<AcquisitionRoute, AcquisitionCapturedDecision>()
    val captured: Map<AcquisitionRoute, AcquisitionCapturedDecision>
        get() = captures.toMap()

    private val counts = mutableMapOf<AcquisitionRoute, MutableMap<String, Int>>()

    fun coverage(): Map<AcquisitionRoute, Map<String, Int>> =
        AcquisitionRoute.entries.associateWith { counts[it]?.toMap().orEmpty() }

    fun observe(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: String,
    ) {
        val own = view.players.single { it.id.value == observation.playerId }
        if (observation.decision == "expand" && expansionTurn != view.turnNumber) {
            expansionTurn = requireNotNull(view.turnNumber) { "Expansion requires a turn." }
            initialPool = own.poolTokens
        }
        if (view.currentEpoch != request.epoch) return
        val route =
            AcquisitionRoute.entries.singleOrNull { it.decision == observation.decision } ?: return
        val offered = observation.candidates.map(BotCandidate::command)
        val counters = counts.getOrPut(route) { mutableMapOf() }
        fun count(key: String) {
            counters[key] = (counters[key] ?: 0) + 1
        }
        count("decisions")
        if (route.buyCommand !in offered) return
        count("offered")
        val choiceKind =
            when (chosen) {
                route.buyCommand -> "incumbentBuys"
                "pass" -> "passes"
                else -> "other"
            }
        count(choiceKind)
        if (
            !request.eligible(
                route,
                view.currentEpoch,
                observation.decision,
                offered,
                chosen,
                initialPool,
                own.poolTokens,
            )
        ) {
            return
        }
        count("eligibleDeclines")
        if (route in captures) return
        check(snapshotEntryCount == commandEntryIndex) {
            "Acquisition roots must be ordinary single-player decisions."
        }
        captures[route] =
            AcquisitionCapturedDecision(
                number,
                snapshotEntryCount,
                chosen,
                initialPool.takeIf { route == AcquisitionRoute.EXPANSION },
                AcquisitionActorInput(view, observation),
            )
    }
}

/** Complete generated backbones and all paired arms; no best-action selection or fitting. */
internal class AcquisitionPilotCollector(
    private val input: AcquisitionPilotInput,
    private val deadlineNanos: Long,
) {
    private class Trace(val request: AcquisitionRequest) : SimulationTrace {
        override val seat = request.seat
        val selection = AcquisitionRootSelection(request)
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
        ) =
            selection.observe(
                number,
                snapshotEntryCount,
                commandEntryIndex,
                view,
                observation,
                chosen.command,
            )

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    fun collect(index: Int): Map<String, Any> {
        val request = AcquisitionRequest(index)
        val seeds = input.origins[index]
        val trace = Trace(request)
        val backbone =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = seeds.gameSeed,
                    competitors = List(4) { Competitor(input.arena.candidate, BotDifficulty.HARD) },
                    deadlineNanos = deadlineNanos,
                    verifyReplay = true,
                    trace = trace,
                    controllers = input.controllers(request.seat),
                )
        val roots =
            AcquisitionRoute.entries.map { route ->
                val root = trace.selection.captured[route]
                val identity = mapOf("route" to route.name, "chanceSeeds" to seeds.future(route))
                if (root == null) {
                    identity + mapOf("status" to "MISSING", "reason" to "NO_ELIGIBLE_DECLINE")
                } else {
                    identity + evaluate(trace, root, route, seeds)
                }
            }
        return mapOf(
            "kind" to "acquisition-origin-1",
            "signature" to input.signature,
            "request" to request,
            "gameSeed" to seeds.gameSeed,
            "coverage" to trace.selection.coverage(),
            "backbone" to
                mapOf(
                    "header" to trace.header,
                    "entries" to trace.entries,
                    "canonicalSha256" to CardAdvantageExecutor.hash(trace.entries),
                    "playerDecisions" to backbone.steps,
                    "replayVerified" to true,
                ),
            "roots" to roots,
        )
    }

    private fun evaluate(
        trace: Trace,
        root: AcquisitionCapturedDecision,
        route: AcquisitionRoute,
        seeds: AcquisitionOriginSeeds,
    ): Map<String, Any> {
        val prefix = trace.entries.take(root.entryCount)
        val state =
            prefix.fold(GameState(trace.header)) { state, entry ->
                checkTokenValueDeadline(deadlineNanos)
                state.step(TextCodec.parse(entry))
            }
        val actor = state.playerIds[trace.seat]
        check(
            state.viewForDecision(actor) == root.actorInput.view &&
                BotObservations.from(state, actor) == root.actorInput.observation
        ) {
            "The canonical prefix must reproduce the exact captured actor input."
        }
        check(
            TextCodec.parseAction(root.incumbentCommand, state, actor).toText() ==
                trace.entries[root.entryCount]
        ) {
            "The incumbent action must be the actual next canonical backbone action."
        }
        val continuation =
            FrozenPolicyContinuation(
                List(4) { input.arena.candidate },
                input.controllers(trace.seat),
                seeds.gameSeed,
                deadlineNanos,
                decisionOffset = root.number,
            )
        val prototype = ExperimentSession(state, seeds.future(route).first(), recordEntries = true)
        check(prototype.actors() == listOf(actor)) {
            "An acquisition root must have one deciding actor."
        }
        check(
            continuation.commands(prototype, mapOf(actor to root.actorInput.observation))[actor] ==
                root.incumbentCommand
        ) {
            "The exact frozen controller and absolute policy seed must reproduce the incumbent."
        }
        val arms =
            listOf("REFERENCE" to root.incumbentCommand, "BUY" to route.buyCommand).map {
                (role, command) ->
                val outcomes =
                    seeds.future(route).mapIndexed { replicate, chance ->
                        val session = prototype.fork(chance)
                        session.apply(mapOf(actor to command), deadlineNanos)
                        val outcome =
                            continuation.finish(session, trace.seat, input.configuration.id, chance)
                        val canonical = session.entries.map(Entry::toText)
                        val replay =
                            canonical.fold(state) { current, entry ->
                                checkTokenValueDeadline(deadlineNanos)
                                current.step(TextCodec.parse(entry))
                            }
                        check(replay == session.state && replay.stage == Stage.FINISHED) {
                            "Every acquisition arm must reproduce its complete canonical terminal suffix."
                        }
                        mapOf(
                            "replicate" to replicate,
                            "terminal" to outcome,
                            "canonicalEntries" to canonical,
                            "canonicalSha256" to CardAdvantageExecutor.hash(canonical),
                            "replayVerified" to true,
                        )
                    }
                mapOf("role" to role, "command" to command, "outcomes" to outcomes)
            }
        return mapOf(
            "status" to "COLLECTED",
            "actorInput" to root.actorInput,
            "provenance" to
                mapOf(
                    "absolutePlayerDecision" to root.number,
                    "prefixEntryCount" to root.entryCount,
                    "prefixSha256" to CardAdvantageExecutor.hash(prefix),
                    "initialExpansionPool" to root.initialExpansionPool,
                    "incumbentCommand" to root.incumbentCommand,
                    "exactPrefixAndIncumbentVerified" to true,
                ),
            "arms" to arms,
        )
    }
}
