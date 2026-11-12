package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/**
 * Actor-safe state and unchanged ordinary encoder, separate from authority evidence and returns.
 */
internal data class CancellationActorInput(val view: GameView, val observation: BotObservation)

/**
 * Capture the first branching cancellation in the assigned epoch, for its actual first player.
 * Every seat has identical frozen roles; choosing the evaluated seat at the root changes no policy.
 * Fork seeds retain existing hands/deck. Paired returns are conditional on an independent origin,
 * never information-safe winner labels. All policies receive only their ordinary actor observation.
 */
internal class CancellationCollector(
    private val input: CancellationInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): Map<String, Any> {
        val request = CancellationRequest(index)
        val seeds = input.origins[index]
        val session = ExperimentSession.start(input.configuration, seeds.gameSeed.toLong(), true)
        // Homogeneous roles make this bookkeeping seat immaterial before a root exists.
        val continuation = continuation(seeds, seat = 0)
        val windows = mutableListOf<Map<String, Any>>()
        while (session.state.stage != Stage.FINISHED) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            val cancellation =
                observations.entries.singleOrNull {
                    it.value.decision == "cancel-adjustment"
                }
            if (cancellation != null) {
                val (actor, observation) = cancellation
                check(
                    session.actors() == listOf(actor) && actor == session.state.turnOrder.first()
                ) {
                    "A cancellation window must belong to the actual first player alone."
                }
                val offered = observation.candidates.map(BotCandidate::command)
                check(
                    offered.size in 1..3 &&
                        "pass" in offered &&
                        offered.toSet() == session.state.legalChoices(actor).commands.toSet()
                ) {
                    "The actor must expose every owner cancellation command and PASS."
                }
                val incumbent = requireNotNull(commands[actor]) { "The incumbent is required." }
                val captures = request.captures(session.state.currentEpoch.number, observation)
                val seat = session.state.playerIds.indexOf(actor)
                windows +=
                    mapOf(
                        "absolutePlayerDecision" to session.decisions,
                        "entryIndex" to session.entries.size,
                        "turn" to
                            requireNotNull(session.state.turnNumber) { "A window needs a turn." },
                        "epoch" to session.state.currentEpoch.number,
                        "seat" to seat,
                        "actor" to actor.value,
                        "offered" to offered,
                        "incumbentCommand" to incumbent,
                        "captured" to captures,
                    )
                if (captures) {
                    return identity(request, seeds, prefix(session), windows) +
                        evaluate(seeds, session, seat, incumbent)
                }
            }
            session.apply(commands, deadlineNanos)
        }
        return identity(request, seeds, prefix(session), windows) +
            mapOf(
                "status" to "MISSING",
                "reason" to "NO_BRANCHING_ASSIGNED_EPOCH",
                "arms" to emptyList<Any>(),
            )
    }

    private fun continuation(seeds: CancellationSeeds, seat: Int, offset: Int = 0) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(seat),
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
        check(replay == session.state) { "The canonical prefix must reproduce the exact root." }
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
        request: CancellationRequest,
        seeds: CancellationSeeds,
        prefix: Map<String, Any>,
        windows: List<Map<String, Any>>,
    ): Map<String, Any> =
        mapOf(
            "kind" to "cancellation-origin-1",
            "signature" to input.signature,
            "request" to request,
            "gameSeed" to seeds.gameSeed,
            "chanceSeeds" to seeds.future,
            "prefix" to prefix,
            "windows" to windows,
        )

    private fun evaluate(
        seeds: CancellationSeeds,
        root: ExperimentSession,
        seat: Int,
        incumbent: String,
    ): Map<String, Any> {
        val state = root.state
        val actor = state.playerIds[seat]
        val observation = BotObservations.from(state, actor)
        val prototype = ExperimentSession(state, seeds.future.first(), recordEntries = true)
        val baseline = continuation(seeds, seat, root.decisions)
        check(baseline.commands(prototype, mapOf(actor to observation))[actor] == incumbent) {
            "Fresh normal controllers at the absolute player offset must reproduce the incumbent."
        }
        val arms =
            observation.candidates.mapIndexed { action, candidate ->
                val outcomes =
                    seeds.future.mapIndexed { replicate, chance ->
                        val session = prototype.fork(chance)
                        session.apply(mapOf(actor to candidate.command), deadlineNanos)
                        val terminal =
                            continuation(seeds, seat, root.decisions)
                                .finish(session, seat, input.configuration.id, chance)
                        val entries = session.entries.map(Entry::toText)
                        val replay =
                            entries.fold(state) { current, text ->
                                checkTokenValueDeadline(deadlineNanos)
                                current.step(TextCodec.parse(text))
                            }
                        check(replay == session.state && replay.stage == Stage.FINISHED) {
                            "Every complete cancellation suffix must reproduce its exact terminal state."
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
            "actorInput" to CancellationActorInput(state.viewForDecision(actor), observation),
            "provenance" to
                mapOf(
                    "seat" to seat,
                    "actor" to actor.value,
                    "absolutePlayerDecision" to root.decisions,
                    "prefixEntryCount" to root.entries.size,
                    "incumbentCommand" to incumbent,
                    "exactPrefixAndIncumbentVerified" to true,
                ),
            "arms" to arms,
        )
    }
}
