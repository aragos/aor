package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotValidationTest {
    @Test
    fun positiveUnseenScenarioImprovementQualifiesForTournament() {
        val outcomes = List(32) { seed -> outcome(seed, listOf(0.2, 0.4), listOf(0.4, 0.6)) }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isTrue()
        assertThat(report.rewardDifference.count).isEqualTo(32)
        assertThat(report.rewardDifference.mean).isWithin(1e-12).of(0.2)
        assertThat(report.minimumSamplesPerScenario).isEqualTo(2)
    }

    @Test
    fun unchangedBaselineBehaviorDoesNotQualifyAsImprovement() {
        val outcomes =
            List(32) { seed ->
                outcome(seed, listOf(0.2, 0.4), listOf(0.2, 0.4), strategy = "KEEP_BASELINE")
            }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isFalse()
        assertThat(report.rewardDifference.mean).isEqualTo(0.0)
        assertThat(report.fallbackFraction).isEqualTo(1.0)
    }

    @Test
    fun worseStudentDoesNotQualifyForTournament() {
        val outcomes = List(32) { seed -> outcome(seed, listOf(0.6, 0.8), listOf(0.2, 0.4)) }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isFalse()
        assertThat(report.rewardDifference.mean).isLessThan(0.0)
    }

    @Test
    fun smallScenarioSetStopsBeforeTournamentEvenWithLargeApparentGain() {
        val outcomes = List(31) { seed -> outcome(seed, listOf(0.0, 0.0), listOf(1.0, 1.0)) }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isFalse()
        assertThat(report.reason).contains("32 unseen scenarios")
    }

    @Test
    fun singleContinuationPerScenarioDoesNotQualifyForTournament() {
        val outcomes = List(32) { seed -> outcome(seed, listOf(0.0), listOf(1.0)) }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isFalse()
        assertThat(report.reason).contains("at least 2 fresh paired continuations")
    }

    @Test
    fun moreRolloutsDoNotTreatOneScenarioAsManyIndependentPositions() {
        val outcomes =
            List(32) { seed ->
                val student = if (seed < 16) 0.7 else 0.35
                outcome(seed, List(100) { 0.5 }, List(100) { student })
            }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isFalse()
        assertThat(report.rewardDifference.count).isEqualTo(32)
        assertThat(report.rewardDifference.mean).isWithin(1e-12).of(0.025)
        assertThat(report.maximumSamplesPerScenario).isEqualTo(100)
        assertThat(report.rewardDifference.lower95).isLessThan(0.0)
    }

    @Test
    fun familyResultsShowFallbackAndGainWithoutCreatingSeparateGates() {
        val outcomes =
            List(32) { seed ->
                if (seed < 16) {
                    outcome(seed, listOf(0.2, 0.2), listOf(0.5, 0.5), tag = "opportunity")
                } else {
                    outcome(
                        seed,
                        listOf(0.4, 0.4),
                        listOf(0.4, 0.4),
                        "holding-cost",
                        "KEEP_BASELINE",
                    )
                }
            }

        val report = PilotValidation.report(outcomes)

        assertThat(report.passed).isTrue()
        assertThat(report.fallbackFraction).isEqualTo(0.5)
        val opportunity =
            checkNotNull(report.byTag["opportunity"]) {
                "The report must retain the opportunity family."
            }
        val holding =
            checkNotNull(report.byTag["holding-cost"]) {
                "The report must retain the holding-cost family."
            }
        assertThat(opportunity.rewardDifference.mean).isWithin(1e-12).of(0.3)
        assertThat(opportunity.fallbackFraction).isEqualTo(0.0)
        assertThat(holding.rewardDifference.mean).isEqualTo(0.0)
        assertThat(holding.fallbackFraction).isEqualTo(1.0)
    }

    @Test
    fun duplicateScenarioSeedsAreRejected() {
        val repeated = outcome(7, listOf(0.2, 0.4), listOf(0.4, 0.6))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotValidation.report(listOf(repeated, repeated))
            }

        assertThat(failure).hasMessageThat().contains("scenario seeds must be unique")
    }

    @Test
    fun mismatchedPairedSampleCountsAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                outcome(7, listOf(0.2, 0.4), listOf(0.4))
            }

        assertThat(failure).hasMessageThat().contains("equal nonempty paired")
    }

    @Test
    fun nonfiniteRewardsAreRejectedAtTheOutcomeBoundary() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                outcome(7, listOf(0.2, 0.4), listOf(Double.NaN, 0.6))
            }

        assertThat(failure).hasMessageThat().contains("finite and between zero and one")
    }

    private fun outcome(
        seed: Int,
        baseline: List<Double>,
        student: List<Double>,
        tag: String = "opportunity",
        strategy: String = "HOLD_EXPAND",
    ): PilotValidationOutcome = PilotValidationOutcome(seed, tag, baseline, student, strategy)
}
