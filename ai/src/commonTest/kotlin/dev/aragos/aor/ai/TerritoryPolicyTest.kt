package dev.aragos.aor.ai

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The same numerical and signed-seed fixtures execute on JVM and JavaScript. */
class TerritoryPolicyTest {
    @Test
    fun explicitExpansionTwoPreservesTheSameNumericalAndSeedMath() {
        val original = model(TerritoryPhase.EXPANSION)
        val state = observation(TerritoryPhase.EXPANSION)
        val current =
            original.copy(schemaVersion = 2, encoderVersion = TerritoryEncoder.EXPANSION_2.version)
        val projected = state.copy(encoderVersion = TerritoryEncoder.EXPANSION_2.version)

        assertEquals(
            TerritoryPolicy.evaluate(state, original),
            TerritoryPolicy.evaluate(projected, current),
        )
        assertEquals(
            TerritoryPolicy.choose(state, original, -179),
            TerritoryPolicy.choose(projected, current, -179),
        )
    }

    @Test
    fun schemaTwoCannotRelabelExpansionOne() {
        assertFailsWith<IllegalArgumentException> {
            model(TerritoryPhase.EXPANSION).copy(schemaVersion = 2)
        }
    }

    @Test
    fun schemaTwoCannotRelabelTokenInputs() {
        assertFailsWith<IllegalArgumentException> {
            model(TerritoryPhase.TOKEN).copy(schemaVersion = 2)
        }
    }

    @Test
    fun expansionTwoCannotUseAnUndeclaredSchema() {
        assertFailsWith<IllegalArgumentException> {
            model(TerritoryPhase.EXPANSION)
                .copy(schemaVersion = 3, encoderVersion = TerritoryEncoder.EXPANSION_2.version)
        }
    }

    @Test
    fun expansionTwoDoesNotSilentlyChangeTheExistingPpoContract() {
        assertFailsWith<IllegalArgumentException> {
            model(TerritoryPhase.EXPANSION)
                .copy(encoderVersion = TerritoryEncoder.EXPANSION_2.version)
        }
    }

    @Test
    fun zeroActorKeepsTheExactPriorForBothTypedPhases() {
        for (phase in TerritoryPhase.entries) {
            val result =
                TerritoryPolicy.evaluate(
                    observation(phase),
                    model(phase).copy(actorWeights = List(64) { 0.0 }),
                )
            assertEquals(listOf(.97, .03), result.probabilities)
            assertEquals(
                "right",
                TerritoryPolicy.choose(
                        observation(phase),
                        model(phase).copy(actorWeights = List(64) { 0.0 }),
                        .97,
                    )
                    .command,
            )
        }
    }

    @Test
    fun bothTypedNetworksMatchTheIndependentNonzeroOracle() {
        for (phase in TerritoryPhase.entries) {
            val result = TerritoryPolicy.evaluate(observation(phase), model(phase))
            assertEquals(.740327573777746, result.logits[0], 1e-12)
            assertEquals(-3.3137038836953616, result.logits[1], 1e-12)
            assertEquals(.9829436874186683, result.probabilities[0], 1e-12)
            assertEquals(.5174163824291889, result.value, 1e-12)
            val selected = TerritoryPolicy.choose(observation(phase), model(phase), .999999)
            assertEquals("right", selected.command)
            assertEquals(result.probabilities[1], exp(selected.logProbability), 1e-12)
        }
    }

    @Test
    fun phaseVersionsAndUnknownFeaturesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TerritoryPolicy.evaluate(
                observation(TerritoryPhase.TOKEN),
                model(TerritoryPhase.EXPANSION),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            model(TerritoryPhase.TOKEN)
                .copy(encoderVersion = PurchaseObservationEncoder.ENCODER_VERSION)
        }
        assertFailsWith<IllegalStateException> {
            TerritoryPolicy.evaluate(
                observation(TerritoryPhase.TOKEN).copy(stateFeatures = mapOf("unknown" to 0.0)),
                model(TerritoryPhase.TOKEN),
            )
        }
    }

    @Test
    fun phaseActionBoundsRejectOverflowWithoutTruncation() {
        for (phase in TerritoryPhase.entries) {
            val actions =
                (0..phase.maxActions).map {
                    TerritoryAction("action ${it.toString().padStart(4, '0')}", emptyMap())
                }
            assertEquals(
                phase.maxActions,
                observation(phase).copy(actions = actions.dropLast(1)).actions.size,
            )
            assertFailsWith<IllegalArgumentException> { observation(phase).copy(actions = actions) }
        }
    }

    @Test
    fun signedMixerGoldensAndDistinctTypedSaltsAreStable() {
        val goldens =
            mapOf(
                0 to 0.0,
                1 to .3175988623406738,
                2 to .19123476883396506,
                17 to .8469332996755838,
                -1 to .5075902475509793,
                Int.MIN_VALUE to .4267028346657753,
                Int.MAX_VALUE to .975769916549325,
            )
        goldens.forEach { (seed, expected) ->
            assertEquals(expected, TerritoryPolicy.uniform(seed))
        }
        assertEquals(0x510e527f, TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, 0))
        assertEquals(0x9b05688c.toInt(), TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, 0))
        assertNotEquals(
            TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, -1),
            TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, -1),
        )
    }

    private fun observation(phase: TerritoryPhase) =
        TerritoryObservation(
            phase.encoderVersion,
            mapOf("root" to 1.0),
            listOf(
                TerritoryAction("left", mapOf("left" to 1.0)),
                TerritoryAction("right", mapOf("right" to 1.0)),
            ),
            0,
        )

    private fun model(phase: TerritoryPhase) =
        TerritoryPolicyModel(
            encoderVersion = phase.encoderVersion,
            stateFeatureNames = listOf("after-left", "after-right", "root"),
            actionFeatureNames = listOf("cost", "left", "right"),
            stateWeights = List(192) { if (it == 2) .3 else 0.0 },
            stateBias = List(64) { if (it == 0) .2 else 0.0 },
            actionWeights =
                List(192) {
                    when (it) {
                        0 -> .1
                        1 -> .4
                        2 -> -.2
                        else -> 0.0
                    }
                },
            actionBias = List(64) { if (it == 0) -.1 else 0.0 },
            actorWeights = List(64) { if (it == 0) 1.2 else 0.0 },
            valueWeights = List(64) { if (it == 0) .8 else 0.0 },
            valueBias = -.3,
        )
}
