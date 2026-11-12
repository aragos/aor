package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same sampling, scope and mass fixtures execute on both JVM and JS. */
class PurchaseSupportedPolicyTest {
    private val owned =
        AdvanceCategory.entries
            .filter { it != AdvanceCategory.COMMUNICATION }
            .map { category ->
                AdvanceId.entries.first { AdvanceData.definition(it).category == category }
            }
    private val observation =
        PurchaseObservation(
                stateFeatures =
                    mapOf(
                        "decision.purchase-advances" to 1.0,
                        "reliefAvailable" to 0.2,
                        "role.0.misery" to 0.05,
                    ) + owned.associate { "role.0.advance.${it.name}" to 1.0 },
                actions =
                    (AdvanceId.entries
                            .filter { it !in owned }
                            .take(12)
                            .map { "buy-advance ${it.letter}" } + "buy-advance N" + "pass")
                        .distinct()
                        .sorted()
                        .mapIndexed { index, command ->
                            PurchaseAction(command, mapOf("slot" to index / 10.0))
                        },
                baselineIndex = 12,
            )
            .let {
                it.copy(
                    baselineIndex = it.actions.indexOfFirst { action -> action.command == "pass" }
                )
            }

    private fun model(): PurchasePolicyModel =
        PurchasePolicyModel(
            stateFeatureNames = observation.stateFeatures.keys.sorted(),
            actionFeatureNames = listOf("slot"),
            stateWeights = List(64 * observation.stateFeatures.size) { 0.0 },
            stateBias = List(64) { 0.01 },
            actionWeights = List(64) { if (it == 0) 0.3 else 0.0 },
            actionBias = List(64) { 0.0 },
            actorWeights = List(64) { if (it == 0) 0.2 else 0.0 },
            valueWeights = List(64) { 0.01 },
            valueBias = 0.1,
        )

    private fun policy(): PurchaseSupportedPolicy {
        val original = model()
        return PurchaseSupportedPolicy(
            original,
            original.copy(actorWeights = original.actorWeights.map { it + 0.5 }),
        )
    }

    @Test
    fun supportedSetContainsPassAndCanonicalCompletionBeforeOtherAdvances() {
        val support = assertNotNull(PurchaseSupport.from(observation))

        assertEquals("pass", support.commands.first())
        assertEquals("buy-advance N", support.commands[1])
        assertEquals(5, support.commands.size)
        assertTrue(
            support.commands.all { command -> observation.actions.any { it.command == command } }
        )
    }

    @Test
    fun zeroReliefLeavesThePublicGateClosed() {
        val input = observation.copy(stateFeatures = observation.stateFeatures - "reliefAvailable")

        assertNull(PurchaseSupport.from(input))
    }

    @Test
    fun chaosLeavesThePublicGateClosed() {
        val input =
            observation.copy(stateFeatures = observation.stateFeatures + ("role.0.chaos" to 1.0))

        assertNull(PurchaseSupport.from(input))
    }

    @Test
    fun noMissingCategoryLeavesThePublicGateClosed() {
        val input =
            observation.copy(
                stateFeatures = observation.stateFeatures + ("role.0.advance.WRITTEN_RECORD" to 1.0)
            )

        assertNull(PurchaseSupport.from(input))
    }

    @Test
    fun identicalActorRetainsEveryOriginalEvaluationBit() {
        val model = model()
        val result = PurchaseSupportedPolicy(model, model).evaluate(observation)

        assertEquals(PurchasePolicy.evaluate(observation, model), result)
    }

    @Test
    fun gateOffRetainsEveryOriginalEvaluationBit() {
        val input = observation.copy(stateFeatures = observation.stateFeatures - "reliefAvailable")
        val policy = policy()

        assertEquals(PurchasePolicy.evaluate(input, policy.reference), policy.evaluate(input))
    }

    @Test
    fun nonHardDifficultyRetainsTheReference() {
        val policy = policy()

        assertEquals(
            PurchasePolicy.evaluate(observation, policy.reference),
            policy.evaluate(observation, BotDifficulty.NORMAL),
        )
    }

    @Test
    fun unmeasuredOffersRetainTheirExactProbability() {
        val policy = policy()
        val original = PurchasePolicy.evaluate(observation, policy.reference)
        val actual = policy.evaluate(observation)
        val support = assertNotNull(PurchaseSupport.from(observation))

        observation.actions.forEachIndexed { index, action ->
            if (action.command !in support.commands) {
                assertEquals(original.probabilities[index], actual.probabilities[index])
            }
        }
    }

    @Test
    fun supportMassAndReferenceValueArePreserved() {
        val policy = policy()
        val original = PurchasePolicy.evaluate(observation, policy.reference)
        val actual = policy.evaluate(observation)
        val support = assertNotNull(PurchaseSupport.from(observation))
        val indices =
            observation.actions.indices.filter {
                observation.actions[it].command in support.commands
            }

        assertTrue(
            abs(
                indices.sumOf { actual.probabilities[it] } -
                    indices.sumOf { original.probabilities[it] }
            ) < 1e-14
        )
        assertEquals(original.value, actual.value)
        assertTrue(abs(actual.probabilities.sum() - 1.0) < 1e-14)
    }

    @Test
    fun maximumRedistributionIsBoundedByTheDeclaredMixture() {
        val original = model()
        val candidate = original.copy(actorWeights = List(64) { -1000.0 })
        val reference = PurchasePolicy.evaluate(observation, original)
        val actual = PurchaseSupportedPolicy(original, candidate).evaluate(observation)
        val variation =
            actual.probabilities.zip(reference.probabilities).sumOf { (a, b) -> abs(a - b) } / 2.0

        assertTrue(variation <= PurchaseSupportedPolicy.ALPHA + 1e-14)
        assertTrue(actual.probabilities.all { it > 0.0 })
    }

    @Test
    fun sampledChoiceUsesTheSameCommonSeedAndReportedDistribution() {
        val policy = policy()
        val evaluation = policy.evaluate(observation)

        listOf(0, 1, -1, 519, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            val actual = policy.choose(observation, seed)
            val expected =
                PurchasePolicy.select(observation, evaluation, PurchasePolicy.uniform(seed))
            assertEquals(expected, actual)
            assertEquals(ln(evaluation.probabilities[actual.index]), actual.logProbability)
        }
    }

    @Test
    fun alteredBasisIsRejectedBeforeInference() {
        val original = model()

        assertFailsWith<IllegalArgumentException> {
            PurchaseSupportedPolicy(original, original.copy(stateBias = List(64) { 0.2 }))
        }
    }

    @Test
    fun alteredValueHeadIsRejectedBeforeInference() {
        val original = model()

        assertFailsWith<IllegalArgumentException> {
            PurchaseSupportedPolicy(original, original.copy(valueBias = 0.2))
        }
    }
}
