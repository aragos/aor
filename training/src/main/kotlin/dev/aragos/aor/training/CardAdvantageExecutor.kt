package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Exact Simulation seed/batch adapter. Controllers receive no authority state or future outcomes.
 */
internal class CardAdvantageExecutor(
    val input: CardAdvantageCollectionInput,
    val request: CardAdvantageRequest,
    private val deadlineNanos: Long,
) {
    val controllers: Map<Int, SimulationController> =
        (0..3).associateWith { seat ->
            CardAdvantageController(
                input.environment,
                input.continuation?.model?.takeIf {
                    seat == request.seat || request.opponentUsesCandidate[seat]
                },
            )
        }

    fun check(decisions: Int) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
            throw CancellationException("The advantage executor deadline elapsed or was canceled.")
        }
        check(decisions < 12000) { "An advantage trajectory exceeded the ordinary decision guard." }
    }

    fun decision(
        state: GameState,
        actor: PlayerId,
        number: Int,
        batchStart: Int = number,
    ): SimulationDecision {
        val seat = state.playerIds.indexOf(actor)
        return SimulationDecision(
            number,
            seat,
            policySeed(request.seed, batchStart, seat),
            state.viewForPlayer(actor),
            BotObservations.from(state, actor),
        )
    }

    fun commands(
        state: GameState,
        batchStart: Int,
        seeds: CardPolicySeedProvider? = null,
    ): Map<PlayerId, String> {
        check(batchStart)
        val prompt = state.prompts().single()
        val actors =
            when (prompt) {
                is Prompt.SinglePlayer -> listOf(prompt.player)
                is Prompt.MultiPlayer -> prompt.players
                is Prompt.System -> error("Resolve authority outcomes before choosing a batch.")
            }
        return actors
            .mapIndexed { offset, actor ->
                val seat = state.playerIds.indexOf(actor)
                val observation = BotObservations.from(state, actor)
                val seed =
                    seeds?.seed(
                        batchStart + offset,
                        batchStart,
                        requireNotNull(state.turnNumber) {
                            "Tail sampling requires an actual turn."
                        },
                        state.stage,
                        observation.decision,
                        seat,
                    ) ?: policySeed(request.seed, batchStart, seat)
                val controller =
                    requireNotNull(controllers[seat]) {
                        "Every seat requires its frozen controller."
                    }
                val command =
                    if (controller.controls(observation.decision)) {
                        requireNotNull(
                            controller.choose(
                                SimulationDecision(
                                    batchStart + offset,
                                    seat,
                                    seed,
                                    state.viewForPlayer(actor),
                                    observation,
                                )
                            )
                        ) {
                            "A declared advantage controller must return its chosen command."
                        }
                    } else {
                        BotPolicy.choose(
                            observation,
                            BotDifficulty.HARD,
                            seed,
                            input.environment.baseline.model,
                        )
                    }
                require(observation.candidates.any { it.command == command }) {
                    "A continuation must choose an ordinary offered command."
                }
                actor to command
            }
            .toMap()
    }

    /**
     * Complete and replay a paired tail. The default keeps absolute policy seeds and four-stream
     * provenance unchanged; a separately versioned diagnostic may supply its policy seed provider
     * and explicit chance start. Neither extension changes authority transitions or sealed batches.
     */
    fun finish(
        session: ExperimentSession,
        rootState: GameState,
        rootNumber: Int,
        replicate: Int,
        seeds: CardPolicySeedProvider? = null,
        chanceSeed: Long = CardAdvantageExecutor.chanceSeed(request.seed, replicate),
    ): CardAdvantageOutcome {
        while (session.state.stage != Stage.FINISHED) {
            val number = rootNumber + session.decisions
            check(number)
            session.apply(commands(session.state, number, seeds), deadlineNanos)
        }
        val canonical = session.entries.map(Entry::toText)
        val replay =
            canonical.fold(rootState) { state, entry ->
                check(rootNumber + session.decisions)
                state.step(TextCodec.parse(entry))
            }
        check(replay == session.state) {
            "Every complete advantage tail must replay through ordinary authoritative entries."
        }
        val game =
            SimulatedGame(
                input.environment.configuration.id,
                request.seed,
                rootNumber + session.decisions,
                session.state.publicView().scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0L,
            )
        return CardAdvantageOutcome(
            game.reward(request.seat),
            game.winShare(request.seat),
            game.rankReward(request.seat),
            game.scores.map(PilotReplayScore::from),
            game.steps,
            chanceSeed,
            CardAdvantageExecutor.hash(canonical),
        )
    }

    companion object {
        fun policySeed(gameSeed: Int, batchStart: Int, seat: Int): Int =
            gameSeed xor (batchStart * 65537 + seat * 8191)

        fun chanceSeed(gameSeed: Int, replicate: Int): Long {
            require(replicate in 0..3) { "Advantage collection has exactly four future streams." }
            return (gameSeed.toLong() shl 4) xor 0x37a51b20dL xor replicate.toLong()
        }

        fun hash(value: Any): String =
            MessageDigest.getInstance("SHA-256")
                .digest(PurchaseJson.mapper.writeValueAsBytes(value))
                .joinToString("") { "%02x".format(it) }
    }
}
