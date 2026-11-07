package dev.aragos.aor.training

import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/** Executor-only authority snapshot; never serialize this value as actor input or a dataset row. */
internal data class CardAdvantageCapturedRoot(
    val state: GameState,
    val actor: PlayerId,
    val number: Int,
    val observation: BotObservation,
    val chosenCommand: String,
)

internal data class CardAdvantageCapture(
    val backboneDecisions: Int,
    val backboneCanonicalSha256: String,
    val root: CardAdvantageCapturedRoot?,
)

/**
 * Shared prospective root capture, full-backbone replay and exact safe-observation regeneration.
 */
internal class CardAdvantageRootCapture(
    private val input: CardAdvantageCollectionInput,
    private val deadlineNanos: Long,
) {
    private data class Root(
        val number: Int,
        val entries: Int,
        val observation: BotObservation,
        val chosen: String,
    )

    private class Trace(
        private val request: CardAdvantageRequest,
        private val allowTrivial: Boolean,
    ) : SimulationTrace {
        override val seat: Int = request.seat
        var root: Root? = null
        var ordinal = 0
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
                view.stage != Stage.PLAY_CARDS ||
                    observation.decision != "play-cards" ||
                    view.currentEpoch != request.epoch
            )
                return
            ordinal++
            if (
                root != null ||
                    ordinal < request.ordinal ||
                    !CardAdvantageCollector.eligible(view, observation, request, allowTrivial)
            )
                return
            check(snapshotEntryCount == commandEntryIndex) {
                "An ordinary card root requires a single-player batch."
            }
            root = Root(number, snapshotEntryCount, observation, chosen.command)
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    fun capture(index: Int, executor: CardAdvantageExecutor): CardAdvantageCapture {
        val request = input.origins[index]
        val trace = Trace(request, allowTrivial = input.collection != null)
        val backbone =
            Simulation()
                .play(
                    configuration = input.environment.configuration,
                    seed = request.seed,
                    competitors =
                        List(4) {
                            Competitor(input.environment.baseline.model, BotDifficulty.HARD)
                        },
                    deadlineNanos = deadlineNanos,
                    verifyReplay = true,
                    trace = trace,
                    controllers = executor.controllers,
                )
        val identity =
            CardAdvantageCapture(
                backbone.steps,
                CardAdvantageExecutor.hash(trace.entries),
                null,
            )
        val captured = trace.root ?: return identity
        val rootState =
            trace.entries.take(captured.entries).fold(GameState(trace.header)) { state, text ->
                executor.check(captured.number)
                state.step(TextCodec.parse(text))
            }
        val actor = rootState.playerIds[request.seat]
        check(BotObservations.from(rootState, actor) == captured.observation) {
            "The recorded root observation must regenerate exactly."
        }
        return identity.copy(
            root =
                CardAdvantageCapturedRoot(
                    rootState,
                    actor,
                    captured.number,
                    captured.observation,
                    captured.chosen,
                )
        )
    }
}
