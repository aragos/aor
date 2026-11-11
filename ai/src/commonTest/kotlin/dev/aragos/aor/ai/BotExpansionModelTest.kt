package dev.aragos.aor.ai

import dev.aragos.aor.Commodity
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

/** The routing and unchanged beam planner are exercised on both JVM and JavaScript. */
class BotExpansionModelTest {
    private val incumbent =
        BotModel(
            weights =
                mapOf("points" to 1.0, "income" to -5.0, BotBundleFeature.HELD_COMMODITY.key to 1.0)
        )
    private val coefficients =
        BotExpansionModel.Coefficient.entries.associateWith {
            if (it == BotExpansionModel.Coefficient.INCOME) 1.0 else 0.0
        }
    private val enabled =
        incumbent.copy(
            weights =
                incumbent.weights +
                    (BotExpansionModel.GATE to 1.0) +
                    coefficients.mapKeys { (coefficient, _) -> coefficient.key }
        )
    private val fullModel =
        incumbent.copy(
            weights =
                incumbent.weights + coefficients.mapKeys { (coefficient, _) -> coefficient.feature }
        )

    @Test
    fun suppliedCoefficientsReplaceRatherThanAddToTheOrdinaryWeights() {
        val observation = observation()

        assertEquals("pass", choose(observation, incumbent))
        assertEquals("attack a", choose(observation, fullModel))
        assertEquals(choose(observation, fullModel), choose(observation, enabled))
    }

    @Test
    fun allEightNativeFeaturesReceiveTheirExactReplacement() {
        assertEquals(
            setOf(
                "tokens",
                "progress",
                "reach",
                "commodity",
                "income",
                "risk",
                "action.expansion",
                "action.pass",
            ),
            BotExpansionModel.Coefficient.entries.map { it.feature }.toSet(),
        )
        for (coefficient in BotExpansionModel.Coefficient.entries) {
            val base = incumbent.copy(weights = incumbent.weights + (coefficient.feature to -5.0))
            val targets = coefficients.mapValues { (key, _) ->
                if (key == coefficient) 1.0 else 0.0
            }
            val complete = base.copy(weights = base.weights + targets.mapKeys { it.key.feature })
            val scoped =
                base.copy(
                    weights =
                        base.weights +
                            (BotExpansionModel.GATE to 1.0) +
                            targets.mapKeys { it.key.key }
                )
            val observation = observation(feature = coefficient.feature)

            assertEquals("attack a", choose(observation, complete))
            assertEquals(choose(observation, complete), choose(observation, scoped))
        }
    }

    @Test
    fun replacementPreservesTheExistingBeamAndCommodityInteractions() {
        val observation = beamObservation()

        assertEquals("place 2 c", choose(observation, incumbent))
        assertEquals("place 1 a", choose(observation, fullModel))
        assertEquals(choose(observation, fullModel), choose(observation, enabled))
    }

    @Test
    fun explicitZeroDoesNotRestoreAnOrdinaryCoefficient() {
        val zero =
            enabled.copy(
                weights = enabled.weights + (BotExpansionModel.Coefficient.INCOME.key to 0.0)
            )
        val observation =
            observation()
                .copy(
                    candidates =
                        listOf(
                            BotCandidate("attack a", mapOf("income" to 1.0, "points" to 1.0)),
                            BotCandidate("pass", mapOf("points" to 0.5)),
                        )
                )

        assertEquals("attack a", choose(observation, zero))
    }

    @Test
    fun otherDecisionFamiliesRetainTheirOrdinaryChoices() {
        for (decision in
            listOf(
                "buy-tokens",
                "play-cards",
                "purchase-shipping",
                "purchase-advances",
                "buy-card",
            )) {
            val observation = observation().copy(decision = decision)

            assertEquals(choose(observation, incumbent), choose(observation, enabled))
        }
    }

