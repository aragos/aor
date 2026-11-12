package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservations

/**
 * Stops the generated prefix at its first branching assigned-phase window. Prefix PASS overrides
 * never enter tail controllers. Reseeding retains the origin's existing deck/hands; these are
 * conditional action returns, not information-safe best-action labels.
 */
internal class RenaissancePhaseCollector(
    private val input: RenaissancePhaseInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): Map<String, Any> {
        val request = RenaissancePhaseRequest(index)
        val seeds = input.origins[index]
        val session = ExperimentSession.start(input.configuration, seeds.gameSeed.toLong(), true)
        val actor = session.state.playerIds[request.seat]
        val continuation = continuation(request, seeds)
        val windows = mutableListOf<Map<String, Any>>()
        while (session.state.stage != Stage.FINISHED) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            val observation = observations[actor]
            if (observation?.decision == "renaissance") {
                check(session.actors() == listOf(actor)) {
                    "A Renaissance root must be an ordinary single-player window."
                }
                val offered = observation.candidates.map(BotCandidate::command)
                check(
                    offered.size in 1..3 &&
                        offered.toSet() == session.state.legalChoices(actor).commands.toSet()
                ) {
                    "The actor must expose the complete owner Renaissance action set."
                }
                val incumbent =
                    requireNotNull(commands[actor]) { "The incumbent command is required." }
                val captures = request.captures(session.state.stage, observation)
                windows +=
                    mapOf(
                        "absolutePlayerDecision" to session.decisions,
                        "entryIndex" to session.entries.size,
                        "turn" to
                            requireNotNull(session.state.turnNumber) {
                                "A window requires a turn."
                            },
                        "phase" to session.state.stage.name,
                        "offered" to offered,
                        "incumbentCommand" to incumbent,
                        "passedInPrefix" to !captures,
                        "captured" to captures,
                        "assignedPassOnly" to
                            (session.state.stage == request.targetPhase && offered.size == 1),
                    )
                if (captures) {
                    val prefix = prefix(session)
                    return identity(request, seeds, prefix, windows) +
                        evaluate(request, seeds, session, incumbent)
                }
            }
            val prefixCommands =
                if (observation == null) commands
                else
                    commands +
                        (actor to
                            request.prefixCommand(
                                observation,
                                requireNotNull(commands[actor]) {
                                    "An observed controlled player requires its incumbent command."
                                },
                            ))
            session.apply(prefixCommands, deadlineNanos)
        }
        return identity(request, seeds, prefix(session), windows) +
            mapOf(
                "status" to "MISSING",
                "reason" to "NO_BRANCHING_ASSIGNED_PHASE",
                "arms" to emptyList<Any>(),
            )
    }

    private fun continuation(
        request: RenaissancePhaseRequest,
        seeds: RenaissancePhaseSeeds,
        offset: Int = 0,
    ) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(request.seat),
            seeds.gameSeed,
            deadlineNanos,
            decisionOffset = offset,
        )

    private fun prefix(session: ExperimentSession): Map<String, Any> {
        val entries = session.entries.map(Entry::toText)
        val replay =
            entries.fold(GameState(session.state.header)) { state, text ->
                checkTokenValueDeadline(deadlineNanos)
                state.step(TextCodec.parse(text))
            }
        check(replay == session.state) {
            "The canonical prefix must reproduce the exact captured state."
        }
        return mapOf(
            "header" to session.state.header,
            "entries" to entries,
            "canonicalSha256" to CardAdvantageExecutor.hash(entries),
            "playerDecisions" to session.decisions,
            "terminal" to (session.state.stage == Stage.FINISHED),
            "replayVerified" to true,
        )
    }

    private fun identity(
        request: RenaissancePhaseRequest,
        seeds: RenaissancePhaseSeeds,
        prefix: Map<String, Any>,
        windows: List<Map<String, Any>>,
    ): Map<String, Any> =
        mapOf(
            "kind" to "renaissance-phase-origin-1",
            "signature" to input.signature,
            "request" to request,
            "gameSeed" to seeds.gameSeed,
            "chanceSeeds" to seeds.future,
            "prefix" to prefix,
            "windows" to windows,
        )

    private fun evaluate(
        request: RenaissancePhaseRequest,
        seeds: RenaissancePhaseSeeds,
        root: ExperimentSession,
        incumbent: String,
    ): Map<String, Any> {
        val state = root.state
        val actor = state.playerIds[request.seat]
        val observation = BotObservations.from(state, actor)
        val prototype = ExperimentSession(state, seeds.future.first(), recordEntries = true)
        val baseline = continuation(request, seeds, root.decisions)
        check(baseline.commands(prototype, mapOf(actor to observation))[actor] == incumbent) {
            "Fresh normal controllers at the absolute decision offset must reproduce the incumbent."
        }
        val arms =
            observation.candidates.mapIndexed { action, candidate ->
                val outcomes =
                    seeds.future.mapIndexed { replicate, chance ->
                        val session = prototype.fork(chance)
                        session.apply(mapOf(actor to candidate.command), deadlineNanos)
                        val terminal =
                            continuation(request, seeds, root.decisions)
                                .finish(session, request.seat, input.configuration.id, chance)
                        val entries = session.entries.map(Entry::toText)
                        val replay =
                            entries.fold(state) { current, text ->
                                checkTokenValueDeadline(deadlineNanos)
                                current.step(TextCodec.parse(text))
                            }
                        check(replay == session.state && replay.stage == Stage.FINISHED) {
                            "Every complete Renaissance suffix must reproduce its exact terminal state."
                        }
                        mapOf(
                            "replicate" to replicate,
                            "terminal" to terminal,
                            "canonicalEntries" to entries,
                            "canonicalSha256" to CardAdvantageExecutor.hash(entries),
                            "replayVerified" to true,
                        )
                    }
                mapOf(
                    "actionIndex" to action,
                    "command" to candidate.command,
                    "outcomes" to outcomes,
                )
            }
        return mapOf(
            "status" to "COLLECTED",
            "actorInput" to AcquisitionActorInput(state.viewForDecision(actor), observation),
            "provenance" to
                mapOf(
                    "absolutePlayerDecision" to root.decisions,
                    "prefixEntryCount" to root.entries.size,
                    "incumbentCommand" to incumbent,
                    "exactPrefixAndIncumbentVerified" to true,
                ),
            "arms" to arms,
        )
    }
}
