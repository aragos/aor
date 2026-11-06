package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotOptionsTest {
    @Test
    fun defaultsDescribeTheFixedPilotExperiment() {
        val options = PilotOptions.parse(listOf("--mode", "pilot"))

        assertThat(options.output).isEqualTo(Path.of(".aor-training/pilot").toAbsolutePath())
        assertThat(options.model).isNull()
        assertThat(options.hours).isEqualTo(12.0)
        assertThat(options.seed).isEqualTo(19790531)
        assertThat(options.benchmarkGames).isEqualTo(8)
        assertThat(options.positions).isEqualTo(256)
        assertThat(options.samples).isEqualTo(16)
        assertThat(options.pairs).isEqualTo(128)
        assertThat(options.workers).isEqualTo(maximumWorkers())
    }

    @Test
    fun explicitSettingsOverrideExperimentDefaults() {
        val options =
            PilotOptions.parse(
                listOf(
                    "--mode",
                    "pilot",
                    "--output",
                    ".aor-training/options-test",
                    "--model",
                    "baseline.json",
                    "--workers",
                    "1",
                    "--hours",
                    "0.25",
                    "--seed",
                    "0",
                    "--benchmark-games",
                    "2",
                    "--positions",
                    "12",
                    "--samples",
                    "3",
                    "--pairs",
                    "32",
                )
            )

        assertThat(options.output).isEqualTo(Path.of(".aor-training/options-test").toAbsolutePath())
        assertThat(options.model).isEqualTo(Path.of("baseline.json"))
        assertThat(options.workers).isEqualTo(1)
        assertThat(options.hours).isEqualTo(0.25)
        assertThat(options.seed).isEqualTo(0)
        assertThat(options.benchmarkGames).isEqualTo(2)
        assertThat(options.positions).isEqualTo(12)
        assertThat(options.samples).isEqualTo(3)
        assertThat(options.pairs).isEqualTo(32)
    }

    @Test
    fun missingOptionValueIsRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotOptions.parse(listOf("--mode", "pilot", "--pairs"))
            }

        assertThat(failure).hasMessageThat().contains("--option value pairs")
    }

    @Test
    fun repeatedOptionsCannotSilentlyOverwriteEarlierValues() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotOptions.parse(listOf("--mode", "pilot", "--pairs", "32", "--pairs", "64"))
            }

        assertThat(failure).hasMessageThat().contains("may not repeat")
    }

    @Test
    fun legacyMatrixOptionsCannotChangeTheFixedProfile() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotOptions.parse(listOf("--mode", "pilot", "--configurations", "4"))
            }

        assertThat(failure).hasMessageThat().contains("Unknown pilot option")
    }

    @Test
    fun modeMustBeExplicitlyPilot() {
        for (arguments in listOf(emptyList(), listOf("--mode", "train"))) {
            val failure =
                assertFailsWith<IllegalArgumentException> { PilotOptions.parse(arguments) }

            assertThat(failure).hasMessageThat().contains("Pilot mode must be explicit")
        }
    }

    @Test
    fun countOptionsRejectValuesOutsideTheirSupportedRanges() {
        val limits =
            mapOf(
                "--benchmark-games" to 10000,
                "--positions" to 10000,
                "--samples" to 256,
                "--pairs" to 100000,
                "--workers" to maximumWorkers(),
            )
        for ((option, maximum) in limits) {
            for (value in listOf("0", "-1", (maximum + 1).toString())) {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        PilotOptions.parse(listOf("--mode", "pilot", option, value))
                    }

                assertThat(failure).hasMessageThat().contains("$option must be between")
            }
        }
    }

    @Test
    fun malformedCountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PilotOptions.parse(listOf("--mode", "pilot", "--positions", "1.5"))
        }
    }

    @Test
    fun onePositionCannotSupplyBothTrainingAndValidation() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotOptions.parse(listOf("--mode", "pilot", "--positions", "1"))
            }

        assertThat(failure).hasMessageThat().contains("at least two scenarios")
    }

    @Test
    fun twoPositionsAreAcceptedForMinimalSmokeExperiments() {
        val options = PilotOptions.parse(listOf("--mode", "pilot", "--positions", "2"))

        assertThat(options.positions).isEqualTo(2)
    }

    @Test
    fun seedMustStayInTheInitialSeedRange() {
        for (value in listOf("-1", "100000001")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PilotOptions.parse(listOf("--mode", "pilot", "--seed", value))
                }

            assertThat(failure).hasMessageThat().contains("initial seed")
        }
    }

    @Test
    fun timeBudgetMustBeFinitePositiveAndWithinTheSupervisorAllowance() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "0", "-1", "72.01")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PilotOptions.parse(listOf("--mode", "pilot", "--hours", value))
                }

            assertThat(failure).hasMessageThat().contains("hours must be positive and at most 72")
        }
    }

    @Test
    fun changingExperimentCountsOrSeedChangesResumeIdentity() {
        val original = PilotOptions.parse(listOf("--mode", "pilot"))
        val changed =
            listOf(
                original.copy(seed = original.seed + 1),
                original.copy(benchmarkGames = original.benchmarkGames + 1),
                original.copy(positions = original.positions + 1),
                original.copy(samples = original.samples + 1),
                original.copy(pairs = original.pairs + 1),
            )

        for (options in changed) {
            assertThat(options.identity()).isNotEqualTo(original.identity())
        }
    }

    @Test
    fun resourcesAndArtifactLocationsDoNotChangeExperimentIdentity() {
        val original = PilotOptions.parse(listOf("--mode", "pilot"))
        val changed =
            original.copy(
                workers = original.workers + 1,
                hours = 1.0,
                output = Path.of("relocated-run"),
                model = Path.of("relocated-baseline.json"),
            )

        assertThat(changed.identity()).isEqualTo(original.identity())
    }

    private fun maximumWorkers(): Int {
        val processors =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        return ((processors * 0.8).toInt() - 2).coerceAtLeast(1)
    }
}