    @Test
    fun easyAndNormalRetainTheirExactSeededChoices() {
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
    fun absentAndNonUnitGatesRetainTheIncumbent() {
        for (gate in listOf(null, 0.0, 0.5, 2.0)) {
            val weights = enabled.weights - BotExpansionModel.GATE
            val inactive =
                enabled.copy(
                    weights = weights + listOfNotNull(gate?.let { BotExpansionModel.GATE to it })
                )

            assertEquals(choose(observation(), incumbent), choose(observation(), inactive))
        }
    }

    @Test
    fun anyMissingCoefficientRetainsTheEntireIncumbent() {
        for (coefficient in BotExpansionModel.Coefficient.entries) {
            val incomplete = enabled.copy(weights = enabled.weights - coefficient.key)

            assertEquals(choose(observation(), incumbent), choose(observation(), incomplete))
        }
    }

    @Test
    fun reservedFeaturesNeverContributeToOrdinaryScores() {
        val unknown = "planning.expansionModel.future"
        val model = enabled.copy(weights = enabled.weights + (unknown to 1000.0))
        val candidate =
            BotCandidate(
                "pass",
                mapOf("points" to 2.0, BotExpansionModel.GATE to 1000.0, unknown to 1000.0) +
                    BotExpansionModel.Coefficient.entries.associate { it.key to 1000.0 },
            )

        assertEquals(2.0, BotPolicy.score(candidate, model))
    }

    @Test
    fun incompleteExpansionMetadataCannotEnterTheOrdinaryBeamScore() {
        val original = beamObservation()
        val observation =
            original.copy(
                candidates =
                    original.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features +
                                    (BotExpansionModel.Coefficient.INCOME.key to 1000.0)
                        )
                    }
            )
        val incomplete = enabled.copy(weights = enabled.weights - BotExpansionModel.GATE)

        assertEquals("place 2 c", choose(original, incumbent))
        assertEquals(choose(original, incumbent), choose(observation, incomplete))
    }

    @Test
    fun aCompleteMaximumSizeModelCanApplyItsExpansionCoefficients() {
        val model =
            BotModel(
                weights =
                    (0 until 4087).associate { "unused.$it" to 0.0 } +
                        (BotExpansionModel.GATE to 1.0) +
                        coefficients.mapKeys { it.key.key }
            )

        assertEquals("attack a", choose(observation(), model))
    }

    private fun choose(observation: BotObservation, model: BotModel): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

    private fun observation(feature: String = "income"): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "expand",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 2,
            candidates =
                listOf(
                    BotCandidate(
                        "attack a",
                        mapOf(feature to 1.0),
                        tokenCost = 1,
                        family = "expansion",
                    ),
                    BotCandidate("pass", mapOf("points" to 0.5)),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun beamObservation(): BotObservation =
        observation()
            .copy(
                candidates =
                    listOf(
                        BotCandidate(
                            "place 1 a",
                            mapOf("points" to 5.0, "income" to 1.0),
                            tokenCost = 1,
                            group = "a",
                            family = "expansion",
                        ),
                        BotCandidate(
                            "place 1 b",
                            mapOf("points" to 5.0, "income" to 1.0),
                            tokenCost = 1,
                            group = "b",
                            family = "expansion",
                        ),
                        BotCandidate(
                            "place 2 c",
                            mapOf("points" to 13.0),
                            tokenCost = 2,
                            group = "c",
                            family = "expansion",
                        ),
                    ),
                expansionPlanning =
                    BotExpansionPlanning(
                        markerStock = 2,
                        industry = false,
                        commodities =
                            Commodity.entries.map {
                                BotPlanningCommodity(
                                    it,
                                    if (it == Commodity.WOOL) 1 else 0,
                                    2,
                                    0,
                                    0,
                                )
                            },
                        heldCards = listOf(setOf(Commodity.WOOL)),
                        steps =
                            mapOf(
                                "place 1 a" to BotExpansionStep(1, setOf(Commodity.WOOL)),
                                "place 1 b" to BotExpansionStep(1, setOf(Commodity.WOOL)),
                                "place 2 c" to BotExpansionStep(1, setOf(Commodity.TIMBER)),
                            ),
                    ),
            )
}
