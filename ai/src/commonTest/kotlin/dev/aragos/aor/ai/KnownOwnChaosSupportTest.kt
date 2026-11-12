package dev.aragos.aor.ai

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KnownOwnChaosSupportTest {
    private fun observation(vararg features: Map<String, Double>): CardObservation =
        CardObservation(
            stateFeatures = emptyMap(),
            actions = features.mapIndexed { i, f -> CardAction("play card-$i", f) },
            baselineIndex = 0,
        )

    private fun evaluation(vararg p: Double): CardPolicyEvaluation =
        CardPolicyEvaluation(p.map { 0.0 }, p.toList(), 0.4)

    private val chaos = mapOf("effect.role.0.chaos" to 1.0)

    @Test
    fun conditionsOriginalMassAndReportsConditionalLogProbability() {
        val result =
            KnownOwnChaosSupport.card(
                observation(emptyMap(), chaos, emptyMap()),
                evaluation(0.1, 0.6, 0.3),
                0.3,
            )

        assertEquals(listOf(1), result.excludedIndices)
        assertEquals(2, result.index)
        assertEquals(0.25, result.probabilities[0], 1e-15)
        assertEquals(0.0, result.probabilities[1])
        assertEquals(0.75, result.probabilities[2], 1e-15)
        assertEquals(ln(result.probabilities[2]), result.logProbability)
        assertEquals(0.4, result.value)
    }

    @Test
    fun zeroDrawCannotChooseExcludedFirstAction() {
        val result =
            KnownOwnChaosSupport.card(observation(chaos, emptyMap()), evaluation(.9, .1), 0.0)

        assertEquals(1, result.index)
        assertEquals(0.0, result.logProbability)
    }

    @Test
    fun roundingFallbackIsLastEligibleNotLastOffered() {
        val result =
            KnownOwnChaosSupport.card(
                observation(emptyMap(), emptyMap(), chaos),
                evaluation(.1, .2, .7),
                0.9999999999999999,
            )

        assertEquals(1, result.index)
    }

    @Test
    fun allKnownZeroChoicesPreserveExactOriginalSampleAndLogProbability() {
        val observation = observation(chaos, chaos)
        val evaluation = evaluation(.3, .7)
        val old = CardPolicy.select(observation, evaluation, .4)
        val result = KnownOwnChaosSupport.card(observation, evaluation, .4)

        assertEquals(old.index, result.index)
        assertEquals(old.logProbability.toBits(), result.logProbability.toBits())
        assertEquals(evaluation.probabilities, result.probabilities)
        assertEquals(emptyList(), result.excludedIndices)
    }

    @Test
    fun noFilterRetainsExactLegacyChoiceForEverySeed() {
        val observation = observation(emptyMap(), mapOf("effect.role.1.chaos" to 1.0))
        val evaluation = evaluation(.3, .7)
        for (seed in 0..255) {
            val u = CardPolicy.uniform(CardPolicy.actorSeed(seed))
            val old = CardPolicy.select(observation, evaluation, u)
            val result = KnownOwnChaosSupport.card(observation, evaluation, u)
            assertEquals(old.index, result.index)
            assertEquals(old.logProbability.toBits(), result.logProbability.toBits())
            assertEquals(evaluation.probabilities, result.probabilities)
        }
    }

    @Test
    fun uncertainOwnConsequenceIsRetainedEvenAlongsideChaosFlag() {
        val result =
            KnownOwnChaosSupport.card(
                observation(chaos + ("effect.role.0.miseryUncertain" to 1.0), emptyMap()),
                evaluation(.9, .1),
                0.1,
            )

        assertEquals(0, result.index)
        assertEquals(emptyList(), result.excludedIndices)
    }

    @Test
    fun purchaseConditionsOnlyTheAuthoritativeOwnChaosFlag() {
        val observation =
            PurchaseObservation(
                stateFeatures = emptyMap(),
                actions =
                    listOf(
                        PurchaseAction("buy-advance E", mapOf("chaos" to 1.0)),
                        PurchaseAction("pass", emptyMap()),
                    ),
                baselineIndex = 0,
            )
        val result =
            KnownOwnChaosSupport.purchase(
                observation,
                PurchasePolicyEvaluation(listOf(0.0, 0.0), listOf(.9, .1), .2),
                0.0,
            )

        assertEquals("pass", result.command)
        assertEquals(listOf(0.0, 1.0), result.probabilities)
        assertEquals(0.0, result.logProbability)
    }

    @Test
    fun purchaseWithoutFilteringPreservesLegacyBits() {
        val observation =
            PurchaseObservation(
                stateFeatures = emptyMap(),
                actions =
                    listOf(
                        PurchaseAction("buy-advance A", emptyMap()),
                        PurchaseAction("pass", emptyMap()),
                    ),
                baselineIndex = 0,
            )
        val evaluation = PurchasePolicyEvaluation(listOf(0.0, 0.0), listOf(.97, .03), .2)
        val old = PurchasePolicy.select(observation, evaluation, .99)
        val result = KnownOwnChaosSupport.purchase(observation, evaluation, .99)

        assertEquals(old.index, result.index)
        assertEquals(old.logProbability.toBits(), result.logProbability.toBits())
    }

    @Test
    fun invalidUniformIsRejectedBeforeConditionalSampling() {
        assertFailsWith<IllegalArgumentException> {
            KnownOwnChaosSupport.card(observation(chaos, emptyMap()), evaluation(.9, .1), 1.0)
        }
    }
}
