package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * Frozen expansion and token roles around a sampled purchase learner. Candidate means the rotating
 * learning seat; rival arrays use ordinary opponent indices. Purchase actions remain exclusively
 * owned by the collection's learner, rival actor and shared prior, never by an arena purchase
 * actor. [replayIndices] are complete games selected before collection, not additional training
 * worlds.
 */
internal class PurchaseEcology
private constructor(
    private val expansion: ArenaExpansionImitation,
    private val tokens: ArenaTokenOrderPolicy,
    val replayIndices: List<Int>,
) {
    val inputPaths: List<Path>
        get() = expansion.inputPaths()

    fun identity(): String = "$PROTOCOL:${expansion.identity()}:${tokens.identity()}:$replayIndices"

    fun metadata(): Map<String, Any> =
        mapOf("continuationProtocol" to PROTOCOL, "replayIndices" to replayIndices) +
            expansion.metadata() +
            tokens.metadata()

    fun snapshot(artifacts: TrainingArtifacts) {
        expansion.snapshot(artifacts)
        tokens.snapshot(artifacts)
    }

    /** Each nonpurchase phase selects exactly one controller, preserving the sampled learner. */
    fun controllers(
        input: PurchaseCollectionInput,
        index: Int,
        purchase: Map<Int, SimulationController>,
    ): Map<Int, SimulationController> {
        val playerCount = input.configuration.playerCount
        val seat = input.seat(index)
        val indices = input.opponentIndices(index)
        val expansions = expansion.controllers(playerCount, seat, ArenaSide.CANDIDATE, indices)
        val tokenControllers =
            tokens.opponentControllers(
                playerCount,
                seat,
                indices,
                input.opponents.map { it.model },
            ) +
                listOfNotNull(
                        tokens.controller(ArenaSide.CANDIDATE, input.baseline.model)?.let {
                            seat to it
                        }
                    )
                    .toMap()
        return (0 until playerCount).associateWith { player ->
            val phases =
                listOfNotNull(purchase[player], expansions[player], tokenControllers[player])
            object : SimulationController {
                override fun controls(decision: String): Boolean = phases.any {
                    it.controls(decision)
                }

                override fun choose(decision: SimulationDecision): String? =
                    phases
                        .singleOrNull { it.controls(decision.observation.decision) }
                        ?.choose(decision)
            }
        }
    }

    companion object {
        const val PROTOCOL = "ARENA_OVERLAYS_1"

        fun read(
            source: Path,
            node: JsonNode,
            configuration: TrainingConfiguration,
            baseline: PurchaseBaselineInput,
            opponents: List<PurchaseBaselineInput>,
            games: Int,
        ): PurchaseEcology? {
            if (!node.has("continuationProtocol")) {
                require(
                    listOf("expansionImitation", "tokenOrder", "replayIndices").none(node::has)
                ) {
                    "Purchase ecology roles and replays require the explicit continuation protocol."
                }
                return null
            }
            require(
                PurchaseJson.text(node.required("continuationProtocol"), "continuationProtocol") ==
                    PROTOCOL
            ) {
                "Purchase ecology requires ARENA_OVERLAYS_1."
            }
            val expansionNode = node.required("expansionImitation")
            PurchaseJson.fields(expansionNode, setOf("candidateModel", "opponentModels"))
            val tokenNode = node.required("tokenOrder")
            PurchaseJson.fields(tokenNode, setOf("candidateSelector", "opponentSelectors"))
            require(
                PurchaseJson.text(tokenNode.required("candidateSelector"), "candidateSelector") ==
                    "FIXED_16_TO_15"
            ) {
                "Purchase ecology retains the fixed 16-to-15 learner token control."
            }
            require(
                PurchaseJson.strings(tokenNode.required("opponentSelectors"), "opponentSelectors")
                    .all { it in setOf("ORDINARY", "FIXED_16_TO_15") }
            ) {
                "Purchase ecology rivals require ordinary or fixed token selectors."
            }
            val expansion = ArenaExpansionImitation.read(source, expansionNode)
            expansion.validate(baseline.model, baseline.model, opponents.map { it.model })
            val tokens = ArenaTokenOrderPolicy.read(source, tokenNode)
            tokens.validate(listOf(configuration), opponents.size)
            val indices =
                PurchaseJson.array(node.required("replayIndices"), "replayIndices").map {
                    PurchaseJson.integer(it, "purchase replay index")
                }
            require(
                indices.size in 1..8 &&
                    indices.distinct() == indices &&
                    indices.all { it in 0 until games }
            ) {
                "Purchase ecology requires one through eight distinct prospective replay indices."
            }
            return PurchaseEcology(expansion, tokens, indices.toList())
        }
    }
}
