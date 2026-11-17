package dev.aragos.aor.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NearOptimalTokenPolicyTest {
    private val observation =
        TerritoryObservation(
            TerritoryEncoder.TOKEN_2.version,
            mapOf("state" to 1.0),
            listOf(12, 14, 15, 16, 17, 30).map {
                TerritoryAction("buy-tokens $it", mapOf("amount" to it / 36.0))
            },
            2,
        )
    private val deterministic =
        IncumbentResidualChoice(
            List(6) { 0.0 },
            listOf(-10.0, .5, 1.0, .75, .25, .9),
            2,
            "buy-tokens 15",
        )
    private val sampler = NearOptimalTokenSampler(.7, 1.0, 2)

    @Test
    fun excludesPoorScoresAndDistantAmounts() {
        val choice = NearOptimalTokenPolicy.select(observation, deterministic, sampler, .5)
        assertEquals(0.0, choice.probabilities[0])
        assertEquals(0.0, choice.probabilities[5])
        assertTrue(choice.probabilities.slice(1..4).all { it > 0.0 })
        assertEquals(1.0, choice.probabilities.sum(), 1e-12)
    }

    @Test
    fun centersSupportOnActualWinnerRatherThanOriginalReference() {
        val winner =
            deterministic.copy(
                scores = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 2.0),
                index = 5,
                command = "buy-tokens 30",
            )
        val choice = NearOptimalTokenPolicy.select(observation, winner, sampler, .5)
        assertEquals("buy-tokens 30", choice.command)
        assertEquals(1.0, choice.probabilities[5])
    }

    @Test
    fun samplesDifferentNearOptimalCommands() {
        assertEquals(
            "buy-tokens 14",
            NearOptimalTokenPolicy.select(observation, deterministic, sampler, 0.0).command,
        )
        assertEquals(
            "buy-tokens 17",
            NearOptimalTokenPolicy.select(observation, deterministic, sampler, .9999999999999999)
                .command,
        )
    }

    @Test
    fun seedOwnsRepeatableSingleDraw() {
        assertEquals(
            NearOptimalTokenPolicy.choose(observation, deterministic, sampler, 91),
            NearOptimalTokenPolicy.choose(observation, deterministic, sampler, 91),
        )
    }

    @Test
    fun excludedLastOfferCannotBeSelectedByRounding() {
        val choice =
            NearOptimalTokenPolicy.select(
                observation,
                deterministic,
                sampler,
                .9999999999999999,
            )
        assertTrue(choice.probabilities[choice.index] > 0.0)
    }

    @Test
    fun rejectsNonTokenObservations() {
        assertFailsWith<IllegalArgumentException> {
            NearOptimalTokenPolicy.select(
                observation.copy(encoderVersion = TerritoryEncoder.EXPANSION_2.version),
                deterministic,
                sampler,
                .5,
            )
        }
    }
}
