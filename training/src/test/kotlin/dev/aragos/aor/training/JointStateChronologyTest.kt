package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointStateChronologyTest {
    private val projection =
        JointStateChronology(
            mapOf(
                JointStatePhase.CARD to listOf("cash", "epoch"),
                JointStatePhase.PURCHASE to listOf("cash", "epoch"),
            )
        )

    @Test
    fun interleavingUsesRecordedAbsoluteDecisionsAndUnchangedUnits() {
        val game = document(cards = listOf(row(12, 2, .5)), purchases = listOf(row(7, 1, .333)))

        val rows = projection.project(game)

        assertThat(rows.map(JointStateRow::number)).containsExactly(7, 12).inOrder()
        assertThat(rows.map(JointStateRow::phase))
            .containsExactly(JointStatePhase.PURCHASE, JointStatePhase.CARD)
            .inOrder()
        assertThat(rows[0].stateFeatures).containsExactly("purchase.epoch", .333)
        assertThat(rows[1].stateFeatures).containsExactly("card.epoch", .5)
    }

    @Test
    fun actionBaselineFutureAndTerminalFieldsNeverEnterEarlierState() {
        val game = document(cards = listOf(row(2, 1, .333)))
        val before = projection.project(game).first()
        (game["cardDecisions"][0]["observation"] as ObjectNode)
            .put("baselineIndex", 91)
            .put("behaviorValue", 999)
        game.put("terminal", "different future")
        (game["cardDecisions"] as ArrayNode).add(
            PurchaseJson.mapper.valueToTree<JsonNode>(row(20, 4, 1.0))
        )

        assertThat(projection.project(game).first()).isEqualTo(before)
    }

    @Test
    fun emptyPhasesPreserveAnEmptyOriginWithoutInventingRows() {
        assertThat(projection.project(document())).isEmpty()
    }

    @Test
    fun duplicateAbsoluteNumbersAcrossPhasesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            projection.project(document(listOf(row(2, 1, .333)), listOf(row(2, 1, .333))))
        }
    }

    @Test
    fun unknownStateKeysCannotBecomeAFeatureByObservation() {
        val game = document(cards = listOf(row(2, 1, .333)))
        (game["cardDecisions"][0]["observation"]["stateFeatures"] as ObjectNode).put(
            "futureOutcome",
            1,
        )

        assertFailsWith<IllegalArgumentException> { projection.project(game) }
    }

    @Test
    fun dictionaryIncludesBothCompletePhaseVocabulariesEvenForAbsentFeatures() {
        assertThat(projection.stateFeatureNames)
            .containsExactly(
                "card.cash",
                "card.epoch",
                "purchase.cash",
                "purchase.epoch",
            )
            .inOrder()
    }

    private fun row(number: Int, turn: Int, epoch: Double): Map<String, Any> =
        mapOf(
            "number" to number,
            "turn" to turn,
            "observation" to
                mapOf("stateFeatures" to mapOf("epoch" to epoch), "baselineIndex" to 0),
        )

    private fun document(
        cards: List<Any> = emptyList(),
        purchases: List<Any> = emptyList(),
    ): ObjectNode =
        PurchaseJson.mapper.valueToTree(
            mapOf("cardDecisions" to cards, "purchaseDecisions" to purchases)
        )
}
