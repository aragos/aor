package dev.aragos.aor.ai

import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Identical scalar, mixture and signed-seed fixtures run on JVM and JavaScript. */
class TerritoryValuePolicyTest {
    @Test
    fun constantInitialValuesUseFForTiesAndExplicitExploration() {
        for (phase in TerritoryPhase.entries) {
            val observation = observation(phase)
            val initial = model(phase).copy(outputWeights = List(64) { 0.0 })
            val exploratory = TerritoryValuePolicy.evaluate(observation, initial, .2)
            assertEquals(listOf(.5, .5, .5), exploratory.values)
            assertEquals(1, exploratory.greedyIndex)
            assertEquals(listOf(.2 / 3, .2 / 3 + .8, .2 / 3), exploratory.probabilities)
            assertEquals(
                listOf(0.0, 1.0, 0.0),
                TerritoryValuePolicy.evaluate(observation, initial, 0.0).probabilities,
            )
            assertEquals("a", TerritoryValuePolicy.select(observation, exploratory, 0.0).command)
            assertEquals(
                "c",
                TerritoryValuePolicy.select(observation, exploratory, .999999).command,
            )
        }
    }

    @Test
    fun allHiddenUnitsMatchIndependentLinearOracleWithoutPriorOrSigmoid() {
        for (phase in TerritoryPhase.entries) {
            val model = model(phase)
            val observation = observation(phase)
            val actual = TerritoryValuePolicy.evaluate(observation, model, .2)
            val expected =
                observation.actions.map { action ->
                    var result = model.outputBias
                    for (row in 0 until 64) {
                        var state = model.stateBias[row]
                        for (column in model.stateFeatureNames.indices) {
                            state +=
                                model.stateWeights[row * 2 + column] *
                                    (observation.stateFeatures[model.stateFeatureNames[column]]
                                        ?: 0.0)
                        }
                        var local = tanh(state) + model.actionBias[row]
                        for (column in model.actionFeatureNames.indices) {
                            local +=
                                model.actionWeights[row * 2 + column] *
                                    (action.features[model.actionFeatureNames[column]] ?: 0.0)
                        }
                        result += model.outputWeights[row] * tanh(local)
                    }
                    result
                }
            actual.values.zip(expected).forEach { (value, oracle) ->
                assertEquals(oracle, value, 1e-12)
            }
            val pythonValues = listOf(.45808659327777873, .5752613525052769, .4779604578324139)
            actual.values.zip(pythonValues).forEach { (value, oracle) ->
                assertEquals(oracle, value, 1e-12)
            }
            assertEquals(1, actual.greedyIndex)
            val below =
                TerritoryValuePolicy.evaluate(
                    observation,
                    model.copy(outputWeights = List(64) { 0.0 }, outputBias = -.7),
                    0.0,
                )
            assertEquals(listOf(-.7, -.7, -.7), below.values)
            val above =
                TerritoryValuePolicy.evaluate(
                    observation,
                    model.copy(outputWeights = List(64) { 0.0 }, outputBias = 1.2),
                    0.0,
                )
            assertEquals(listOf(1.2, 1.2, 1.2), above.values)
        }
    }

    @Test
    fun equalNonbaselineMaximaUseCanonicalCommand() {
        val phase = TerritoryPhase.TOKEN
        val input =
            observation(phase)
                .copy(
                    actions =
                        listOf(
                            TerritoryAction("a", mapOf("x" to 1.0)),
                            TerritoryAction("b", mapOf("x" to -1.0)),
                            TerritoryAction("c", mapOf("x" to 1.0)),
                        )
                )
        val weights =
            model(phase)
                .copy(
                    stateWeights = List(128) { 0.0 },
                    stateBias = List(64) { 0.0 },
                    actionWeights = List(128) { if (it == 0) 1.0 else 0.0 },
                    actionBias = List(64) { 0.0 },
                    outputWeights = List(64) { if (it == 0) 1.0 else 0.0 },
                )
        assertEquals(0, TerritoryValuePolicy.evaluate(input, weights, 0.0).greedyIndex)
    }

