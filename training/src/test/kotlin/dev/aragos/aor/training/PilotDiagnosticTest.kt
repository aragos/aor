package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotDiagnosticTest {
    @Test
    fun teacherUsesOriginalLabelsWhileStudentUsesFrozenPredictions() {
        val labels = row(listOf(0.3, 0.1, 0.2, 0.6))
        val student = constantStudent(listOf(0.3, 0.5, 0.4, 0.1))

        val selection = PilotDiagnostic.select(labels, student)

        assertThat(selection.teacher).isEqualTo(CommodityExperimentStrategy.HOLD_EXPAND)
        assertThat(selection.student).isEqualTo(CommodityExperimentStrategy.PLAY_NOW)
        assertThat(selection.labelRewards).containsExactly(0.3, 0.1, 0.2, 0.6).inOrder()
        assertThat(selection.predictions).containsExactly(0.3, 0.5, 0.4, 0.1).inOrder()
    }

    @Test
    fun teacherTiesRetainBaselineBehavior() {
        val labels = row(listOf(0.6, 0.6, 0.6, 0.6))

        val selection = PilotDiagnostic.select(labels, constantStudent())

        assertThat(selection.teacher).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE)
    }

    @Test
    fun teacherTreatsSampleMeanRoundingAsATie() {
        val labels =
            row(
                listOf(
                    0.29583333333333334,
                    0.2958333333333334,
                    0.29583333333333334,
                    0.29583333333333334,
                )
            )

        val selection = PilotDiagnostic.select(labels, constantStudent())

        assertThat(selection.teacher).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE)
    }

    @Test
    fun numericalTieToleranceDoesNotDiscardSmallMeasuredAdvantages() {
        val labels = row(listOf(0.3, 0.30000000001, 0.2, 0.1))

        val selection = PilotDiagnostic.select(labels, constantStudent())

        assertThat(selection.teacher).isEqualTo(CommodityExperimentStrategy.PLAY_NOW)
    }

    @Test
    fun freshRewardsCannotReselectTheTeachersPlan() {
        val selection = PilotDiagnostic.select(row(listOf(0.3, 0.1, 0.2, 0.6)), constantStudent())
        val outcome =
            PilotDiagnosticOutcome(
                selection = selection,
                baselineRewards = listOf(0.4, 0.4),
                teacherRewards = listOf(0.1, 0.1),
                studentRewards = listOf(0.8, 0.8),
            )

        val report = PilotDiagnostic.report(listOf(outcome))

        assertThat(report.teacherChoices["HOLD_EXPAND"]).isEqualTo(1)
        assertThat(report.teacherVsBaseline.mean).isWithin(1e-12).of(-0.3)
        assertThat(report.studentVsBaseline.mean).isWithin(1e-12).of(0.4)
        assertThat(report.promoted).isFalse()
    }

    @Test
    fun reportUsesEquallyWeightedScenarioMeansInsteadOfIndividualRollouts() {
        val outcomes =
            listOf(
                outcome(
                    index = 0,
                    baseline = listOf(0.5),
                    teacher = listOf(0.9),
                    student = listOf(0.7),
                ),
                outcome(
                    index = 1,
                    baseline = List(100) { 0.5 },
                    teacher = List(100) { 0.3 },
                    student = List(100) { 0.4 },
                ),
            )

        val report = PilotDiagnostic.report(outcomes)

        assertThat(report.teacherVsBaseline.count).isEqualTo(2)
        assertThat(report.teacherVsBaseline.mean).isWithin(1e-12).of(0.1)
        assertThat(report.studentVsBaseline.mean).isWithin(1e-12).of(0.05)
        assertThat(report.teacherVsStudent.mean).isWithin(1e-12).of(0.05)
        assertThat(report.minimumSamplesPerScenario).isEqualTo(1)
        assertThat(report.maximumSamplesPerScenario).isEqualTo(100)
        assertThat(report.teacherVsBaseline.lower95).isLessThan(0.0)
        assertThat(report.baselineMeanReward).isWithin(1e-12).of(0.5)
        assertThat(report.teacherMeanReward).isWithin(1e-12).of(0.6)
        assertThat(report.studentMeanReward).isWithin(1e-12).of(0.55)
    }

    @Test
    fun reportRetainsFamilyResultsAndChoiceCounts() {
        val first = outcome(0, listOf(0.5), listOf(0.9), listOf(0.7))
        val second =
            outcome(1, listOf(0.5), listOf(0.5), listOf(0.5), tag = "final-window").let { outcome ->
                outcome.copy(
                    selection =
                        outcome.selection.copy(
                            teacher = CommodityExperimentStrategy.KEEP_BASELINE,
                            student = CommodityExperimentStrategy.KEEP_BASELINE,
                        )
                )
            }

        val report = PilotDiagnostic.report(listOf(first, second))

        assertThat(report.teacherChoices["HOLD_EXPAND"]).isEqualTo(1)
        assertThat(report.teacherChoices["KEEP_BASELINE"]).isEqualTo(1)
        assertThat(report.studentChoices["KEEP_BASELINE"]).isEqualTo(1)
        val finalWindow =
            checkNotNull(report.byTag["final-window"]) {
                "The final-window family must appear in the diagnostic report."
            }
        assertThat(finalWindow.scenarios).isEqualTo(1)
        assertThat(finalWindow.teacherVsBaseline.mean).isEqualTo(0.0)
    }

    @Test
    fun reorderedLabelStrategiesAreRejected() {
        val labels = row(listOf(0.3, 0.1, 0.2, 0.6))
        (labels.required("strategies")[0] as ObjectNode).put("strategy", "PLAY_NOW")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.select(labels, constantStudent())
            }

        assertThat(failure).hasMessageThat().contains("strategy enum order")
    }

    @Test
    fun nonnumericLabelCannotSilentlyBecomeZero() {
        val labels = row(listOf(0.3, 0.1, 0.2, 0.6))
        (labels.required("strategies")[0].required("reward") as ObjectNode).put("mean", "bad")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.select(labels, constantStudent())
            }

        assertThat(failure).hasMessageThat().contains("label mean must be numeric")
    }

    @Test
    fun nonfiniteLabelsAreRejected() {
        val labels = row(listOf(0.3, Double.NaN, 0.2, 0.6))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.select(labels, constantStudent())
            }

        assertThat(failure).hasMessageThat().contains("finite reward")
    }

    @Test
    fun truncatedFeatureVectorsAreRejectedBeforePrediction() {
        val labels = row(listOf(0.3, 0.1, 0.2, 0.6))
        (labels as ObjectNode).set<JsonNode>("features", ObjectMapper().valueToTree(listOf(0.0)))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.select(labels, constantStudent())
            }

        assertThat(failure).hasMessageThat().contains("feature order and dimension")
    }

    @Test
    fun repeatedScenarioSeedsAreRejected() {
        val first = outcome(0, listOf(0.5), listOf(0.9), listOf(0.7))
        val duplicate = first.copy(selection = first.selection.copy(index = 1))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.report(listOf(first, duplicate))
            }

        assertThat(failure).hasMessageThat().contains("scenario seeds must be unique")
    }

    @Test
    fun repeatedPositionIndicesAreRejected() {
        val first = outcome(0, listOf(0.5), listOf(0.9), listOf(0.7))
        val duplicate = first.copy(selection = first.selection.copy(scenarioSeed = 1))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotDiagnostic.report(listOf(first, duplicate))
            }

        assertThat(failure).hasMessageThat().contains("position indices must be unique")
    }

    @Test
    fun unequalPairedSampleCountsAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                outcome(0, listOf(0.5, 0.5), listOf(0.9), listOf(0.7, 0.7))
            }

        assertThat(failure).hasMessageThat().contains("equal nonempty paired")
    }

    @Test
    fun outOfRangeFreshRewardsAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                outcome(0, listOf(0.5), listOf(1.1), listOf(0.7))
            }

        assertThat(failure).hasMessageThat().contains("finite and between zero and one")
    }

    @Test
    fun diagnosticSeedsAreNegativeAndUniqueAcrossPositionBoundaries() {
        val indices = listOf(0, 1, 9998, 9999)
        val seeds = indices.flatMap { index ->
            (0..255).map { sample -> diagnosticSeed(100_000_000, index, sample) }
        }

        assertThat(seeds.distinct()).hasSize(1024)
        assertThat(seeds.all { it < 0 }).isTrue()
        assertThat(diagnosticSeed(0, 0, 0)).isEqualTo(Int.MIN_VALUE)
    }

    @Test
    fun oversizedSeedCoordinatesAreRejectedInsteadOfWrapping() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                diagnosticSeed(100_000_000, 10000, 0)
            }

        assertThat(failure).hasMessageThat().contains("between 0 and 9999")
    }

    private fun outcome(
        index: Int,
        baseline: List<Double>,
        teacher: List<Double>,
        student: List<Double>,
        tag: String = "opportunity",
    ): PilotDiagnosticOutcome =
        PilotDiagnosticOutcome(
            selection =
                PilotDiagnosticSelection(
                    index = index,
                    scenarioSeed = index,
                    tag = tag,
                    teacher = CommodityExperimentStrategy.HOLD_EXPAND,
                    student = CommodityExperimentStrategy.PLAY_NOW,
                    predictions = listOf(0.3, 0.5, 0.4, 0.1),
                    labelRewards = listOf(0.3, 0.1, 0.2, 0.6),
                ),
            baselineRewards = baseline,
            teacherRewards = teacher,
            studentRewards = student,
        )

    private fun row(values: List<Double>): JsonNode =
        ObjectMapper()
            .valueToTree(
                mapOf(
                    "index" to 0,
                    "seed" to 20261106,
                    "tag" to "opportunity",
                    "features" to List(CommodityExperiment.featureNames.size) { 0.0 },
                    "strategies" to
                        CommodityExperimentStrategy.entries.mapIndexed { index, strategy ->
                            mapOf(
                                "strategy" to strategy.name,
                                "reward" to mapOf("mean" to values[index]),
                            )
                        },
                )
            )

    private fun constantStudent(values: List<Double> = listOf(0.3, 0.5, 0.4, 0.1)): PilotStudent =
        PilotStudent(
            inputWeights = List(24) { List(CommodityExperiment.featureNames.size) { 0.0 } },
            hiddenBias = List(24) { 0.0 },
            outputWeights = List(CommodityExperimentStrategy.entries.size) { List(24) { 0.0 } },
            outputBias = values,
        )
}
