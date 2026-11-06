package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrainingArtifactsModelTest {
    @Test
    fun sparseExternalFilesUseTheSamePriorOverlayAsTheBrowser() {
        withDirectory { directory ->
            val source = directory.resolve("sparse.json")
            Files.writeString(
                source,
                """{"schemaVersion":1,"weights":{"cash":0,"planning.example":2}}""",
            )

            val model = TrainingArtifacts.readModel(source)

            assertThat(model.weights)
                .containsExactlyEntriesIn(
                    BotModels.default.weights + mapOf("cash" to 0.0, "planning.example" to 2.0)
                )
        }
    }

    @Test
    fun savedSparsePoliciesWriteExplicitZerosAndRoundTripTheirScores() {
        withDirectory { directory ->
            val original = BotModel(weights = mapOf("cash" to 0.0, "planning.example" to 2.0))
            val artifacts = TrainingArtifacts(directory)
            val candidate =
                BotCandidate(
                    "test",
                    mapOf("income" to 10.0, "cash" to 4.0, "planning.example" to 3.0),
                )

            artifacts.model(original)
            val restored = TrainingArtifacts.readModel(directory.resolve("model.json"))

            val written = checkNotNull(artifacts.read("model.json")) { "The model was written." }
            assertThat(written.required("weights").required("income").doubleValue()).isEqualTo(0.0)
            assertThat(written.required("weights").required("cash").doubleValue()).isEqualTo(0.0)
            assertThat(restored.weights["planning.example"]).isEqualTo(2.0)
            assertThat(BotPolicy.score(candidate, restored))
                .isEqualTo(BotPolicy.score(candidate, original))
        }
    }

    @Test
    fun completeModelsRoundTripWithoutAnyWeightChanges() {
        withDirectory { directory ->
            val original =
                BotModels.default.copy(
                    weights = BotModels.default.weights + ("planning.example" to 2.0)
                )

            TrainingArtifacts(directory).model(original)
            val restored = TrainingArtifacts.readModel(directory.resolve("model.json"))

            assertThat(restored).isEqualTo(original)
        }
    }

    @Test
    fun schemaVersionRejectsFractionalTextualAndOverflowingValues() {
        withDirectory { directory ->
            val source = directory.resolve("invalid.json")
            for (value in
                listOf("1.5", "\"1\"", "4294967297", "-4294967295", "18446744073709551616")) {
                Files.writeString(source, """{"schemaVersion":$value,"weights":{}}""")

                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        TrainingArtifacts.readModel(source)
                    }

                assertThat(failure).hasMessageThat().contains("schemaVersion as the JSON integer 1")
            }
        }
    }

    private fun withDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("aor-model-wire-")
        try {
            action(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
