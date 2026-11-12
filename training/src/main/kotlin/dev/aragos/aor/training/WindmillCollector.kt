package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/**
 * Actor-safe state and unchanged ordinary encoder, separate from authority evidence and returns.
 */
internal data class WindmillActorInput(val view: GameView, val observation: BotObservation)

/**
 * Capture the first controlled-seat Windmill opportunity in its prospective assigned epoch. Every
 * seat uses the identical frozen G3 card/purchase bundle and complete E/T/M8/F0 roles. Fork seeds
 * retain existing hands/deck. Paired returns are conditional on an independent origin, never
 * information-safe winner labels. All policies receive only their ordinary actor observation.
 */
internal class WindmillCollector(
    private val input: WindmillInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): Map<String, Any> {
        val request = WindmillRequest(index)
        val seeds = input.origins[index]
        val session = ExperimentSession.start(input.configuration, seeds.gameSeed.toLong(), true)
        val continuation = continuation(seeds, request.seat)
        val windows = mutableListOf<Map<String, Any>>()
        while (session.state.stage != Stage.FINISHED) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            val windmill =
                observations.entries.singleOrNull {
                    it.value.decision == "windmill" &&
                        session.state.playerIds.indexOf(it.key) == request.seat
                }
            if (windmill != null) {
                val (actor, observation) = windmill
                check(session.actors() == listOf(actor)) {
                    "A Windmill player opportunity must have exactly one deciding actor."
                }
                val offered = observation.candidates.map(BotCandidate::command)
                check(
                    offered.size == 2 &&
                        offered.toSet() == setOf("windmill", "pass") &&
                        offered.toSet() == session.state.legalChoices(actor).commands.toSet()
                ) {
                    "The actor must expose every owner windmill command and PASS."
                }
                val incumbent = requireNotNull(commands[actor]) { "The incumbent is required." }
                val seat = session.state.playerIds.indexOf(actor)
                val captures =
                    request.captures(session.state.currentEpoch.number, seat, observation)
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
                "reason" to "NO_CONTROLLED_WINDMILL_IN_ASSIGNED_EPOCH",
                "arms" to emptyList<Any>(),
            )
    }

    private fun continuation(seeds: WindmillSeeds, seat: Int, offset: Int = 0) =
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
        request: WindmillRequest,
        seeds: WindmillSeeds,
        prefix: Map<String, Any>,
        windows: List<Map<String, Any>>,
    ): Map<String, Any> =
        mapOf(
            "kind" to "windmill-origin-1",
            "signature" to input.signature,
            "request" to request,
            "gameSeed" to seeds.gameSeed,
            "chanceSeeds" to seeds.future,
            "prefix" to prefix,
            "windows" to windows,
        )

    private fun evaluate(
        seeds: WindmillSeeds,
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
                        val normal = continuation(seeds, seat, root.decisions)
                        val offerCard =
                            captureImmediateOffer(
                                session,
                                actor,
                                candidate.command,
                                normal,
                                root.decisions,
                            )
                        val terminal = normal.finish(session, seat, input.configuration.id, chance)
                        val entries = session.entries.map(Entry::toText)
                        val replay =
                            entries.fold(state) { current, text ->
                                checkTokenValueDeadline(deadlineNanos)
                                current.step(TextCodec.parse(text))
                            }
                        check(replay == session.state && replay.stage == Stage.FINISHED) {
                            "Every complete windmill suffix must reproduce its exact terminal state."
                        }
                        mapOf(
                            "replicate" to replicate,
                            "immediateOfferCard" to offerCard,
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
            "actorInput" to WindmillActorInput(state.viewForDecision(actor), observation),
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

    /**
     * Resolve no new branch: after the root's ATTEMPT chance, record and execute the normal
     * immediate offered-card choice once. Later trade windows are not attributed to this root. The
     * absolute index counts player commands, including the already forced root action.
     */
    internal fun captureImmediateOffer(
        session: ExperimentSession,
        actor: PlayerId,
        rootCommand: String,
        normal: FrozenPolicyContinuation,
        offset: Int,
    ): List<Map<String, Any>> {
        if (rootCommand != "windmill" || session.state.stage == Stage.FINISHED) return emptyList()
        val observations = session.actors().associateWith(session::observation)
        val observation = observations[actor]
        if (observation?.decision != "offer-card") return emptyList()
        check(observations.keys == setOf(actor)) {
            "A root-linked card offer needs its sole actor."
        }
        normal.check(session)
        val commands = normal.commands(session, observations)
        val command =
            requireNotNull(commands[actor]) { "The normal offer-card choice is required." }
        check(observation.candidates.any { it.command == command }) {
            "The recorded normal offer-card command must be actor-visible and offered."
        }
        val receipt =
            mapOf(
                "absolutePlayerDecision" to offset + session.decisions,
                "suffixEntryIndex" to session.entries.size,
                "actorInput" to
                    WindmillActorInput(session.state.viewForDecision(actor), observation),
                "normalCommand" to command,
            )
        session.apply(commands, deadlineNanos)
        return listOf(receipt)
    }
}
