package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Frozen shipping/advance policies sharing one prior independent of ordinary seat-model weights.
 * Optional candidate/incumbent actors replace only the evaluated seat; rivals retain [policy].
 * Every explicitly supplied input is bound by SHA-256 and archived verbatim.
 */
class ArenaPurchasePolicy
private constructor(
    private val baseline: PurchaseBaselineInput,
    private val policy: PurchaseActorInput,
    private val candidatePolicy: PurchaseActorInput?,
    private val incumbentPolicy: PurchaseActorInput?,
) {
    private enum class ActorRole(val field: String, val snapshot: String) {
        SHARED("policy", "frozen-purchase-policy.json"),
        CANDIDATE("candidatePolicy", "frozen-purchase-candidate-policy.json"),
        INCUMBENT("incumbentPolicy", "frozen-purchase-incumbent-policy.json"),
    }

    private val actors =
        listOfNotNull(
            ActorRole.SHARED to policy,
            candidatePolicy?.let { ActorRole.CANDIDATE to it },
            incumbentPolicy?.let { ActorRole.INCUMBENT to it },
        )

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
            actors.all { (_, actor) ->
                actor.model.stateFeatureNames == dictionary.stateFeatureNames &&
                    actor.model.actionFeatureNames == dictionary.actionFeatureNames
            }
        ) {
            "The frozen arena purchase actor must match the complete encoder dictionaries."
        }
    }

    internal fun identity(): String =
        "frozen-purchase-1:${baseline.sha256}:${policy.sha256}" +
            actors
                .filter { (role, _) -> role != ActorRole.SHARED }
                .joinToString("") { (role, actor) ->
                    ":${role.field}:${actor.sha256}"
                }

    /** Null [side] preserves the shared environment and rejects ambiguous override routing. */
    internal fun controllers(
        playerCount: Int,
        controlledSeat: Int,
        side: ArenaSide?,
    ): Map<Int, SimulationController> {
        require(controlledSeat in 0 until playerCount) {
            "The purchase actor's controlled seat must belong to the arena lineup."
        }
        require(side != null || candidatePolicy == null && incumbentPolicy == null) {
            "Purchase actor overrides require an explicit candidate or incumbent arena side."
        }
        val controlledPolicy =
            when (side) {
                ArenaSide.CANDIDATE -> candidatePolicy ?: policy
                ArenaSide.INCUMBENT -> incumbentPolicy ?: policy
                null -> policy
            }
        return (0 until playerCount).associateWith { seat ->
            PurchaseActorController(
                baseline.model,
                if (seat == controlledSeat) controlledPolicy.model else policy.model,
            )
        }
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
                        )
                ) +
                    actors.associate { (role, actor) ->
                        role.field to
                            mapOf(
                                "path" to actor.path.toString(),
                                "sha256" to actor.sha256,
                                "snapshot" to role.snapshot,
                            )
                    }
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        snapshot(
            artifacts.directory,
            "frozen-purchase-baseline.json",
            baseline.path,
            baseline.sha256,
        )
        for ((role, actor) in actors) {
            snapshot(artifacts.directory, role.snapshot, actor.path, actor.sha256)
        }
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
            PurchaseJson.fields(
                node,
                setOf("baseline", "policy"),
                setOf("candidatePolicy", "incumbentPolicy"),
            )
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
            fun actor(name: String): PurchaseActorInput {
                val actorPath = path(name)
                val actorHash = PurchaseJson.hash(actorPath)
                val model = PurchasePolicyInput.read(actorPath)
                require(PurchaseJson.hash(actorPath) == actorHash) {
                    "The frozen arena purchase $name changed while being read."
                }
                return PurchaseActorInput(actorPath, actorHash, model)
            }
            val policy = actor("policy")
            val candidate = node["candidatePolicy"]?.let { actor("candidatePolicy") }
            val incumbent = node["incumbentPolicy"]?.let { actor("incumbentPolicy") }
            require(PurchaseJson.hash(baselinePath) == baselineHash) {
                "A frozen arena purchase input changed while being read."
            }
            return ArenaPurchasePolicy(baseline, policy, candidate, incumbent)
        }
    }
}
