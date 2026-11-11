package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Snapshot-bound continuation semantics, separate from the unchanged token encoder and model. */
internal object TokenOrderContinuationArtifacts {
    private const val REFERENCE_VERSION = "ordinary-token-reference-1"

    fun metadata(specification: TokenOrderSpecification, directory: Path): Map<String, Any> {
        val protocol = specification.continuationProtocol ?: return emptyMap()
        validateWeights(specification, directory)
        return mapOf(
            "continuationProtocol" to protocol.name,
            "replayOrigins" to specification.replayOrigins,
            "tokenReference" to
                mapOf(
                    "version" to REFERENCE_VERSION,
                    "ordinaryModelSnapshot" to "initial.json",
                    "ordinaryModelSha256" to PurchaseJson.hash(directory.resolve("initial.json")),
                ),
        )
    }

    /**
     * The opt-in wire contract requires matching explicit dictionaries outside the eight expansion
     * coordinates. A Python fitter therefore never has to reconstruct the native model prior.
     */
    private fun validateWeights(specification: TokenOrderSpecification, directory: Path) {
        val initial = weights(directory.resolve("initial.json"))
        require(initial == weights(directory.resolve("incumbent.json"))) {
            "Arena token collection requires identical serialized ordinary reference weights."
        }
        val overlays =
            PurchaseJson.mapper
                .valueToTree<JsonNode>(specification.arena.overlayMetadata())
                .required("expansionImitation")
        fun compare(input: JsonNode, ordinary: Map<String, Double>) {
            val snapshot = PurchaseJson.text(input.required("snapshot"), "expansion snapshot")
            val expansion = weights(directory.resolve(snapshot))
            val names = ExpansionImitationProtocol.features
            require(expansion.filterKeys { it !in names } == ordinary.filterKeys { it !in names }) {
                "Serialized expansion weights must preserve every explicit non-expansion coefficient."
            }
        }
        compare(overlays.required("candidateModel"), initial)
        overlays["opponentModels"]?.forEachIndexed { index, input ->
            if (!input.isNull) compare(input, weights(directory.resolve("opponent-$index.json")))
        }
    }

    private fun weights(path: Path): Map<String, Double> {
        val values = PurchaseJson.read(path).required("weights")
        require(values.isObject) { "A serialized ordinary model requires a weight dictionary." }
        return values.properties().associate { (name, value) ->
            name to PurchaseJson.number(value, "weight $name")
        }
    }
}
