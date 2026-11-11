package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.TokenOrderPolicy

/** Ephemeral authority root; never serialized into an observation or inherited-label sidecar. */
internal data class TokenOrderPending(
    val frozen: TokenValueFrozenRoot,
    val ordinary: BotObservation,
    val continuation: FrozenPolicyContinuation,
)

/** Original prospective metadata without terminal outcomes, plus an optional unopened root. */
internal data class TokenOrderCapture(
    val origin: TokenOrderOrigin,
    val pending: TokenOrderPending?,
) {
    init {
        require((origin.status == TokenValueOriginStatus.COLLECTED) == (pending != null)) {
            "Exactly collected token origins retain an unopened continuation root."
        }
        require(origin.alternatives == null) {
            "Prefix capture cannot contain terminal outcomes."
        }
    }

    /** Compare every original metadata field at the persisted JSON boundary, excluding outcomes. */
    fun verifyParent(recorded: JsonNode) {
        val expected =
            (recorded.deepCopy<JsonNode>() as ObjectNode).apply { remove("alternatives") }
        val actual = PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsString(origin))
        require(expected == actual) {
            "Reconstructed token prefix changed its request, reference, observation, proposals or position."
        }
    }

    fun referenceNeighborIndices(): List<Int> {
        val root = requireNotNull(pending) { "A skipped token origin has no action proposals." }
        val observation =
            requireNotNull(origin.observation) {
                "A captured token root retains its ordinary encoded observation."
            }
        return TokenOrderPolicy.referenceNeighborIndices(root.ordinary, observation)
    }
}
