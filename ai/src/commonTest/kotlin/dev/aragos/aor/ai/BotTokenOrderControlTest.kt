package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same control fixtures run on JVM and JavaScript. */
class BotTokenOrderControlTest {
    private val ordinary = BotModel(weights = mapOf("points" to 1.0))
    private val enabled =
        ordinary.copy(weights = ordinary.weights + (BotTokenOrderControl.FIXED_16_TO_15 to 1.0))

    @Test
    fun enabledControlChoosesTheOfferedNeighborOfSixteen() {
        assertEquals("buy-tokens 15", choose(enabled))
    }

    @Test
    fun legacyModelRetainsItsActualChoice() {
        assertEquals("buy-tokens 16", choose(ordinary))
    }

    @Test
    fun disabledControlRetainsItsActualChoice() {
        val disabled =
            enabled.copy(weights = enabled.weights + (BotTokenOrderControl.FIXED_16_TO_15 to 0.0))

        assertEquals("buy-tokens 16", choose(disabled))
    }

    @Test
    fun missingOfferedFifteenRetainsSixteen() {
        val original = observation()
        val reduced =
            original.copy(candidates = original.candidates.filter { it.command != "buy-tokens 15" })

        assertEquals("buy-tokens 16", choose(enabled, reduced))
    }

    @Test
    fun otherOrdinaryAmountsRemainUnchanged() {
        assertEquals("buy-tokens 17", choose(enabled, observation(best = 17)))
    }

    @Test
    fun normalDifficultyRetainsItsOrdinaryChoice() {
        assertEquals(
            "buy-tokens 16",
            BotPolicy.choose(observation(), BotDifficulty.NORMAL, 0, enabled),
        )
    }

    @Test
    fun easyDifficultyRetainsItsSeededChoice() {
        val observation = observation()

        assertEquals(
            BotPolicy.choose(observation, BotDifficulty.EASY, 21, ordinary),
            BotPolicy.choose(observation, BotDifficulty.EASY, 21, enabled),
        )
    }

    @Test
    fun reservedControlFeaturesNeverContributeToOrdinaryScores() {
        val candidate =
            BotCandidate(
                command = "buy-tokens 15",
                family = "tokens",
                features = mapOf("points" to 2.0, BotTokenOrderControl.FIXED_16_TO_15 to 1000.0),
            )

        assertEquals(2.0, BotPolicy.score(candidate, enabled))
    }

    @Test
    fun controlUsesTheTokenHeadsChoiceInsteadOfTheLinearScoreWinner() {
        val observation = observation(best = 8)
        val model =
            BotTokenValue.withWeights(
                enabled,
                BotTokenValue.featureNames.map {
                    if (it == BotTokenPlanFeature.HELD_COMMODITY.key) 1.0 else 0.0
                },
            )

        assertEquals("buy-tokens 15", choose(model, observation))
    }

    @Test
    fun correctionHeadStillDeterminesTheIncumbentBeforeTheFixedControl() {
        val model =
            BotTokenCorrection.withWeights(
                enabled,
                BotTokenValue.featureNames.map {
                    if (it == BotTokenPlanFeature.PUBLIC_COMMODITY.key) 1.0 else 0.0
                },
            )

        assertEquals("buy-tokens 17", choose(model))
    }

    private fun choose(model: BotModel, observation: BotObservation = observation()): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

    private fun observation(best: Int = 16): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "buy-tokens",
            context = mapOf("stock" to 36.0),
            availableCash = 36,
            availableTokens = 36,
            candidates =
                listOf(8, 15, 16, 17).map { amount ->
                    BotCandidate(
                        command = "buy-tokens $amount",
                        family = "tokens",
                        features =
                            BotTokenValue.featureNames.associateWith {
                                when {
                                    it == BotTokenPlanFeature.HELD_COMMODITY.key && amount == 16 ->
                                        1.0
                                    it == BotTokenPlanFeature.PUBLIC_COMMODITY.key &&
                                        amount == 17 -> 1.0
                                    else -> 0.0
                                }
                            } + mapOf("points" to if (amount == best) 1.0 else 0.0),
                    )
                },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
