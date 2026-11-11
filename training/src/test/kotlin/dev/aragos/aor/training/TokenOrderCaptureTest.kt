package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderCaptureTest {
    private val skipped =
        TokenOrderOrigin(
            signature = "original-runtime-signature",
            index = 7,
            split = TokenValueSplit.TRAINING,
            configuration = "fixture",
            gameSeed = -91,
            seat = 2,
            opponentIndices = listOf(0, 1, 2, 3),
            requestedEpoch = 2,
            requestedOrdinal = 1,
            originDecisions = 140,
            status = TokenValueOriginStatus.SKIPPED,
            skipReason = TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED,
        )

    @Test
    fun capturedSkipRetainsItsExactHistoricalMetadataWithoutOutcomes() {
        val capture = TokenOrderCapture(skipped, null)
        val persisted =
            PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsString(skipped))

        capture.verifyParent(persisted)

        assertThat(capture.origin).isEqualTo(skipped)
    }

    @Test
    fun changedHistoricalOrdinalIsRejectedBeforeAnyBranch() {
        val capture = TokenOrderCapture(skipped, null)
        val recorded =
            PurchaseJson.mapper.valueToTree<ObjectNode>(skipped).apply {
                put("requestedOrdinal", 2)
            }

        assertFailsWith<IllegalArgumentException> { capture.verifyParent(recorded) }
    }

    @Test
    fun capturedMetadataCannotPretendAnUnopenedRootExists() {
        assertFailsWith<IllegalArgumentException> {
            TokenOrderCapture(skipped.copy(status = TokenValueOriginStatus.COLLECTED), null)
        }
    }

    @Test
    fun runtimeCompatibilityRejectsAnUnlistedSimulationClassChange() {
        assertFailsWith<IllegalArgumentException> {
            TokenOrderParentRuntime.verifyClasses(
                mapOf("engine.class" to byteArrayOf(1)),
                mapOf("engine.class" to byteArrayOf(2)),
                listOf("adapter"),
            )
        }
    }

    @Test
    fun runtimeCompatibilityAllowsOnlyExactOwnerAndNestedClasses() {
        TokenOrderParentRuntime.verifyClasses(
            mapOf("adapter.class" to byteArrayOf(1), "engine.class" to byteArrayOf(3)),
            mapOf(
                "adapter.class" to byteArrayOf(2),
                "adapter\$Nested.class" to byteArrayOf(4),
                "engine.class" to byteArrayOf(3),
            ),
            listOf("adapter"),
        )
    }

    @Test
    fun runtimeCompatibilityRejectsOwnerPrefixLookalikes() {
        assertFailsWith<IllegalArgumentException> {
            TokenOrderParentRuntime.verifyClasses(
                emptyMap(),
                mapOf("adapterOther.class" to byteArrayOf(1)),
                listOf("adapter"),
            )
        }
    }

    @Test
    fun relocationChangesOnlyExactDeclaredStringsAndPreservesOriginalTree() {
        val original =
            PurchaseJson.mapper.readTree(
                """{"path":"/old/a","text":"/old/a-suffix","nested":[7,"/old/a"]}"""
            )
        val expected =
            PurchaseJson.mapper.readTree(
                """{"path":"/local/a","text":"/old/a-suffix","nested":[7,"/local/a"]}"""
            )

        val relocated = TokenOrderParentSource.substitute(original, mapOf("/old/a" to "/local/a"))

        assertThat(relocated).isEqualTo(expected)
        assertThat(original["path"].asText()).isEqualTo("/old/a")
    }
}
