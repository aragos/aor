package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Frozen shipping/advance policy for every arena seat. Its prior remains independent of candidate,
 * incumbent and opponent weights; both source files are bound by SHA-256 and archived verbatim.
 */
class ArenaPurchasePolicy
private constructor(
    private val baseline: PurchaseBaselineInput,
    private val policy: PurchaseActorInput,
) {
    internal fun validate(configurations: List<TrainingConfiguration>) {
        require(configurations.all { it.id == CONFIGURATION }) {
            "A frozen arena purchase policy requires $CONFIGURATION."
        }
        val configuration = configurations.first()
        val dictionary =
            PurchaseObservationEncoder.dictionary(
                PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            )
        require(
            policy.model.stateFeatureNames == dictionary.stateFeatureNames &&
                policy.model.actionFeatureNames == dictionary.actionFeatureNames
        ) {
            "The frozen arena purchase actor must match the complete encoder dictionaries."
        }
    }

    internal fun identity(): String = "frozen-purchase-1:${baseline.sha256}:${policy.sha256}"

    internal fun controllers(playerCount: Int): Map<Int, SimulationController> =
        (0 until playerCount).associateWith {
            PurchaseActorController(baseline.model, policy.model)
        }

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "frozenPurchase" to
                mapOf(
                    "baseline" to
                        mapOf(
                            "path" to baseline.path.toString(),
                            "sha256" to baseline.sha256,
                            "snapshot" to "frozen-purchase-baseline.json",
                        ),
                    "policy" to
                        mapOf(
                            "path" to policy.path.toString(),
                            "sha256" to policy.sha256,
                            "snapshot" to "frozen-purchase-policy.json",
                        ),
                )
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        snapshot(
            artifacts.directory,
            "frozen-purchase-baseline.json",
            baseline.path,
            baseline.sha256,
        )
        snapshot(artifacts.directory, "frozen-purchase-policy.json", policy.path, policy.sha256)
    }

    private fun snapshot(directory: Path, name: String, source: Path, sha256: String) {
        val destination = directory.resolve(name)
        if (Files.exists(destination)) {
            require(PurchaseJson.hash(destination) == sha256) {
                "Existing arena snapshot $name differs and will not be overwritten."
            }
            return
        }
        val temporary = Files.createTempFile(directory, ".frozen-purchase-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            require(PurchaseJson.hash(temporary) == sha256) {
                "A frozen arena purchase input changed before archiving."
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"

        internal fun read(source: Path, node: JsonNode): ArenaPurchasePolicy {
            PurchaseJson.fields(node, setOf("baseline", "policy"))
            fun path(name: String): Path =
                source
                    .toAbsolutePath()
                    .parent
                    .resolve(PurchaseJson.text(node.required(name), name))
                    .normalize()
                    .toRealPath()
            val baselinePath = path("baseline")
            val baselineHash = PurchaseJson.hash(baselinePath)
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    baselineHash,
                    TrainingArtifacts.readModel(baselinePath),
                )
            val policyPath = path("policy")
            val policyHash = PurchaseJson.hash(policyPath)
            val policy =
                PurchaseActorInput(policyPath, policyHash, PurchasePolicyInput.read(policyPath))
            require(
                PurchaseJson.hash(baselinePath) == baselineHash &&
                    PurchaseJson.hash(policyPath) == policyHash
            ) {
                "A frozen arena purchase input changed while being read."
            }
            return ArenaPurchasePolicy(baseline, policy)
        }
    }
}
