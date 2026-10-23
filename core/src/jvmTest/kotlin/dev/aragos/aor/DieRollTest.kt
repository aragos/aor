package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DieRollTest {
    @Test
    fun constructorRejectsResultsOutsideASixSidedDie() {
        for (value in listOf(0, 7)) {
            val failure = assertFailsWith<IllegalArgumentException> { DieRoll(value) }

            assertThat(failure).hasMessageThat().contains("between 1 and 6")
        }
    }

    @Test
    fun rollsCompareByDieValue() {
        assertThat(DieRoll(1)).isLessThan(DieRoll(6))
    }

    @Test
    fun malformedTextHasACommandDiagnostic() {
        val failure = assertFailsWith<RuleViolation> { DieRoll.parse("one") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun impossibleResultsHaveAnOutcomeDiagnostic() {
        val failure = assertFailsWith<RuleViolation> { DieRoll.parse("7") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }
}
