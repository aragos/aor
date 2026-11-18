package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionFinalScreenTest {
    @Test
    fun unchangedFinalAndInitializerCannotClaimLearnedGain() {
        val report = ExpansionOnPolicyStore.wire(ExpansionFinalScreen.report(worlds(.6, .4, .6)))
        assertThat(report["finalMinusG3"]["rewards"]["mean"].doubleValue()).isWithin(1e-12).of(.2)
        assertThat(report["finalMinusInitializer"]["rewards"]["mean"].doubleValue()).isEqualTo(0.0)
        assertThat(report["developmentGatePassed"].booleanValue()).isFalse()
    }

    @Test
    fun bothControlsAreRequiredForProspectiveNomination() {
        val report = ExpansionOnPolicyStore.wire(ExpansionFinalScreen.report(worlds(.6, .4, .5)))
        assertThat(report["developmentGatePassed"].booleanValue()).isTrue()
        assertThat(report["finalMinusG3"]["rewards"]["count"].intValue()).isEqualTo(1024)
        assertThat(report["qualification"].booleanValue()).isFalse()
    }

    @Test
    fun partialBankHasNoStatistics() {
        assertFailsWith<IllegalArgumentException> {
            ExpansionFinalScreen.report(worlds(.6, .4, .5).take(512))
        }
    }

    @Test
    fun duplicatedWorldIsNotAnotherIndependentUnit() {
        val worlds = worlds(.6, .4, .5)
        assertFailsWith<IllegalArgumentException> {
            ExpansionFinalScreen.report(worlds.dropLast(1) + worlds.first())
        }
    }

    @Test
    fun canonicalHistoricalMismatchRejectsEvenWithSameTerminal() {
        val expected = historical()
        val changed = expected.deepCopy().putArray("entries").add("different")
        val actual = historical().also { it.set<JsonNode>("entries", changed) }
        assertFailsWith<IllegalArgumentException> {
            ExpansionFinalScreen.verifyHistorical(expected, actual)
        }
    }

    @Test
    fun changedLaterControlledCommandRejectsHistoricalParity() {
        val expected = historical()
        val actual = historical()
        (actual["decisions"][0]["chosen"] as ObjectNode).put("command", "different")
        assertFailsWith<IllegalArgumentException> {
            ExpansionFinalScreen.verifyHistorical(expected, actual)
        }
    }

    @Test
    fun exactCanonicalAndCommandsAcceptWithoutPolicyExecution() {
        ExpansionFinalScreen.verifyHistorical(historical(), historical())
    }

    private fun historical(): ObjectNode =
        ExpansionOnPolicyStore.wire(
            mapOf(
                "replayVerified" to true,
                "index" to 0,
                "configuration" to "synthetic",
                "seed" to -1,
                "seat" to 0,
                "side" to "CANDIDATE",
                "opponentIndices" to List(4) { 0 },
                "header" to mapOf("synthetic" to true),
                "entries" to listOf("pass"),
                "decisions" to
                    listOf(
                        mapOf(
                            "snapshotEntryCount" to 0,
                            "commandEntryIndex" to 0,
                            "chosen" to mapOf("command" to "pass"),
                        )
                    ),
            )
        ) as ObjectNode

    private fun worlds(final: Double, raw: Double, initial: Double): List<JsonNode> =
        (0 until 1024).map { index ->
            fun game(value: Double) =
                mapOf(
                    "wins" to List(4) { value },
                    "rewards" to List(4) { value },
                    "ranks" to List(4) { value },
                    "scores" to List(4) { mapOf("total" to 1) },
                )
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "index" to index,
                    "seat" to index % 4,
                    "roles" to
                        mapOf(
                            "FINAL" to game(final),
                            "RAW_G3" to game(raw),
                            "INITIAL" to game(initial),
                        ),
                )
            )
        }
}
