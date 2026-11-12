package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.CancellationValueInput
import dev.aragos.aor.ai.CancellationValueModel
import dev.aragos.aor.ai.CancellationValuePolicy
import java.nio.file.Files
import java.nio.file.Path

/**
 * Candidate-only cancellation inference. All rival/incumbent and other-phase roles remain intact.
 */
class ArenaCancellationValue
private constructor(
    private val path: Path,
    private val sha256: String,
    private val model: CancellationValueModel,
) {
    internal fun validate(configurations: List<TrainingConfiguration>) {
        require(configurations.all { it.playerCount == 4 }) {
            "Cancellation value inference requires four-seat configurations."
        }
    }

    internal fun identity(): String = "${CancellationValuePolicy.VERSION}:candidate:$sha256"

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "cancellationValue" to
                mapOf(
                    "candidateModel" to
                        mapOf(
                            "path" to path.toString(),
                            "sha256" to sha256,
                            "snapshot" to SNAPSHOT,
                        ),
                    "version" to CancellationValuePolicy.VERSION,
                )
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(path, sha256, SNAPSHOT)
    }

    internal fun controller(side: ArenaSide?): SimulationController? {
        require(side != null) { "Cancellation value inference requires an explicit arena side." }
        return if (side == ArenaSide.CANDIDATE) CancellationValueController(model) else null
    }

    companion object {
        private const val SNAPSHOT = "cancellation-value-candidate.json"

        internal fun read(source: Path, node: JsonNode): ArenaCancellationValue {
            PurchaseJson.fields(node, setOf("candidateModel"))
            val path =
                source
                    .toAbsolutePath()
                    .parent
                    .resolve(PurchaseJson.text(node.required("candidateModel"), "candidateModel"))
                    .toRealPath()
            require(Files.size(path) in 1..100_000) { "Cancellation model exceeds its size bound." }
            val hash = PurchaseJson.hash(path)
            val model = readModel(PurchaseJson.read(path))
            require(PurchaseJson.hash(path) == hash) { "Cancellation model changed while reading." }
            return ArenaCancellationValue(path, hash, model)
        }

        internal fun readModel(node: JsonNode): CancellationValueModel {
            PurchaseJson.fields(
                node,
                setOf(
                    "version",
                    "featureNames",
                    "coefficients",
                    "mean",
                    "standardDeviation",
                    "intercept",
                    "sourceSha256",
                ),
            )
            return CancellationValueModel(
                PurchaseJson.text(node.required("version"), "version"),
                PurchaseJson.strings(node.required("featureNames"), "featureNames"),
                PurchaseJson.numbers(node.required("coefficients"), "coefficients"),
                PurchaseJson.numbers(node.required("mean"), "mean"),
                PurchaseJson.numbers(node.required("standardDeviation"), "standardDeviation"),
                PurchaseJson.number(node.required("intercept"), "intercept"),
                PurchaseJson.text(node.required("sourceSha256"), "sourceSha256"),
            )
        }
    }
}

/** No chance/policy seed or private authority state enters the fixed public value function. */
internal class CancellationValueController(private val model: CancellationValueModel) :
    SimulationController {
    override fun controls(decision: String): Boolean = decision == "cancel-adjustment"

    override fun choose(decision: SimulationDecision): String? {
        if (!controls(decision.observation.decision)) return null
        val input =
            CancellationValueInput.from(decision.view, PlayerId(decision.observation.playerId))
        val predictions = CancellationValuePolicy.predictions(input, model)
        require(
            decision.observation.candidates.map { it.command }.toSet() == predictions.keys + "pass"
        ) {
            "Cancellation value inference requires every original offered command."
        }
        return CancellationValuePolicy.choose(predictions)
    }
}
