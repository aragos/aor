package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategyArenaResumeTest {
    @Test
    fun matchingInitializedCheckpointRestoresTheMissingOpponentSnapshot() {
        withFixture { fixture ->
            fixture.snapshots.model(BotModels.default, "candidate.json")
            fixture.snapshots.model(BotModels.default, "incumbent.json")

            val failure = assertFailsWith<IllegalArgumentException> { fixture.resume() }

            assertThat(failure).hasMessageThat().contains("exhausted its three-day allowance")
            assertThat(fixture.snapshots.read("opponent-0.json"))
                .isEqualTo(fixture.snapshots.read("candidate.json"))
        }
    }

    @Test
    fun matchingExistingSnapshotBytesArePreservedDuringResume() {
        withFixture { fixture ->
            fixture.snapshots.model(BotModels.default, "candidate.json")
            val candidate = fixture.output.resolve("candidate.json")
            val original = Files.readString(candidate) + "\n\n"
            Files.writeString(candidate, original)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.resume() }

            assertThat(failure).hasMessageThat().contains("exhausted its three-day allowance")
            assertThat(Files.readString(candidate)).isEqualTo(original)
        }
    }

    @Test
    fun matchingCheckpointCannotOverwriteDifferentExistingModelEvidence() {
        withFixture { fixture ->
            val different =
                BotModels.default.copy(weights = BotModels.default.weights + ("cash" to 999.0))
            fixture.snapshots.model(different, "candidate.json")
            val candidate = fixture.output.resolve("candidate.json")
            val original = Files.readString(candidate)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.resume() }

            assertThat(failure)
                .hasMessageThat()
                .contains("Existing model snapshot candidate.json differs")
            assertThat(Files.readString(candidate)).isEqualTo(original)
            assertThat(Files.exists(fixture.output.resolve("opponent-0.json"))).isFalse()
        }
    }

    @Test
    fun emptyExistingModelEvidenceIsRejectedWithoutOverwrite() {
        withFixture { fixture ->
            val candidate = fixture.output.resolve("candidate.json")
            Files.createFile(candidate)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.resume() }

            assertThat(failure)
                .hasMessageThat()
                .contains("Existing model snapshot candidate.json differs")
            assertThat(Files.exists(candidate)).isTrue()
            assertThat(Files.size(candidate)).isEqualTo(0L)
        }
    }

    @Test
    fun wrongCheckpointSignatureCannotCreateMissingModelEvidence() {
        withFixture(signature = "different") { fixture ->
            val failure = assertFailsWith<IllegalArgumentException> { fixture.resume() }

            assertThat(failure).hasMessageThat().contains("Arena code, models or settings changed")
            assertThat(Files.exists(fixture.output.resolve("candidate.json"))).isFalse()
        }
    }

    private data class Fixture(val source: Path, val output: Path) {
        val snapshots = TrainingArtifacts(output)

        fun resume() {
            StrategyArenaMain.run(
                listOf(
                    "--mode",
                    "arena",
                    "--source",
                    source.toString(),
                    "--output",
                    output.toString(),
                    "--workers",
                    "1",
                    "--hours",
                    "0.001",
                )
            )
        }
    }

    private fun withFixture(signature: String? = null, action: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("aor-arena-model-resume-")
        try {
            val inputs = TrainingArtifacts(directory.resolve("inputs"))
            inputs.model(BotModels.default, "base.json")
            val model = inputs.directory.resolve("base.json").toString()
            inputs.write(
                "arena.json",
                mapOf(
                    "schemaVersion" to 1,
                    "purpose" to "SCREENING",
                    "configurations" to listOf(TrainingMatrix.stratified.first().id),
                    "candidate" to model,
                    "incumbent" to model,
                    "opponents" to listOf(model),
                    "pairsPerConfiguration" to 1,
                    "seed" to 71,
                ),
            )
            val source = inputs.directory.resolve("arena.json")
            val fixture = Fixture(source, directory.resolve("output"))
            val expected =
                trainingSignature("arena-1:${ArenaSpecification.read(source).identity()}")
            fixture.snapshots.write(
                "state.json",
                mapOf(
                    "kind" to "strategy-arena",
                    "signature" to (signature ?: expected),
                    "phase" to "initialized",
                ),
            )
            // Stop after archive initialization, before any worker or game can be launched.
            Files.writeString(fixture.output.resolve("started-at.txt"), Instant.EPOCH.toString())

            action(fixture)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
