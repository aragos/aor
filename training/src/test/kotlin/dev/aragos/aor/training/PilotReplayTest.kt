package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotReplayTest {
    private val scenario = CommodityExperiment.scenario(3)

    @Test
    fun recordingDoesNotChangeFixedPlanContinuationResults() {
        withRunner { runner ->
            val ordinary =
                runner.continuation(scenario, -17, CommodityExperimentStrategy.HOLD_EXPAND)

            val recorded = runner.replay(scenario, -17, CommodityExperimentStrategy.HOLD_EXPAND)

            assertThat(recorded.game.rewards).isEqualTo(ordinary.rewards)
            assertThat(recorded.game.wins).isEqualTo(ordinary.wins)
            assertThat(recorded.game.ranks).isEqualTo(ordinary.ranks)
            assertThat(recorded.game.decisions).isEqualTo(ordinary.decisions)
            assertThat(recorded.game.planSelectionsByStrategy)
                .isEqualTo(ordinary.planSelectionsByStrategy)
        }
    }

    @Test
    fun canonicalTraceReconstructsTerminalScoresFromSampledFixture() {
        withRunner { runner ->
            val recorded = runner.replay(scenario, 51, CommodityExperimentStrategy.PLAY_NOW)

            val replayed =
                recorded.entries.fold(CommodityExperiment.sampledState(scenario, 51)) { state, entry
                    ->
                    state.step(TextCodec.parse(entry))
                }

            assertThat(recorded.replayVerified).isTrue()
            assertThat(replayed.stage).isEqualTo(Stage.FINISHED)
            assertThat(replayed.publicView().scores.map(PilotReplayScore::from))
                .isEqualTo(recorded.finalScores)
        }
    }

    @Test
    fun baselineTraceRetainsOnlyTheDecidingHandAndNoExperimentalPlan() {
        withRunner { runner ->
            val recorded = runner.replay(scenario, 51, CommodityExperimentStrategy.KEEP_BASELINE)

            assertThat(recorded.decisions).isNotEmpty()
            assertThat(recorded.decisions.first().hand.map { it.id })
                .containsExactlyElementsIn(
                    scenario.state.playerState(scenario.actor).hand.map { it.definition.id }
                )
            assertThat(recorded.decisions.first().players.map { it.id })
                .containsExactlyElementsIn(scenario.state.playerIds.map { it.value })
            assertThat(recorded.game.plans).isEqualTo(0)
            assertThat(recorded.game.planSelectionsByStrategy).isEmpty()
        }
    }

    @Test
    fun expiredReplayDeadlineStopsBeforeSimulating() {
        withRunner(deadline = System.nanoTime() - 1) { runner ->
            val failure =
                assertFailsWith<CancellationException> {
                    runner.replay(scenario, 51, CommodityExperimentStrategy.KEEP_BASELINE)
                }

            assertThat(failure).hasMessageThat().contains("deadline")
        }
    }

    private fun withRunner(
        deadline: Long = Long.MAX_VALUE,
        action: (PilotRunner) -> Unit,
    ) {
        val directory = Files.createTempDirectory("aor-pilot-replay-")
        try {
            Tournaments(workers = 1, deadlineNanos = deadline).use { workers ->
                val options =
                    PilotOptions(
                        output = directory,
                        model = null,
                        workers = 1,
                        hours = 1.0,
                        seed = 17,
                        benchmarkGames = 1,
                        positions = 2,
                        samples = 2,
                        pairs = 1,
                    )
                action(
                    PilotRunner(
                        options,
                        TrainingArtifacts(directory),
                        BotModels.default,
                        deadline,
                        workers,
                    )
                )
            }
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
