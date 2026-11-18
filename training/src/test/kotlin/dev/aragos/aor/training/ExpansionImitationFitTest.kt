package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionImitationFitTest {
    @Test
    fun analyticalGradientMatchesCentralFiniteDifference() {
        val coefficients = List(8) { (it - 3.0) / 10.0 }
        val result = ExpansionImitationOptimizer.objective(contrasts(), coefficients, 0.1)

        val numerical =
            coefficients.indices.map { index ->
                val positive = coefficients.mapIndexed { other, value ->
                    value + if (other == index) 1e-5 else 0.0
                }
                val negative = coefficients.mapIndexed { other, value ->
                    value - if (other == index) 1e-5 else 0.0
                }
                (ExpansionImitationOptimizer.objective(contrasts(), positive, 0.1).loss -
                    ExpansionImitationOptimizer.objective(contrasts(), negative, 0.1).loss) / 2e-5
            }

        result.gradient.indices.forEach {
            assertThat(result.gradient[it]).isWithin(1e-9).of(numerical[it])
        }
    }

    @Test
    fun analyticalHessianMatchesGradientFiniteDifference() {
        val coefficients = List(8) { (it - 3.0) / 10.0 }
        val result = ExpansionImitationOptimizer.objective(contrasts(), coefficients, 1.0)

        val numerical =
            coefficients.indices.map { column ->
                val positive = coefficients.mapIndexed { index, value ->
                    value + if (index == column) 1e-5 else 0.0
                }
                val negative = coefficients.mapIndexed { index, value ->
                    value - if (index == column) 1e-5 else 0.0
                }
                val high =
                    ExpansionImitationOptimizer.objective(contrasts(), positive, 1.0).gradient
                val low = ExpansionImitationOptimizer.objective(contrasts(), negative, 1.0).gradient
                high.indices.map { (high[it] - low[it]) / 2e-5 }
            }

        result.hessian.indices.forEach { row ->
            result.hessian.indices.forEach { column ->
                assertThat(result.hessian[row][column]).isWithin(1e-9).of(numerical[column][row])
            }
        }
    }

    @Test
    fun convergesToFiniteRegularizedStationaryPoint() {
        val fitted = ExpansionImitationOptimizer.fit(contrasts(), 0.1, Long.MAX_VALUE)

        assertThat(fitted.maximumGradient).isLessThan(1e-8)
        assertThat(fitted.iterations).isLessThan(100)
        assertThat(fitted.objective)
            .isLessThan(
                ExpansionImitationOptimizer.objective(contrasts(), List(8) { 0.0 }, 0.1).loss
            )
    }

    @Test
    fun largeMarginsRemainFinite() {
        val examples =
            contrasts().mapIndexed { index, value ->
                value.copy(offset = if (index == 0) 10000.0 else -10000.0)
            }

        val objective = ExpansionImitationOptimizer.objective(examples, List(8) { 0.0 }, 1.0)

        assertThat(objective.loss.isFinite()).isTrue()
        assertThat(objective.gradient.all(Double::isFinite)).isTrue()
    }

    @Test
    fun exportedRawScoresRetainNormalizedPairwiseMargins() {
        val baseline =
            BotModel(weights = mapOf("income" to 3.0, "tokens" to 2.0, "untouched" to 7.0))
        val examples = listOf(example(1), example(2, 4))
        val scale = ExpansionImitationScale.from(examples, baseline)
        val contrasts = ExpansionImitationOptimizer.contrasts(examples, baseline, scale)
        val fitted = ExpansionImitationOptimizer.fit(contrasts, 0.1, Long.MAX_VALUE)
        val directory = Files.createTempDirectory("imitation-score-test")
        try {
            TrainingArtifacts(directory)
                .model(ExpansionImitationOptimizer.model(baseline, scale, fitted))
            val exported = TrainingArtifacts.readModel(directory.resolve("model.json"))

            val actual = examples.flatMap { example ->
                example.alternatives.map {
                    (BotPolicy.score(example.selected, exported) - BotPolicy.score(it, exported)) /
                        scale.temperature
                }
            }

            actual.indices.forEach { index ->
                val pair = contrasts[index]
                val expected =
                    pair.offset +
                        pair.features.indices.sumOf { pair.features[it] * fitted.coefficients[it] }
                assertThat(actual[index]).isWithin(1e-12).of(expected)
            }
            assertThat(exported.weights["untouched"]).isEqualTo(7.0)
            assertThat(ExpansionImitationProtocol.preservesOtherWeights(baseline, exported))
                .isTrue()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun offeredActionCountDoesNotChangeDecisionWeight() {
        val baseline = BotModel(weights = emptyMap())
        val first = listOf(example(1), example(2))
        val duplicated = listOf(example(1), example(2, 12))
        val scale = ExpansionImitationScale.from(first, baseline)

        val fits =
            listOf(first, duplicated).map {
                ExpansionImitationOptimizer.fit(
                    ExpansionImitationOptimizer.contrasts(it, baseline, scale),
                    1.0,
                    Long.MAX_VALUE,
                )
            }

        fits[0].coefficients.indices.forEach {
            assertThat(fits[0].coefficients[it]).isWithin(1e-12).of(fits[1].coefficients[it])
        }
    }

    @Test
    fun strongerRegularizationKeepsDeltaCloserToFrozenBaseline() {
        val fits =
            listOf(0.1, 10.0).map {
                ExpansionImitationOptimizer.fit(contrasts(), it, Long.MAX_VALUE)
            }

        assertThat(fits[1].coefficients.sumOf { it * it })
            .isLessThan(fits[0].coefficients.sumOf { it * it })
    }

    @Test
    fun expiredDeadlineStopsFitting() {
        assertFailsWith<CancellationException> {
            ExpansionImitationOptimizer.fit(contrasts(), 1.0, System.nanoTime() - 1)
        }
    }

    @Test
    fun arbitraryRegularizationIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ExpansionImitationOptimizer.fit(contrasts(), 0.2, Long.MAX_VALUE)
        }
    }

    @Test
    fun selectionRequiresImprovementAndDistinctValidationBehavior() {
        val baseline = metrics(2, 0, listOf("a", "b"))
        val candidates =
            listOf(
                candidate(0.1, metrics(4, 3, listOf("c", "d"))),
                candidate(1.0, metrics(4, 3, listOf("c", "d"))),
                candidate(10.0, metrics(2, 3, listOf("e", "f"))),
            )

        val selected = ExpansionImitationMain.select(candidates, baseline)

        assertThat(selected).containsExactly("lambda-1.0.json")
    }

    private fun metrics(correct: Int, changed: Int, commands: List<String>) =
        ExpansionImitationMetrics(10, correct, changed, 1.0, commands)

    private fun candidate(lambda: Double, metrics: ExpansionImitationMetrics) =
        ExpansionImitationCandidate(
            lambda,
            "lambda-$lambda.json",
            "test",
            ExpansionImitationFit(List(8) { 0.0 }, 0, 0.0, 0.0),
            metrics,
            metrics,
        )

    private fun contrasts() =
        listOf(
            ExpansionImitationContrast(List(8) { (it + 1.0) / 8.0 }, -0.7, 0.5),
            ExpansionImitationContrast(List(8) { if (it % 2 == 0) 0.4 else -0.2 }, 1.3, 0.5),
        )

    private fun example(entry: Int, alternatives: Int = 1): ExpansionImitationExample {
        val chosen =
            BotCandidate(
                "place a 2",
                mapOf("income" to 8.0, "tokens" to -2.0),
                family = "expansion",
            )
        val others =
            List(alternatives) { index ->
                BotCandidate(
                    "pass-$index",
                    mapOf("income" to 1.0, "tokens" to -8.0),
                    family = "pass",
                )
            }
        return ExpansionImitationExample(
            entry,
            BotObservation(
                playerId = "p1",
                decision = "expand",
                context = emptyMap(),
                availableCash = 100,
                availableTokens = 16,
                candidates = listOf(chosen) + others,
                rulesVersion = RULES_VERSION,
                dataVersion = DATA_VERSION,
            ),
            chosen.command,
        )
    }
}
