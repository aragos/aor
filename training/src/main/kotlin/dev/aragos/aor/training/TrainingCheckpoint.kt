package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Resume retains the original opponent baseline and never selects on final evaluation seeds. */
data class TrainingCheckpoint(
    val signature: String,
    val initial: BotModel,
    val champion: BotModel,
    val population: List<BotModel>,
    val nextRound: Int = 0,
    val warmupComplete: Boolean = false,
) {
    fun save(artifacts: TrainingArtifacts, ledger: CoverageLedger) {
        artifacts.write(
            "state.json",
            mapOf(
                "formatVersion" to 1,
                "signature" to signature,
                "initial" to initial,
                "champion" to champion,
                "population" to population,
                "nextRound" to nextRound,
                "warmupComplete" to warmupComplete,
                "coverage" to ledger.report(),
            ),
        )
    }

    companion object {
        fun restore(
            root: JsonNode,
            expectedSignature: String,
            ledger: CoverageLedger,
        ): TrainingCheckpoint {
            require(root["formatVersion"]?.intValue() == 1) {
                "Unsupported training checkpoint format."
            }
            require(root["signature"]?.asText() == expectedSignature) {
                "Checkpoint engine, policy or training configuration differs; use a new output directory."
            }
            fun model(node: JsonNode): BotModel =
                BotModel(
                    schemaVersion = node["schemaVersion"].intValue(),
                    weights =
                        node["weights"].properties().associate { (key, value) ->
                            key to value.doubleValue()
                        },
                )
            ledger.restore(root["coverage"])
            return TrainingCheckpoint(
                signature = expectedSignature,
                initial = model(root["initial"]),
                champion = model(root["champion"]),
                population = root["population"].map(::model),
                nextRound = root["nextRound"].intValue(),
                warmupComplete = root["warmupComplete"].booleanValue(),
            )
        }
    }
}

/**
 * Fingerprint the actual packaged policy, so resuming cannot mix incompatible feature definitions.
 */
fun trainingSignature(configuration: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("$RULES_VERSION\n$DATA_VERSION\n$configuration\n".toByteArray())
    val locations =
        listOf(
                BotPolicy::class.java,
                dev.aragos.aor.GameState::class.java,
                TrainingCheckpoint::class.java,
            )
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }
            .distinct()
    for (location in locations) {
        if (Files.isRegularFile(location)) {
            Files.newInputStream(location).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } else {
            Files.walk(location).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    digest.update(location.relativize(path).toString().toByteArray())
                    digest.update(Files.readAllBytes(path))
                }
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
