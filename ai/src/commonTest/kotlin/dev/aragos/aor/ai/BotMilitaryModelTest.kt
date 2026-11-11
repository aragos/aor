package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shared JVM/JS policy tests: the opt-in replaces four weights only for Hard play. */
class BotMilitaryModelTest {
    private val incumbent =
        BotModel(
            weights =
                mapOf("points" to 1.0) +
                    BotMilitaryModel.Coefficient.entries.associate { it.feature to -20.0 }
        )
    private val coefficients = BotMilitaryModel.Coefficient.entries.associate { it.key to 2.0 }
    private val enabled =
        incumbent.copy(weights = incumbent.weights + (BotMilitaryModel.GATE to 1.0) + coefficients)
    private val full =
        incumbent.copy(
            weights =
                incumbent.weights +
                    BotMilitaryModel.Coefficient.entries.associate { it.feature to 2.0 }
        )

    @Test
    fun allFourAbsoluteCoefficientsMatchTheFrozenFullModel() {
        assertEquals(
            setOf("card.armor", "card.stirrups", "card.long-bow", "card.gunpowder"),
            BotMilitaryModel.Coefficient.entries.map { it.feature }.toSet(),
        )
        for (coefficient in BotMilitaryModel.Coefficient.entries) {
            val observation = observation(coefficient.feature)

            assertEquals("pass", choose(observation, incumbent))
            assertEquals("play military", choose(observation, full))
            assertEquals(choose(observation, full), choose(observation, enabled))
        }
    }

    @Test
    fun easyAndNormalRetainTheirExactSeededOrdinaryChoices() {
        for (difficulty in listOf(BotDifficulty.EASY, BotDifficulty.NORMAL)) {
            for (seed in 0..20) {
                assertEquals(
                    BotPolicy.choose(observation(), difficulty, seed, incumbent),
                    BotPolicy.choose(observation(), difficulty, seed, enabled),
                )
            }
        }
    }

    @Test
    fun absentOrNonUnitGateRetainsTheOrdinaryPolicy() {
        for (gate in listOf(null, 0.0, 0.5, 2.0)) {
            val model =
                enabled.copy(
                    weights =
                        enabled.weights - BotMilitaryModel.GATE +
                            listOfNotNull(gate?.let { BotMilitaryModel.GATE to it })
                )

            assertEquals(choose(observation(), incumbent), choose(observation(), model))
        }
    }

    @Test
    fun anyMissingCoefficientRetainsAllFourOrdinaryWeights() {
        for (coefficient in BotMilitaryModel.Coefficient.entries) {
            val incomplete = enabled.copy(weights = enabled.weights - coefficient.key)

            assertEquals(choose(observation(), incumbent), choose(observation(), incomplete))
        }
    }

    @Test
    fun explicitZeroDoesNotRestoreTheOrdinaryCardBias() {
        val zero =
            enabled.copy(
                weights = enabled.weights + (BotMilitaryModel.Coefficient.STIRRUPS.key to 0.0)
            )
        val observation =
            observation()
                .copy(
                    candidates =
                        listOf(
                            BotCandidate(
                                "play military",
                                mapOf("card.stirrups" to 1.0, "points" to 2.0),
                            ),
                            BotCandidate("pass", mapOf("points" to 1.0)),
                        )
                )

        assertEquals("play military", choose(observation, zero))
    }

    @Test
    fun reservedTransportKeysNeverAddOrdinaryScore() {
        val unknown = "planning.militaryModel.future"
        val model = enabled.copy(weights = enabled.weights + (unknown to 1000.0))
        val candidate =
            BotCandidate(
                "pass",
                mapOf("points" to 1.0, BotMilitaryModel.GATE to 1000.0, unknown to 1000.0) +
                    coefficients.mapValues { 1000.0 },
            )

        assertEquals(1.0, BotPolicy.score(candidate, model))
    }

    @Test
    fun expansionReplacementRetainsTheMilitaryWeightsInTheExistingBeam() {
        val expansion =
            BotExpansionModel.Coefficient.entries.associate {
                it.key to if (it == BotExpansionModel.Coefficient.INCOME) 1.0 else 0.0
            } + (BotExpansionModel.GATE to 1.0)
        val scoped = enabled.copy(weights = enabled.weights + expansion)
        val reference = full.copy(weights = full.weights + expansion)
        val observation =
            observation()
                .copy(
                    decision = "expand",
                    availableTokens = 2,
                    candidates =
                        listOf(
                            BotCandidate(
                                "place 1 a",
                                mapOf("card.stirrups" to 1.0),
                                tokenCost = 1,
                                group = "a",
                                family = "expansion",
                            ),
                            BotCandidate(
                                "place 1 b",
                                mapOf("income" to 1.0),
                                tokenCost = 1,
                                group = "b",
                                family = "expansion",
                            ),
                            BotCandidate(
                                "place 2 c",
                                mapOf("income" to 2.0),
                                tokenCost = 2,
                                group = "c",
                                family = "expansion",
                            ),
                        ),
                )

        assertEquals("place 1 a", choose(observation, reference))
        assertEquals(choose(observation, reference), choose(observation, scoped))
    }

    @Test
    fun maximumSizeModelCanReplaceAllFourCoefficients() {
        val model =
            BotModel(
                weights =
                    (0 until 4091).associate { "unused.$it" to 0.0 } +
                        (BotMilitaryModel.GATE to 1.0) +
                        coefficients
            )

        assertEquals("play military", choose(observation(), model))
    }

    private fun choose(observation: BotObservation, model: BotModel): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

    private fun observation(feature: String = "card.stirrups"): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "play-cards",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 0,
            candidates =
                listOf(
                    BotCandidate("play military", mapOf(feature to 1.0)),
                    BotCandidate("pass", mapOf("points" to 1.0)),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
