package dev.aragos.aor.ai

import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The same explicit version-2 linear-Q fixture runs on JVM and JavaScript. */
class ExpansionValueVersionTest {
    @Test
    fun publicUsageChangesValuesUsingTheUnchangedLinearNetwork() {
        val model = model()
        val unused = observation(false)
        val used = observation(true)
        for ((input, bit) in listOf(unused to 0.0, used to 1.0)) {
            val actual = TerritoryValuePolicy.evaluate(input, model, .2)
            val hidden = tanh(.25 + .7 * bit)
            val expected = listOf(.5 + 1.2 * tanh(hidden + .3), .5 + 1.2 * tanh(hidden - .5))
            actual.values.zip(expected).forEach { (value, oracle) ->
                assertEquals(oracle, value, 1e-14)
            }
            assertEquals(0, actual.greedyIndex)
            assertEquals(listOf(.9, .1), actual.probabilities)
        }
        assertNotEquals(
            TerritoryValuePolicy.evaluate(unused, model, .2).values,
            TerritoryValuePolicy.evaluate(used, model, .2).values,
        )
    }

    @Test
    fun olderObservationsCannotSupplyMissingVersionTwoContext() {
        val model = model()
        val old = observation(false).copy(encoderVersion = TerritoryEncoder.EXPANSION_1.version)
        assertFailsWith<IllegalArgumentException> { TerritoryValuePolicy.evaluate(old, model, .2) }
    }

    @Test
    fun incompletePublicPairDictionaryIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(stateFeatureNames = listOf("context"), stateWeights = List(64) { 0.0 })
        }
    }

    private fun observation(used: Boolean) =
        TerritoryObservation(
            TerritoryEncoder.EXPANSION_2.version,
            if (used) mapOf("context" to .25, "cathedral.used.role.0.role.1" to 1.0)
            else mapOf("context" to .25),
            listOf(
                TerritoryAction("attack a", mapOf("x" to 1.0)),
                TerritoryAction("pass", mapOf("x" to -1.0)),
            ),
            1,
        )

    private fun model(): TerritoryValueModel {
        val names = (ExpansionObservationEncoder.cathedralUsageNames + "context").sorted()
        return TerritoryValueModel(
            encoderVersion = TerritoryEncoder.EXPANSION_2.version,
            stateFeatureNames = names,
            actionFeatureNames = listOf("x"),
            stateWeights =
                List(64 * names.size) { index ->
                    when (index) {
                        names.indexOf("cathedral.used.role.0.role.1") -> .7
                        names.indexOf("context") -> .2
                        else -> 0.0
                    }
                },
            stateBias = List(64) { if (it == 0) .2 else 0.0 },
            actionWeights = List(64) { if (it == 0) .4 else 0.0 },
            actionBias = List(64) { if (it == 0) -.1 else 0.0 },
            outputWeights = List(64) { if (it == 0) 1.2 else 0.0 },
            outputBias = .5,
        )
    }
}
