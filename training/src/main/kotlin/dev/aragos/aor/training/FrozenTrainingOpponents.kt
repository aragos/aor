package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotModel
import java.nio.file.Path

/** The immutable ordered rival models in a training or evaluation population. */
internal class FrozenTrainingOpponents
private constructor(
    private val inputs: List<ModelInitializationSource>,
    val models: List<BotModel>,
) {
    val hashes: List<String> = inputs.map(ModelInitializationSource::sha256)

    fun snapshot(artifacts: TrainingArtifacts) {
        inputs.forEachIndexed { index, input ->
            artifacts.ensureFileSnapshot(input.path, input.sha256, "roster-$index.json")
        }
    }

    companion object {
        fun read(specification: Path): FrozenTrainingOpponents {
            val source = specification.toAbsolutePath().normalize()
            val entries = ObjectMapper().readTree(source.toFile())
            require(entries.isArray && entries.size() in 1..16) {
                "The opponent roster requires a JSON array with one to sixteen models."
            }
            val inputs = entries.map { entry ->
                require(entry.isTextual && entry.asText().isNotBlank()) {
                    "Each opponent must be a nonempty model file path."
                }
                val relative = Path.of(entry.asText())
                val modelFile = source.parent.resolve(relative).normalize()
                require(!relative.isAbsolute && modelFile.startsWith(source.parent)) {
                    "An opponent model must be inside its roster directory."
                }
                ModelInitializationSource(modelFile, PurchaseJson.hash(modelFile))
            }
            val models = inputs.map { input ->
                TrainingArtifacts.readModel(input.path).also {
                    require(PurchaseJson.hash(input.path) == input.sha256) {
                        "An opponent model changed while reading."
                    }
                }
            }
            return FrozenTrainingOpponents(inputs, models)
        }
    }
}
