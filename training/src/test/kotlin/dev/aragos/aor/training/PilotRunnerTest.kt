package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotRunnerTest {
    @Test
    fun failingValidationPreventsTournamentWork() {
        val validation = validationReport(improvement = 0.0)
        val student = constantStudent()

        withRunner { runner, artifacts ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    runner.evaluate(student, validation)
                }

            assertThat(failure).hasMessageThat().contains("passing held-out validation")
            assertThat(artifacts.read("pair-0.json")).isNull()
            assertThat(artifacts.read("evaluation.json")).isNull()
        }
    }

    @Test
    fun skippedEvaluationReportsCompletedExperimentWithoutTournamentOrPromotion() {
        val validation = validationReport(improvement = 0.0)

        withRunner { runner, artifacts ->
            runner.skipEvaluation(validation)

            val report =
                checkNotNull(artifacts.read("evaluation.json")) {
                    "Skipping the tournament must write its outcome."
                }
            assertThat(report.required("experimentComplete").booleanValue()).isTrue()
            assertThat(report.required("complete").booleanValue()).isFalse()
            assertThat(report.required("skipped").booleanValue()).isTrue()
            assertThat(report.required("promoted").booleanValue()).isFalse()
            assertThat(report.required("pairs").intValue()).isEqualTo(0)
            assertThat(report.required("games").intValue()).isEqualTo(0)
            assertThat(report.required("reason").asText()).isEqualTo(validation.reason)
            assertThat(report.required("validation").required("passed").booleanValue()).isFalse()
            assertThat(artifacts.read("pair-0.json")).isNull()
        }
    }

    @Test
    fun passingValidationCannotBeReportedAsSkipped() {
        val validation = validationReport(improvement = 0.1)

        withRunner { runner, artifacts ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    runner.skipEvaluation(validation)
                }

            assertThat(failure).hasMessageThat().contains("proceed to evaluation")
            assertThat(artifacts.read("evaluation.json")).isNull()
        }
    }

    private fun validationReport(improvement: Double): PilotValidationReport =
        PilotValidation.report(
            List(PilotValidation.MINIMUM_SCENARIOS) { seed ->
                PilotValidationOutcome(
                    scenarioSeed = seed,
                    tag = "opportunity",
                    baselineRewards = List(PilotValidation.MINIMUM_SAMPLES) { 0.4 },
                    studentRewards = List(PilotValidation.MINIMUM_SAMPLES) { 0.4 + improvement },
                    strategy = CommodityExperimentStrategy.HOLD_EXPAND.name,
                )
            }
        )

    private fun constantStudent(): PilotStudent =
        PilotStudent(
            inputWeights = List(24) { listOf(0.0) },
            hiddenBias = List(24) { 0.0 },
            outputWeights = List(CommodityExperimentStrategy.entries.size) { List(24) { 0.0 } },
            outputBias = List(CommodityExperimentStrategy.entries.size) { 0.0 },
        )

    private fun withRunner(action: (PilotRunner, TrainingArtifacts) -> Unit) {
        val directory = Files.createTempDirectory("aor-pilot-gate-")
        try {
            val artifacts = TrainingArtifacts(directory)
            // An expired executor makes accidental tournament work fail immediately in these tests.
            val deadline = System.nanoTime() - 1
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
                    PilotRunner(options, artifacts, BotModels.default, deadline, workers),
                    artifacts,
                )
            }
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
