package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedWinScreenTest {
    @Test
    fun finalMustBeatBothStartAndSupportedControlOnWins() {
        val report = CoordinatedWinScreenMain.report(worlds(.6, .4, .5))
        assertThat(report["learningGatePassed"]).isEqualTo(true)
        assertThat(report["developmentGatePassed"]).isEqualTo(true)
        assertThat(report["qualification"]).isEqualTo(false)
    }

    @Test
    fun beatingControlCannotHideFailureToBeatTheWarmStart() {
        val report = CoordinatedWinScreenMain.report(worlds(.5, .6, .4))
        assertThat(report["learningGatePassed"]).isEqualTo(false)
        assertThat(report["developmentGatePassed"]).isEqualTo(false)
    }

    @Test
    fun partialWorldBankCannotPublishEffects() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedWinScreenMain.report(worlds(.6, .4, .5).dropLast(1))
        }
    }

    private fun worlds(final: Double, start: Double, control: Double): List<JsonNode> =
        (0..2047).map { index ->
            fun game(value: Double) =
                mapOf(
                    "wins" to List(4) { value },
                    "rewards" to List(4) { 1.0 - value },
                    "ranks" to List(4) { .5 },
                    "scores" to (4 downTo 1).map { mapOf("player" to "p$it", "total" to 10) },
                )
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "index" to index,
                    "seat" to index % 4,
                    "roles" to
                        mapOf(
                            "FINAL" to game(final),
                            "START" to game(start),
                            "CONTROL" to game(control),
                        ),
                )
            )
        }
}
