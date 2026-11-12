package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.ExpansionBonusProjection
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.ExpansionPairedEncoder

internal enum class CardStyleInteractionMissing {
    TARGET_WINDOW_NONBRANCHING,
    TARGET_WINDOW_NOT_REACHED,
    CARD_UNAVAILABLE,
}

internal data class CardStyleInteractionArm(
    val command: String,
    val outcomes: List<ExpansionPairedTail>,
)

/** Both logical arms resolve to the same execution when the unchanged E command already buys. */
internal data class CardStyleInteractionResult(
    val style: CardStyleInteractionStyle,
    val incumbentIndex: Int,
    val acquireIndex: Int,
    val arms: List<CardStyleInteractionArm>,
)

internal data class CardStyleInteractionOrigin(
    val kind: String = "card-continuation-origin-1",
    val signature: String,
    val request: CardStyleInteractionRequest,
    val streams: CardStyleInteractionSeeds,
    val status: ExpansionOriginStatus,
    val missingReason: CardStyleInteractionMissing?,
    val firstExpansionTurn: Int?,
    val observedWindows: Int,
    val prefix: ExpansionPairedPrefix,
    val actorInput: ExpansionPairedActorInput?,
    val incumbentCommand: String?,
    val exactIncumbentVerified: Boolean,
    val continuations: List<CardStyleInteractionResult>,
)

/** Fixed public root, then persistent own-seat C/P styles under shared future chance streams. */
internal class CardStyleInteractionCollector(
    private val input: CardStyleInteractionInput,
    private val deadlineNanos: Long,
) {
    fun normal(
        style: CardStyleInteractionStyle,
        streams: CardStyleInteractionSeeds,
        seat: Int,
        offset: Int = 0,
    ) =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            input.controllers(style, seat),
            streams.gameSeed,
            deadlineNanos,
            decisionOffset = offset,
        )

    fun collect(index: Int): CardStyleInteractionOrigin {
        val request = CardStyleInteractionRequest(index)
        val streams = input.origins[index]
        val session = ExperimentSession.start(input.configuration, streams.gameSeed.toLong(), true)
        val normal = normal(CardStyleInteractionStyle.G3, streams, request.seat)
        val window = ExpansionPairedWindow(ExpansionPairedRequest(index))
        var cardUnavailable = false
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
                    check(session.actors() == listOf(actor)) {
                        "An expansion root has one acting seat."
                    }
                    if (observation.candidates.any { it.command == ACQUIRE }) {
                        return evaluate(
                            request,
                            streams,
                            session,
                            window,
                            requireNotNull(commands[actor]) {
                                "The unchanged E command is required."
                            },
                        )
                    }
                    cardUnavailable = true
                }
            }
            session.apply(commands, deadlineNanos)
        }
        return CardStyleInteractionOrigin(
            signature = input.signature,
            request = request,
            streams = streams,
            status = ExpansionOriginStatus.MISSING,
            missingReason =
                when {
                    cardUnavailable -> CardStyleInteractionMissing.CARD_UNAVAILABLE
                    window.selectedNonbranching ->
                        CardStyleInteractionMissing.TARGET_WINDOW_NONBRANCHING
                    else -> CardStyleInteractionMissing.TARGET_WINDOW_NOT_REACHED
                },
            firstExpansionTurn = window.firstTurn,
            observedWindows = window.observedWindows,
            prefix = prefix(session),
            actorInput = null,
            incumbentCommand = null,
            exactIncumbentVerified = false,
            continuations = emptyList(),
        )
    }

    private fun prefix(session: ExperimentSession): ExpansionPairedPrefix {
        val entries = session.entries.map(Entry::toText)
        check(replay(GameState(session.state.header), entries) == session.state) {
            "The complete normal prefix must replay to its exact recorded state."
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
        request: CardStyleInteractionRequest,
        streams: CardStyleInteractionSeeds,
        root: ExperimentSession,
        window: ExpansionPairedWindow,
        incumbent: String,
    ): CardStyleInteractionOrigin {
        val state = root.state
        val actor = state.playerIds[request.seat]
        val observation = root.observation(actor)
        val view = state.viewForPlayer(actor)
        val bonus = ExpansionBonusProjection.from(state)
        val encoded = ExpansionPairedEncoder.from(view, actor, observation, incumbent, bonus)
        val actorInput = ExpansionPairedActorInput(view, observation, bonus, encoded)
        val commands = listOf(incumbent, ACQUIRE).distinct()
        check(commands.all { command -> observation.candidates.any { it.command == command } }) {
            "Both fixed logical interventions must be actually offered before any future draw."
        }
        val prefix = prefix(root)
        val prototype = ExperimentSession(state, streams.future.first(), recordEntries = true)
        check(
            normal(CardStyleInteractionStyle.G3, streams, request.seat, root.decisions)
                .commands(prototype, mapOf(actor to observation))[actor] == incumbent
        ) {
            "Fresh unchanged G3/E must reproduce the root command at its original PLAYER offset."
        }
        val continuations =
            CardStyleInteractionStyle.entries.map { style ->
                val arms = commands.map { command ->
                    val tails =
                        streams.future.mapIndexed { replicate, chance ->
                            val session = prototype.fork(chance)
                            session.apply(mapOf(actor to command), deadlineNanos)
                            val continuation = normal(style, streams, request.seat, root.decisions)
                            val terminal =
                                continuation.finish(
                                    session,
                                    request.seat,
                                    input.configuration.id,
                                    chance,
                                )
                            val entries = session.entries.map(Entry::toText)
                            check(
                                replay(state, entries) == session.state &&
                                    session.state.stage == Stage.FINISHED
                            ) {
                                "Each fixed-continuation terminal suffix must replay exactly."
                            }
                            ExpansionPairedTail(
                                replicate,
                                terminal,
                                entries,
                                CardAdvantageExecutor.hash(entries),
                            )
                        }
                    CardStyleInteractionArm(command, tails)
                }
                CardStyleInteractionResult(style, 0, commands.indexOf(ACQUIRE), arms)
            }
        return CardStyleInteractionOrigin(
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
            continuations = continuations,
        )
    }

    companion object {
        const val ACQUIRE = "buy-expansion-card"
    }
}