    @Test
    fun singletonHasProbabilityOneAndRetainsItsActualValue() {
        val phase = TerritoryPhase.EXPANSION
        val input =
            observation(phase)
                .copy(actions = listOf(TerritoryAction("pass", emptyMap())), baselineIndex = 0)
        val result = TerritoryValuePolicy.evaluate(input, model(phase), .2)
        assertEquals(listOf(1.0), result.probabilities)
        val chosen = TerritoryValuePolicy.select(input, result, .999999)
        assertEquals("pass", chosen.command)
        assertEquals(result.values.single(), chosen.selectedQ)
    }

    @Test
    fun wrongArchitecturesPhasesFeaturesAndEpsilonAreRejected() {
        val model = model(TerritoryPhase.TOKEN)
        val input = observation(TerritoryPhase.TOKEN)
        assertFailsWith<IllegalArgumentException> { model.copy(architecture = "ppo") }
        assertFailsWith<IllegalArgumentException> { model.copy(schemaVersion = 2) }
        assertFailsWith<IllegalArgumentException> { model.copy(outputWeights = listOf(.5)) }
        assertFailsWith<IllegalArgumentException> {
            TerritoryValuePolicy.evaluate(input, model(TerritoryPhase.EXPANSION), .2)
        }
        assertFailsWith<IllegalArgumentException> {
            TerritoryValuePolicy.evaluate(
                input.copy(stateFeatures = mapOf("unknown" to 1.0)),
                model,
                .2,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TerritoryValuePolicy.evaluate(input, model, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            TerritoryValuePolicy.evaluate(input, model, -.1)
        }
    }

    @Test
    fun signedMixerAndDistinctPhaseSelectorSaltsAreStable() {
        assertEquals(0x243f6a88, TerritoryValuePolicy.selectorSeed(TerritoryPhase.TOKEN, 0))
        assertEquals(
            0x85a308d3.toInt(),
            TerritoryValuePolicy.selectorSeed(TerritoryPhase.EXPANSION, 0),
        )
        val seeds = listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE)
        val uniforms =
            listOf(
                0.0,
                .3175988623406738,
                .19123476883396506,
                .8469332996755838,
                .5075902475509793,
                .4267028346657753,
                .975769916549325,
            )
        seeds.zip(uniforms).forEach { (seed, expected) ->
            assertEquals(expected, TerritoryValuePolicy.uniform(seed))
        }
        for (phase in TerritoryPhase.entries) {
            assertTrue(
                seeds.all {
                    TerritoryValuePolicy.selectorSeed(phase, it) !=
                        TerritoryPolicy.actorSeed(phase, it)
                }
            )
            val expected =
                if (phase == TerritoryPhase.TOKEN) {
                    listOf(1, 2, 1, 2, 1, 1, 1, 1)
                } else {
                    listOf(1, 1, 1, 1, 1, 1, 2, 1)
                }
            val selected =
                (seeds + -979738860).map { botSeed ->
                    TerritoryValuePolicy.choose(
                            observation(phase),
                            model(phase),
                            .2,
                            TerritoryValuePolicy.selectorSeed(phase, botSeed),
                        )
                        .index
                }
            assertEquals(expected, selected)
        }
    }

    private fun observation(phase: TerritoryPhase) =
        TerritoryObservation(
            phase.encoderVersion,
            mapOf("s1" to -.75, "s0" to .25),
            listOf(
                TerritoryAction("a", mapOf("x" to 1.0)),
                TerritoryAction("b", mapOf("y" to 2.0)),
                TerritoryAction("c", mapOf("y" to -.5, "x" to .75)),
            ),
            1,
        )

    private fun model(phase: TerritoryPhase) =
        TerritoryValueModel(
            encoderVersion = phase.encoderVersion,
            stateFeatureNames = listOf("s0", "s1"),
            actionFeatureNames = listOf("x", "y"),
            stateWeights = List(128) { (it % 11 - 5) * .017 },
            stateBias = List(64) { (it % 7 - 3) * .03 },
            actionWeights = List(128) { (it % 13 - 6) * .021 },
            actionBias = List(64) { (it % 5 - 2) * .04 },
            outputWeights = List(64) { (it % 9 - 4) * .11 },
            outputBias = .5,
        )
}
