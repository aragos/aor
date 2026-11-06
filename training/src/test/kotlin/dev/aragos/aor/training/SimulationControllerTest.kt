package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SimulationControllerTest {
    @Test
    fun explicitBaselineControllerPreservesCanonicalGameSeedsAndCoverage() {
        assertThat(controlled.game.copy(elapsedMillis = 0))
            .isEqualTo(legacy.game.copy(elapsedMillis = 0))
        assertThat(controlled.trace.entries).isEqualTo(legacy.trace.entries)
        assertThat(controlled.trace.decisions).isEqualTo(legacy.trace.decisions)
    }

    @Test
    fun purchaseInputsRegenerateFromCanonicalPrefixAfterEachCommand() {
        val purchases =
            controlled.inputs.filter {
                it.observation.decision in setOf("purchase-shipping", "purchase-advances")
            }
        val counts = controlled.trace.decisions.associateBy { it.number }
        val wanted = purchases.associateBy {
            requireNotNull(counts[it.number]) {
                    "Every controlled input requires its canonical trace position."
                }
                .snapshotEntryCount
        }
        var replay = GameState(controlled.trace.header)
        val reproduced = mutableListOf<Int>()
        controlled.trace.entries.forEachIndexed { count, entry ->
            val input = wanted[count]
            if (input != null) {
                val actor = replay.playerIds[input.seat]
                val view = replay.viewForPlayer(actor)
                val observation = BotObservations.from(replay, actor)
                val baseline = BotPolicy.choose(observation, BotDifficulty.HARD, input.seed)
                assertThat(input.view).isEqualTo(view)
                assertThat(input.observation).isEqualTo(observation)
                val encoded = PurchaseObservationEncoder.from(view, actor, observation, baseline)
                assertThat(encoded.actions.map { it.command })
                    .containsExactlyElementsIn(observation.candidates.map { it.command })
                assertThat(encoded.actions[encoded.baselineIndex].command).isEqualTo(baseline)
                reproduced.add(input.number)
            }
            replay = replay.step(TextCodec.parse(entry))
        }
        assertThat(purchases.map { it.observation.decision })
            .containsAtLeast("purchase-shipping", "purchase-advances")
        assertThat(reproduced).containsExactlyElementsIn(purchases.map { it.number }).inOrder()
        assertThat(
                purchases.zipWithNext().any { (first, second) ->
                    first.observation.decision == "purchase-advances" &&
                        second.observation.decision == "purchase-advances" &&
                        first.view.turnNumber == second.view.turnNumber &&
                        first.observation.candidates.map { it.command } !=
                            second.observation.candidates.map { it.command }
                }
            )
            .isTrue()
    }

    @Test
    fun allSealedControllersSeeCommonUncommittedViewsBeforeAnyBidIsApplied() {
        val inputs = mutableListOf<SimulationDecision>()
        val controller = SimulationController { decision ->
            if (decision.observation.decision != "bid-capital")
                throw CancellationException("Setup captured.")
            inputs.add(decision)
            "bid-capital ${decision.seat}"
        }

        assertFailsWith<CancellationException> {
            Simulation()
                .play(
                    configuration,
                    seed,
                    competitors,
                    controllers = competitors.indices.associateWith { controller },
                )
        }

        assertThat(inputs.map { it.seat }).containsExactly(0, 1, 2).inOrder()
        assertThat(inputs.all { it.view.revealedBids.isEmpty() }).isTrue()
        assertThat(inputs.map { it.view.players }.distinct()).hasSize(1)
        assertThat(inputs.all { it.view.revealedTokenPurchases.isEmpty() }).isTrue()
    }

    @Test
    fun rejectedControllerCommandNeverReachesTheAuthority() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                Simulation()
                    .play(
                        configuration,
                        seed,
                        competitors,
                        controllers = mapOf(0 to SimulationController { "bid-capital 99999" }),
                    )
            }

        assertThat(failure).hasMessageThat().contains("ordinary offered commands")
    }

    @Test
    fun phaseFilterAvoidsConstructingUnrequestedControllerInputs() {
        val controller =
            object : SimulationController {
                override fun controls(decision: String): Boolean = false

                override fun choose(decision: SimulationDecision): String =
                    error("Filtered callback must not run.")
            }
        val actual =
            Simulation()
                .play(configuration, seed, competitors, controllers = mapOf(0 to controller))

        assertThat(actual.copy(elapsedMillis = 0)).isEqualTo(legacy.game.copy(elapsedMillis = 0))
    }

    private data class Snapshot(val number: Int, val snapshotEntryCount: Int, val command: String)

    private class Trace : SimulationTrace {
        override val seat = 0
        val decisions = mutableListOf<Snapshot>()
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
            decisions.add(Snapshot(number, snapshotEntryCount, chosen.command))
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    private data class Recorded(
        val game: SimulatedGame,
        val trace: Trace,
        val inputs: List<SimulationDecision>,
    )

    companion object {
        private val configuration = TrainingMatrix.stratified.first()
        private const val seed = 19790531
        private val competitors = List(3) { Competitor(BotModels.default, BotDifficulty.HARD) }
        private val legacy by lazy {
            val trace = Trace()
            Recorded(
                Simulation().play(configuration, seed, competitors, trace = trace),
                trace,
                emptyList(),
            )
        }
        private val controlled by lazy {
            val inputs = mutableListOf<SimulationDecision>()
            val trace = Trace()
            val controller = SimulationController { decision ->
                inputs.add(decision)
                BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed)
            }
            Recorded(
                Simulation()
                    .play(
                        configuration,
                        seed,
                        competitors,
                        trace = trace,
                        controllers = mapOf(0 to controller),
                    ),
                trace,
                inputs.toList(),
            )
        }
    }
}
