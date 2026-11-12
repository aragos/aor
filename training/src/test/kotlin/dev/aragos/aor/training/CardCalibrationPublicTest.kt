package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardCalibrationPublicTest {
    private fun observation(count: Int) =
        mapOf(
            "encoderVersion" to "synthetic",
            "stateFeatures" to emptyMap<String, Double>(),
            "baselineIndex" to 0,
            "actions" to
                (0 until count).map {
                    mapOf("command" to "$it", "features" to emptyMap<String, Double>())
                },
        )

    private fun project(records: List<Any>) =
        CardCalibrationPublic.rows(
            ByteArrayInputStream(
                PurchaseJson.mapper.writeValueAsBytes(
                    mapOf(
                        "index" to 0,
                        "signature" to "fixture",
                        "terminal" to mapOf("secret" to listOf("must", "remain", "opaque")),
                        "cardDecisions" to records,
                        "purchaseDecisions" to emptyList<Any>(),
                    )
                )
            ),
            0,
            "a".repeat(64),
            "fixture",
        )

    private fun terminalExport(original: String) =
        PurchaseJson.mapper.valueToTree<JsonNode>(
            mapOf(
                "kind" to "joint-credit-terminal-export-1",
                "newFits" to 0,
                "sha256" to "65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d",
                "originalBundle" to mapOf("sha256" to original),
                "components" to
                    mapOf(
                        "card" to
                            mapOf(
                                "sha256" to
                                    "916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf"
                            ),
                        "purchase" to
                            mapOf(
                                "sha256" to
                                    "6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79"
                            ),
                    ),
            )
        )

    @Test
    fun pathOnlyPortableBundleMayDifferFromOriginalBatchBundleBytes() {
        val receipt =
            PurchaseJson.mapper.valueToTree<JsonNode>(mapOf("outputBundleSha256" to "a".repeat(64)))
        CardCalibrationPublicMain.validateTerminalExport(receipt, terminalExport("a".repeat(64)))
    }

    @Test
    fun portableExportCannotBindAnUnrelatedOriginalBatch() {
        val receipt =
            PurchaseJson.mapper.valueToTree<JsonNode>(mapOf("outputBundleSha256" to "b".repeat(64)))
        assertFailsWith<IllegalArgumentException> {
            CardCalibrationPublicMain.validateTerminalExport(
                receipt,
                terminalExport("a".repeat(64)),
            )
        }
    }

    @Test
    fun onlyFirst32NonforcedInputsSurviveAndAllBehaviorLabelsAreSkipped() {
        val records =
            (0..40).map { number ->
                mapOf(
                    "number" to number,
                    "observation" to observation(if (number == 0) 1 else 2),
                    "selectedIndex" to mapOf("poison" to true),
                    "behaviorLogProbability" to "not decoded",
                    "behaviorValue" to "not decoded",
                )
            }

        val rows = project(records)

        assertThat(rows).hasSize(32)
        assertThat(rows.map { it["number"] }).containsExactlyElementsIn((1..32).toList()).inOrder()
        assertThat(rows.first().keys)
            .containsExactly("world", "phase", "number", "gameSha256", "observation")
    }

    @Test
    fun recordsAfterTheFixedCapAreNotDecodedAsObservations() {
        val records =
            (0..31).map { mapOf("number" to it, "observation" to observation(2)) } +
                listOf(mapOf("observation" to "opaque after cap"))

        assertThat(project(records)).hasSize(32)
    }

    @Test
    fun outOfOrderOriginalDecisionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            project(
                listOf(
                    mapOf("number" to 2, "observation" to observation(2)),
                    mapOf("number" to 1, "observation" to observation(2)),
                )
            )
        }
    }
}
