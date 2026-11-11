package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.TokenOrderPolicy

/**
 * Produces only missing fixed-T returns; inherited raw outcomes remain in their immutable parent.
 */
internal class TokenOrderNeighborAugmentation(
    private val input: TokenOrderNeighborInput,
    deadlineNanos: Long,
) {
    private val collector =
        TokenOrderCollector(input.parent.specification, input.parent.signature, deadlineNanos)

    fun origin(index: Int): Map<String, Any?> {
        val recorded = input.recorded(index)
        val missing = input.missing(index)
        val previous = recorded["proposedIndices"]?.map(JsonNode::asInt).orEmpty()
        val captured =
            if (missing.isEmpty()) null
            else collector.capture(index).also { it.verifyParent(recorded) }
        val proposed = captured?.referenceNeighborIndices() ?: previous
        require(proposed.filter { it !in previous } == missing) {
            "Actual actor-visible proposals differ from the predeclared missing branches."
        }
        val alternatives = captured?.let { collector.finish(it, missing) }.orEmpty()
        val specification = input.parent.specification
        val seed = recorded.required("gameSeed").asInt()
        return mapOf(
            "schemaVersion" to 1,
            "kind" to TokenOrderNeighborInput.KIND,
            "index" to index,
            "split" to recorded.required("split").asText(),
            "status" to recorded.required("status").asText(),
            "parentOriginSha256" to input.parent.files["origin-$index.json"],
            "parentSignature" to input.parent.signature,
            "parentProposedIndices" to previous,
            "proposedIndices" to proposed,
            "reusedIndices" to proposed.filter { it in previous },
            "newIndices" to missing,
            "omittedIndices" to previous.filter { it !in proposed },
            "referenceIndex" to recorded["referenceIndex"]?.asInt(),
            "originPolicySeed" to seed,
            "continuationSeeds" to
                if (previous.isEmpty()) emptyList()
                else List(8) { specification.continuationSeed(seed, it) },
            "parentMetadataValidated" to true,
            "prefixReconstructed" to (captured != null),
            "prefixMetadataExact" to (captured != null),
            "prefixPlayerActions" to captured?.origin?.originDecisions,
            "newAlternatives" to alternatives,
        )
    }

    fun metadata(): Map<String, Any> =
        mapOf(
            "schemaVersion" to 1,
            "kind" to TokenOrderNeighborInput.KIND,
            "sourceSha256" to input.sourceSha256,
            "parentSignature" to input.parent.signature,
            "parentFiles" to input.parent.files,
            "proposalVersion" to TokenOrderPolicy.REFERENCE_NEIGHBOR_PROPOSAL_VERSION,
            "selectorVersion" to TokenOrderPolicy.REFERENCE_NEIGHBOR_SELECTOR_VERSION,
            "referenceProtocol" to input.parent.specification.reference.version,
            "replayOrigins" to input.replayOrigins,
            "origins" to 640,
            "newBranches" to 314,
            "newTerminalTails" to 2512,
            "oldTerminalTailsRerun" to 0,
            "validationRole" to "DEVELOPMENT_ONLY",
            "productionActivation" to false,
        )
}
