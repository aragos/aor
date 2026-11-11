package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Path

/** Native-only expansion models; every other decision retains the seat's actual ordinary policy. */
class ArenaExpansionImitation
private constructor(
    private val roles: Map<ArenaSide, Input>,
    private val opponents: List<Input?>?,
) {
    private data class Input(val path: Path, val sha256: String, val model: BotModel)

    /** Collection has one controlled continuation, explicitly bound to the candidate side. */
    internal fun validateCollectionContinuation() {
        require(roles.keys == setOf(ArenaSide.CANDIDATE)) {
            "Token collection requires one candidate expansion continuation and no unused incumbent role."
        }
    }

    internal fun validate(
        candidate: BotModel,
        incumbent: BotModel,
        ordinaryOpponents: List<BotModel>,
    ) {
        require(opponents == null || opponents.size == ordinaryOpponents.size) {
            "Expansion imitation requires exactly one optional model per ordinary opponent index."
        }
        val bindings =
            roles.map { (side, input) ->
                input to if (side == ArenaSide.CANDIDATE) candidate else incumbent
            } +
                opponents.orEmpty().mapIndexedNotNull { index, input ->
                    input?.let { it to ordinaryOpponents[index] }
                }
        require(
            bindings.all { (input, ordinary) ->
                ExpansionImitationProtocol.preservesOtherWeights(ordinary, input.model)
            }
        ) {
            "Expansion imitation models may change only the eight declared weights from their actual ordinary model."
        }
    }

    internal fun identity(): String =
        "expansion-imitation-arena-1:" +
            roles.entries.joinToString(":") { (side, input) -> "$side=${input.sha256}" } +
            (opponents?.joinToString(prefix = ":opponent-models:") { it?.sha256 ?: "ordinary" }
                ?: "")

    internal fun controllers(
        playerCount: Int,
        controlledSeat: Int,
        side: ArenaSide?,
        opponentIndices: List<Int>?,
    ): Map<Int, SimulationController> {
        require(side != null) {
            "Expansion imitation requires an explicit candidate or incumbent side."
        }
        val rivalInputs =
            if (opponents == null) emptyMap()
            else {
                val indices =
                    requireNotNull(opponentIndices) {
                        "Expansion imitation rivals require explicit opponent indices."
                    }
                require(indices.size == playerCount && indices.all { it in opponents.indices }) {
                    "Expansion imitation lineup must bind every seat to an ordinary opponent index."
                }
                indices
                    .mapIndexedNotNull { seat, index ->
                        if (seat == controlledSeat) null else opponents[index]?.let { seat to it }
                    }
                    .toMap()
            }
        return (rivalInputs + listOfNotNull(roles[side]?.let { controlledSeat to it }).toMap())
            .mapValues { (_, input) ->
                object : SimulationController {
                    override fun controls(decision: String): Boolean = decision == "expand"

                    override fun choose(decision: SimulationDecision): String? =
                        if (controls(decision.observation.decision))
                            BotPolicy.choose(
                                decision.observation,
                                BotDifficulty.HARD,
                                decision.seed,
                                input.model,
                            )
                        else null
                }
            }
    }

    private fun inputs(): List<Pair<String, Input>> =
        roles.map { (side, input) ->
            "expansion-imitation-${side.name.lowercase()}.json" to input
        } +
            opponents.orEmpty().mapIndexedNotNull { index, input ->
                input?.let { "expansion-imitation-opponent-$index.json" to it }
            }

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "expansionImitation" to
                buildMap<String, Any> {
                    put("version", ExpansionImitationProtocol.VERSION)
                    put("decision", "expand")
                    put("featureNames", ExpansionImitationProtocol.features)
                    fun document(input: Input, snapshot: String): Map<String, String> =
                        mapOf(
                            "path" to input.path.toString(),
                            "sha256" to input.sha256,
                            "snapshot" to snapshot,
                        )
                    roles.forEach { (side, input) ->
                        put(
                            "${side.name.lowercase()}Model",
                            document(input, "expansion-imitation-${side.name.lowercase()}.json"),
                        )
                    }
                    opponents?.let { models ->
                        put(
                            "opponentModels",
                            models.mapIndexed { index, input ->
                                input?.let {
                                    document(it, "expansion-imitation-opponent-$index.json")
                                }
                            },
                        )
                    }
                }
        )

    internal fun snapshot(artifacts: TrainingArtifacts) {
        inputs().forEach { (name, input) ->
            artifacts.ensureFileSnapshot(input.path, input.sha256, name)
        }
    }

    companion object {
        internal fun read(source: Path, node: JsonNode): ArenaExpansionImitation {
            PurchaseJson.fields(
                node,
                emptySet(),
                setOf("candidateModel", "incumbentModel", "opponentModels"),
            )
            fun input(value: JsonNode): Input {
                val path =
                    source
                        .toAbsolutePath()
                        .parent
                        .resolve(PurchaseJson.text(value, "expansion model"))
                        .normalize()
                        .toRealPath()
                val hash = PurchaseJson.hash(path)
                val model = TrainingArtifacts.readModel(path)
                require(PurchaseJson.hash(path) == hash) {
                    "Expansion imitation model changed while reading."
                }
                return Input(path, hash, model)
            }
            val roles =
                ArenaSide.entries
                    .mapNotNull { side ->
                        node["${side.name.lowercase()}Model"]?.let { side to input(it) }
                    }
                    .toMap()
            val opponents =
                node["opponentModels"]?.let { values ->
                    PurchaseJson.array(values, "expansion opponent models").map {
                        if (it.isNull) null else input(it)
                    }
                }
            require(roles.isNotEmpty() || opponents?.any { it != null } == true) {
                "Expansion imitation requires at least one explicit model role."
            }
            return ArenaExpansionImitation(roles, opponents)
        }
    }
}
