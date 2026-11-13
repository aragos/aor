package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionBaselineScreenTest {
    @Test
    fun improvementOverControlAloneIsMechanismEvidenceWithoutNomination() {
        val report = report(.4, .3, .5)
        assertThat(report["primaryMechanismPassed"].booleanValue()).isTrue()
        assertThat(report["developmentGatePassed"].booleanValue()).isFalse()
        assertThat(report["treatmentMinusControl"]["rewards"]["mean"].doubleValue())
            .isWithin(1e-12)
            .of(.1)
        assertThat(report["treatmentMinusG3"]["rewards"]["mean"].doubleValue())
            .isWithin(1e-12)
            .of(-.1)
    }

    @Test
    fun identicalTreatmentAndControlCannotClaimBaselineBenefit() {
        val report = report(.6, .6, .4)
        assertThat(report["primaryMechanismPassed"].booleanValue()).isFalse()
        assertThat(report["developmentGatePassed"].booleanValue()).isFalse()
        assertThat(report["treatmentMinusControl"]["rewards"]["mean"].doubleValue()).isEqualTo(0.0)
    }

    @Test
    fun bothComparisonsAreRequiredForDevelopmentNomination() {
        val report = report(.6, .5, .4)
        assertThat(report["primaryMechanismPassed"].booleanValue()).isTrue()
        assertThat(report["developmentGatePassed"].booleanValue()).isTrue()
        assertThat(report["treatmentMinusControl"]["rewards"]["count"].intValue()).isEqualTo(1024)
        assertThat(report["controlMinusG3"]["rewards"]["mean"].doubleValue()).isWithin(1e-12).of(.1)
        assertThat(report["qualification"].booleanValue()).isFalse()
    }

    @Test
    fun uncertaintyUsesWholeWorldsAndActualControlledSeat() {
        val worlds = worlds(.6, .5, .4, treatmentChaos = true)
        val report = ExpansionOnPolicyStore.wire(ExpansionBaselineScreen.report(worlds))
        assertThat(report["treatmentMinusControl"]["chaos"]["mean"].doubleValue()).isEqualTo(1.0)
        assertThat(report["controlMinusG3"]["chaos"]["mean"].doubleValue()).isEqualTo(0.0)
        assertThat(report["treatmentMinusControl"]["rewards"]["count"].intValue()).isEqualTo(1024)
    }

    @Test
    fun partialBankCannotOpenEffects() {
        assertFailsWith<IllegalArgumentException> {
            ExpansionBaselineScreen.report(worlds(.6, .5, .4).take(512))
        }
    }

    @Test
    fun duplicateWorldCannotReplaceAnotherIndependentWorld() {
        val worlds = worlds(.6, .5, .4)
        assertFailsWith<IllegalArgumentException> {
            ExpansionBaselineScreen.report(worlds.dropLast(1) + worlds.first())
        }
    }

    private fun report(treatment: Double, control: Double, raw: Double): JsonNode =
        ExpansionOnPolicyStore.wire(ExpansionBaselineScreen.report(worlds(treatment, control, raw)))

    private fun worlds(
        treatment: Double,
        control: Double,
        raw: Double,
        treatmentChaos: Boolean = false,
    ): List<JsonNode> =
        (0 until 1024).map { index ->
            val seat = index % 4
            fun game(value: Double, chaos: Boolean = false) =
                mapOf(
                    "wins" to List(4) { if (it == seat) value else 0.0 },
                    "rewards" to List(4) { if (it == seat) value else 0.0 },
                    "ranks" to List(4) { if (it == seat) value else 0.0 },
                    "scores" to List(4) { mapOf("total" to if (it == seat && chaos) null else 1) },
                )
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "index" to index,
                    "seat" to seat,
                    "replica" to "PRIMARY_DESKTOP",
                    "roles" to
                        mapOf(
                            "TREATMENT" to game(treatment, treatmentChaos),
                            "CONTROL" to game(control),
                            "RAW_G3" to game(raw),
                        ),
                )
            )
        }
}
