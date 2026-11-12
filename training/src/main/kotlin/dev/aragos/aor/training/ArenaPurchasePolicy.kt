package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Path

/**
 * Frozen shipping/advance policies sharing one prior independent of ordinary seat-model weights.
 * Optional candidate/incumbent actors replace only the evaluated seat; rivals retain [policy].
 * Explicit ordinary evaluated sides omit the overlay at that seat, including its frozen prior.
 * Every explicitly supplied input is bound by SHA-256 and archived verbatim.
 */
class ArenaPurchasePolicy
private constructor(
    private val baseline: PurchaseBaselineInput,
    private val policy: PurchaseActorInput,
    private val candidatePolicy: PurchaseActorInput?,
    private val incumbentPolicy: PurchaseActorInput?,
    private val ordinaryEvaluatedSides: Set<ArenaSide>,
    private val candidateSelection: PurchaseActorSelection?,
) {
    init {
        require(ArenaSide.CANDIDATE !in ordinaryEvaluatedSides || candidatePolicy == null) {
            "An ordinary candidate cannot also declare a purchase actor override."
        }
        require(ArenaSide.INCUMBENT !in ordinaryEvaluatedSides || incumbentPolicy == null) {
            "An ordinary incumbent cannot also declare a purchase actor override."
        }
        require(ArenaSide.CANDIDATE !in ordinaryEvaluatedSides || candidateSelection == null) {
            "An ordinary candidate cannot also declare purchase actor selection."
        }
    }

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
                } +
            ArenaSide.entries
                .filter { it in ordinaryEvaluatedSides }
                .joinToString("") {
                    ":ordinary-evaluated-side:$it"
                } +
            (candidateSelection?.let { ":candidate-selection:$it" } ?: "")

    /** Null [side] preserves the shared environment and rejects ambiguous override routing. */
    internal fun controllers(
        playerCount: Int,
        controlledSeat: Int,
        side: ArenaSide?,
    ): Map<Int, SimulationController> {
        require(controlledSeat in 0 until playerCount) {
            "The purchase actor's controlled seat must belong to the arena lineup."
        }
        require(
            side != null ||
                candidatePolicy == null &&
                    incumbentPolicy == null &&
                    ordinaryEvaluatedSides.isEmpty() &&
                    candidateSelection == null
        ) {
            "Purchase actor overrides require an explicit candidate or incumbent arena side."
        }
        val controlledPolicy =
            when (side) {
                ArenaSide.CANDIDATE -> candidatePolicy ?: policy
                ArenaSide.INCUMBENT -> incumbentPolicy ?: policy
                null -> policy
            }
        val ordinary = side?.let { it in ordinaryEvaluatedSides } == true
        return (0 until playerCount)
            .filter { it != controlledSeat || !ordinary }
            .associateWith { seat ->
                PurchaseActorController(
                    baseline.model,
                    if (seat == controlledSeat) controlledPolicy.model else policy.model,
                    selection =
                        if (seat == controlledSeat && side == ArenaSide.CANDIDATE) {
                            candidateSelection ?: PurchaseActorSelection.SAMPLE
                        } else {
                            PurchaseActorSelection.SAMPLE
                        },
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
                    } +
                    (if (ordinaryEvaluatedSides.isEmpty()) {
                        emptyMap()
                    } else {
                        mapOf(
                            "ordinaryEvaluatedSides" to
                                ArenaSide.entries
                                    .filter { it in ordinaryEvaluatedSides }
                                    .map { it.name }
                        )
                    }) +
                    (candidateSelection?.let { mapOf("candidateSelection" to it.name) }
                        ?: emptyMap())
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(
            baseline.path,
            baseline.sha256,
            "frozen-purchase-baseline.json",
        )
        for ((role, actor) in actors) {
            artifacts.ensureFileSnapshot(actor.path, actor.sha256, role.snapshot)
        }
    }

    companion object {
        private const val CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"

        internal fun read(
            source: Path,
            node: JsonNode,
            experimentalRulesTransfer: ExperimentalRulesTransfer? = null,
        ): ArenaPurchasePolicy {
            PurchaseJson.fields(
                node,
                setOf("baseline", "policy"),
                setOf(
                    "candidatePolicy",
                    "incumbentPolicy",
                    "ordinaryEvaluatedSides",
                    "candidateSelection",
                ),
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
                    TrainingArtifacts.readModel(baselinePath, experimentalRulesTransfer),
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
            val selection =
                node["candidateSelection"]?.let { value ->
                    val name = PurchaseJson.text(value, "candidateSelection")
                    requireNotNull(
                        PurchaseActorSelection.entries.singleOrNull { it.name == name }
                    ) {
                        "Candidate purchase selection must be SAMPLE or MAXIMUM_PROBABILITY."
                    }
                }
            val ordinary =
                node["ordinaryEvaluatedSides"]
                    ?.let { values ->
                        require(values.isArray) { "Ordinary evaluated sides must be a JSON array." }
                        val sides = values.map { value ->
                            val name = PurchaseJson.text(value, "ordinary evaluated side")
                            requireNotNull(ArenaSide.entries.singleOrNull { it.name == name }) {
                                "An ordinary evaluated side must be CANDIDATE or INCUMBENT."
                            }
                        }
                        require(sides.distinct().size == sides.size) {
                            "Ordinary evaluated sides must be distinct."
                        }
                        sides.toSet()
                    }
                    .orEmpty()
            require(PurchaseJson.hash(baselinePath) == baselineHash) {
                "A frozen arena purchase input changed while being read."
            }
            return ArenaPurchasePolicy(baseline, policy, candidate, incumbent, ordinary, selection)
        }
    }
}
