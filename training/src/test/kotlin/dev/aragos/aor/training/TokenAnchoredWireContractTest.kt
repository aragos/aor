package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/** Constructed transport rows contain neither authority positions nor simulated outcomes. */
class TokenAnchoredWireContractTest {
    @Test
    fun anchoredPublicSidecarPreservesRoundAndBothReferenceRolesWithoutLabels() {
        val origin = origin()

        val public = TokenPairedStore.publicSidecar(origin, "d".repeat(64))

        assertThat(public["kind"].asText()).isEqualTo("token-anchored-paired-public-1")
        assertThat(public["round"].intValue()).isEqualTo(2)
        assertThat(public.has("iteration")).isFalse()
        assertThat(public["publicRoot"]["referenceIndex"].intValue()).isEqualTo(1)
        assertThat(public["publicRoot"]["rawReferenceIndex"].intValue()).isEqualTo(0)
        assertThat(public["publicRoot"]["inclusionProbabilities"].map(JsonNode::doubleValue))
            .containsExactly(.5, 1.0, 1.0)
            .inOrder()
        assertThat(public.has("arms")).isFalse()
        assertThat(public.has("backboneTerminal")).isFalse()
    }

    @Test
    fun anchoredLabelsUseTheirOwnVersionAndPreservePerActionInclusion() {
        val labels = TokenPairedStore.labelSidecar(origin(), "d".repeat(64))

        assertThat(labels["kind"].asText()).isEqualTo("token-anchored-paired-labels-1")
        assertThat(labels["round"].intValue()).isEqualTo(2)
        assertThat(labels["arms"][0]["inclusionProbability"].doubleValue()).isEqualTo(.5)
        assertThat(labels.has("publicRoot")).isFalse()
    }

    private fun origin(): JsonNode =
        PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to "token-anchored-paired-origin-1",
                "signature" to "a".repeat(64),
                "scopeSha256" to "b".repeat(64),
                "scienceSha256" to "c".repeat(64),
                "round" to 2,
                "request" to mapOf("globalIndex" to 1),
                "streams" to emptyMap<String, Any>(),
                "status" to "READY",
                "missingReason" to null,
                "observedWindows" to 1,
                "publicRoot" to
                    mapOf(
                        "referenceIndex" to 1,
                        "rawReferenceIndex" to 0,
                        "mandatoryIndices" to listOf(1, 2),
                        "inclusionProbabilities" to listOf(.5, 1.0, 1.0),
                    ),
                "anchors" to emptyList<Any>(),
                "arms" to
                    listOf(
                        mapOf(
                            "actionIndex" to 0,
                            "command" to "buy-tokens 20",
                            "inclusionProbability" to .5,
                            "outcomes" to emptyList<Any>(),
                        )
                    ),
            )
        )
}
