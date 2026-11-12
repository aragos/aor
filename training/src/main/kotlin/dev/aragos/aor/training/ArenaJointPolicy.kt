package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotModel
import java.nio.file.Path

/**
 * Frozen sampled card/purchase bundles with an independent purchase prior. Every role is explicit;
 * null card components retain that seat's ordinary HARD policy, not a sampled zero residual actor.
 * Shared rivals never follow the evaluated bundle. Token/expansion overlays remain arena-owned.
 */
class ArenaJointPolicy
private constructor(
    private val source: ModelInitializationSource,
    private val baseline: PurchaseBaselineInput,
    private val bundles: Map<Role, JointActorBundle>,
) {
    private enum class Role(val field: String, val prefix: String) {
        SHARED("sharedBundle", "joint-shared"),
        CANDIDATE("candidateBundle", "joint-candidate"),
        INCUMBENT("incumbentBundle", "joint-incumbent"),
    }

    internal fun validate(configurations: List<TrainingConfiguration>) {
        require(configurations.size == 1 && configurations.single().id == CONFIGURATION) {
            "Joint arena actors require the single supported four-player configuration."
        }
        require(bundles.values.all { it.configuration == configurations.single() }) {
            "Every joint arena bundle must use the arena's exact configuration."
        }
    }

    internal fun identity(): String =
        "$VERSION:${source.sha256}:${baseline.sha256}:" +
            Role.entries.joinToString(":") { role ->
                "${role.field}=${bundle(role).sha256}"
            }

    internal fun controllers(
        playerCount: Int,
        controlledSeat: Int,
        side: ArenaSide?,
        ordinary: BotModel,
        opponentIndices: List<Int>?,
        opponents: List<BotModel>,
    ): Map<Int, SimulationController> {
        require(side != null) {
            "Joint arena roles require an explicit candidate or incumbent side."
        }
        require(controlledSeat in 0 until playerCount) {
            "The joint controlled seat must belong to its arena lineup."
        }
        val indices =
            requireNotNull(opponentIndices) {
                "Joint arena rivals require explicit ordinary opponent indices."
            }
        require(indices.size == playerCount && indices.all { it in opponents.indices }) {
            "The joint lineup must bind every seat to its actual ordinary opponent model."
        }
        val evaluated = if (side == ArenaSide.CANDIDATE) Role.CANDIDATE else Role.INCUMBENT
        return (0 until playerCount).associateWith { seat ->
            bundle(if (seat == controlledSeat) evaluated else Role.SHARED)
                .controller(
                    baseline,
                    ordinary = if (seat == controlledSeat) ordinary else opponents[indices[seat]],
                )
        }
    }

    /** Raw bundle and component identities, including the explicit null-card anchor. */
    internal fun binding(): Map<String, Any?> =
        mapOf(
            "version" to VERSION,
            "source" to source.metadata("source.json"),
            "baseline" to
                mapOf(
                    "path" to baseline.path.toString(),
                    "sha256" to baseline.sha256,
                    "snapshot" to "joint-purchase-baseline.json",
                ),
        ) +
            Role.entries.associate { role ->
                val actor = bundle(role)
                val metadata = actor.metadata()
                role.field to
                    (metadata +
                        mapOf(
                            "snapshot" to "${role.prefix}-bundle.json",
                            "card" to
                                actor.card?.let {
                                    phaseSnapshot(
                                        metadata,
                                        "card",
                                        "${role.prefix}-card-policy.json",
                                    )
                                },
                            "purchase" to
                                phaseSnapshot(
                                    metadata,
                                    "purchase",
                                    "${role.prefix}-purchase-policy.json",
                                ),
                        ))
            }

    internal fun metadata(): Map<String, Any> = mapOf("jointPolicy" to binding())

    internal fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        artifacts.ensureFileSnapshot(baseline.path, baseline.sha256, "joint-purchase-baseline.json")
        for (role in Role.entries) {
            val actor = bundle(role)
            artifacts.ensureFileSnapshot(actor.path, actor.sha256, "${role.prefix}-bundle.json")
            actor.card?.let {
                artifacts.ensureFileSnapshot(it.path, it.sha256, "${role.prefix}-card-policy.json")
            }
            artifacts.ensureFileSnapshot(
                actor.purchase.path,
                actor.purchase.sha256,
                "${role.prefix}-purchase-policy.json",
            )
        }
    }

    private fun bundle(role: Role): JointActorBundle =
        requireNotNull(bundles[role]) { "Every joint arena role requires its explicit bundle." }

    private fun phaseSnapshot(
        metadata: Map<String, Any?>,
        phase: String,
        snapshot: String,
    ): Map<String, Any?> {
        val values =
            requireNotNull(metadata[phase] as? Map<*, *>) {
                "A present joint actor phase requires its typed component metadata."
            }
        return values.entries.associate { (key, value) -> key.toString() to value } +
            ("snapshot" to snapshot)
    }

    companion object {
        private const val VERSION = "arena-joint-policy-1"
        private const val CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"

        internal fun read(
            source: Path,
            node: JsonNode,
            configurations: List<TrainingConfiguration>,
            transfer: ExperimentalRulesTransfer?,
        ): ArenaJointPolicy {
            val sourceInput =
                ModelInitializationSource(source.toRealPath(), PurchaseJson.hash(source))
            PurchaseJson.fields(node, setOf("baseline") + Role.entries.map { it.field })
            require(configurations.size == 1 && configurations.single().id == CONFIGURATION) {
                "Joint arena actors require the single supported four-player configuration."
            }
            fun path(field: String): Path =
                source
                    .toAbsolutePath()
                    .parent
                    .resolve(PurchaseJson.text(node.required(field), field))
                    .normalize()
                    .toRealPath()
            val baselinePath = path("baseline")
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    PurchaseJson.hash(baselinePath),
                    TrainingArtifacts.readModel(baselinePath, transfer),
                )
            val bundles =
                Role.entries.associateWith { role ->
                    JointActorBundle.read(path(role.field), configurations.single(), baseline)
                }
            require(PurchaseJson.hash(baseline.path) == baseline.sha256) {
                "The shared joint purchase prior changed while reading."
            }
            require(PurchaseJson.hash(sourceInput.path) == sourceInput.sha256) {
                "The joint arena source changed while reading its roles."
            }
            return ArenaJointPolicy(sourceInput, baseline, bundles)
        }
    }
}
