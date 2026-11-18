package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import kotlin.test.assertFailsWith
import org.junit.Test

class CoordinatedEsPanelTest {
    private val observation =
        TerritoryObservation(
            TerritoryEncoder.TOKEN_2.version,
            mapOf("public" to 1.0),
            listOf(TerritoryAction("a", emptyMap()), TerritoryAction("b", emptyMap())),
            1,
        )

    @Test
    fun firstAndLastBranchingWindowsPreserveOriginalOffsets() {
        val panel = CoordinatedEsPanel(4)
        listOf(11, 21, 31).forEach { number ->
            panel.observe(CoordinatedEsHead.TOKEN, number, number - 1, 2, observation)
        }
        assertThat(panel.heads()["TOKEN"]?.map { it.number }).containsExactly(11, 31).inOrder()
        assertThat(panel.heads()["TOKEN"]?.map { it.batchStartNumber })
            .containsExactly(10, 30)
            .inOrder()
        assertThat(panel.windows()["TOKEN"]).isEqualTo(CoordinatedEsPanelWindows(3, 3))
    }

    @Test
    fun oneBranchingWindowIsNotDuplicated() {
        val panel = CoordinatedEsPanel(0)
        panel.observe(CoordinatedEsHead.TOKEN, 11, 10, 1, observation)
        assertThat(panel.heads()["TOKEN"]).hasSize(1)
    }

    @Test
    fun otherEpochAndSingletonDoNotFillMissingPanel() {
        val panel = CoordinatedEsPanel(0)
        panel.observe(CoordinatedEsHead.TOKEN, 11, 10, 2, observation)
        panel.observe(
            CoordinatedEsHead.TOKEN,
            21,
            20,
            1,
            observation.copy(actions = observation.actions.take(1), baselineIndex = 0),
        )
        assertThat(panel.heads()["TOKEN"]).isEmpty()
        assertThat(panel.windows()["TOKEN"]).isEqualTo(CoordinatedEsPanelWindows(1, 0))
    }

    @Test
    fun publicWireContainsOnlyOffsetsReferenceAndTypedObservation() {
        val panel = CoordinatedEsPanel(0)
        panel.observe(CoordinatedEsHead.TOKEN, 11, 10, 1, observation)
        val row =
            PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                panel.heads()["TOKEN"]?.single()
            )
        assertThat(row.fieldNames().asSequence().toSet())
            .containsExactly(
                "number",
                "batchStartNumber",
                "epoch",
                "referenceIndex",
                "observation",
            )
        assertThat(row["observation"]["stateFeatures"]["public"].asDouble()).isEqualTo(1.0)
    }

    @Test
    fun mismatchedHeadEncoderIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsPanel(0).observe(CoordinatedEsHead.EXPANSION, 1, 1, 1, observation)
        }
    }
}
