package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotStudentTest {
    private val examples =
        listOf(-1.0, 1.0).flatMap { first ->
            listOf(-1.0, 1.0).map { second ->
                PilotExample(
                    features = listOf(first, second),
                    values =
                        if (first == second) {
                            listOf(0.4, 0.9, 0.1, 0.3)
                        } else {
                            listOf(0.4, 0.1, 0.9, 0.3)
                        },
                )
            }
        }

    @Test
    fun learnerSeparatesPlanChoicesThatRequireFeatureInteractions() {
        // A four-row capacity test needs enough passes to resolve all four reward outputs.
        val student = PilotStudent.fit(examples, seed = 17, epochs = 800)

        for (example in examples) {
            val expectedPlan = example.values.indices.maxBy { example.values[it] }
            assertThat(student.choose(example.features)).isEqualTo(expectedPlan)
            val predictions = student.predict(example.features)
            for (plan in predictions.indices) {
                assertThat(predictions[plan]).isWithin(0.08).of(example.values[plan])
            }
        }
    }

    @Test
    fun fixedSeedAndRowsProduceIdenticalModels() {
        val first = PilotStudent.fit(examples, seed = 17, epochs = 20)

        val second = PilotStudent.fit(examples, seed = 17, epochs = 20)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun expiredDeadlineStopsFittingBeforeTraining() {
        val expired = System.nanoTime() - 1

        val failure =
            assertFailsWith<CancellationException> {
                PilotStudent.fit(examples, seed = 17, deadlineNanos = expired)
            }

        assertThat(failure).hasMessageThat().contains("deadline")
    }

    @Test
    fun savedArtifactRetainsIdenticalPredictions() {
        val original = PilotStudent.fit(examples, seed = 17, epochs = 20)
        val mapper = ObjectMapper()
        val serialized = mapper.writeValueAsString(original)

        val restored = PilotStudent.fromJson(mapper.readTree(serialized))

        assertThat(restored.predict(listOf(0.25, -0.5)))
            .containsExactlyElementsIn(original.predict(listOf(0.25, -0.5)))
            .inOrder()
    }

    @Test
    fun trainingRejectsInconsistentFeatureDimensions() {
        val inconsistent = examples + PilotExample(listOf(0.5), listOf(0.2, 0.3, 0.4, 0.5))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotStudent.fit(inconsistent, seed = 17)
            }

        assertThat(failure).hasMessageThat().contains("share an input dimension")
    }

    @Test
    fun inferenceRejectsMissingFeatures() {
        val student = PilotStudent.fit(examples, seed = 17, epochs = 1)

        val failure = assertFailsWith<IllegalArgumentException> { student.predict(listOf(0.5)) }

        assertThat(failure).hasMessageThat().contains("trained input dimension")
    }

    @Test
    fun artifactRejectsInvalidHiddenLayerDimensions() {
        val student = PilotStudent.fit(examples, seed = 17, epochs = 1)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                student.copy(hiddenBias = student.hiddenBias.dropLast(1))
            }

        assertThat(failure).hasMessageThat().contains("24 hidden units")
    }

    @Test
    fun artifactRejectsNonnumericWeights() {
        val mapper = ObjectMapper()
        val root = mapper.createObjectNode()
        root.putArray("inputWeights").addArray().add("secret")

        val failure = assertFailsWith<IllegalArgumentException> { PilotStudent.fromJson(root) }

        assertThat(failure).hasMessageThat().contains("finite numbers")
    }

    @Test
    fun teacherTargetsRejectOutOfRangeRewards() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotExample(listOf(0.1), listOf(0.5, 1.1, 0.2, 0.3))
            }

        assertThat(failure).hasMessageThat().contains("between zero and one")
    }

    @Test
    fun equalPredictionsRetainBaselineBehavior() {
        val student = fixedPredictions(listOf(0.5, 0.5, 0.5, 0.5))

        val choice = student.choose(listOf(0.0))

        assertThat(choice).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE.ordinal)
    }

    @Test
    fun smallPredictedImprovementRetainsBaselineBehavior() {
        val student = fixedPredictions(listOf(0.5, 0.51, 0.49, 0.4))

        val choice = student.choose(listOf(0.0))

        assertThat(choice).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE.ordinal)
    }

    @Test
    fun meaningfulPredictedImprovementSelectsTheBestPlan() {
        val student = fixedPredictions(listOf(0.5, 0.55, 0.6, 0.4))

        val choice = student.choose(listOf(0.0))

        assertThat(choice).isEqualTo(CommodityExperimentStrategy.HOLD_ORDINARY.ordinal)
    }

    @Test
    fun improvementMustStrictlyExceedTheMargin() {
        val student = fixedPredictions(listOf(0.5, 0.625, 0.4, 0.3))

        val choice = student.choose(listOf(0.0), minimumAdvantage = 0.125)

        assertThat(choice).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE.ordinal)
    }

    @Test
    fun invalidAdvantageMarginIsRejected() {
        val student = fixedPredictions(listOf(0.5, 0.6, 0.4, 0.3))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                student.choose(listOf(0.0), minimumAdvantage = Double.NaN)
            }

        assertThat(failure).hasMessageThat().contains("minimum predicted advantage")
    }

    @Test
    fun legacyThreeOutputArtifactIsRejected() {
        val mapper = ObjectMapper()
        val legacy =
            mapOf(
                "inputWeights" to List(24) { listOf(0.0) },
                "hiddenBias" to List(24) { 0.0 },
                "outputWeights" to List(3) { List(24) { 0.0 } },
                "outputBias" to listOf(0.1, 0.2, 0.3),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotStudent.fromJson(mapper.readTree(mapper.writeValueAsString(legacy)))
            }

        assertThat(failure).hasMessageThat().contains("including the baseline")
    }

    @Test
    fun teacherTargetsRequireBaselineAsWellAsAlternativeRewards() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PilotExample(listOf(0.1), listOf(0.5, 0.2, 0.3))
            }

        assertThat(failure).hasMessageThat().contains("one finite reward per strategy")
    }

    private fun fixedPredictions(values: List<Double>): PilotStudent =
        PilotStudent(
            inputWeights = List(24) { listOf(0.0) },
            hiddenBias = List(24) { 0.0 },
            outputWeights = List(values.size) { List(24) { 0.0 } },
            outputBias = values,
        )
}
