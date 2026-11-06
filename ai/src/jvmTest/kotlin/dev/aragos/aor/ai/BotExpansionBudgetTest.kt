package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Commodity
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test

class BotExpansionBudgetTest {
    private val model = BotModel(weights = mapOf("points" to 1.0))

    @Test
    fun overseasBudgetAppliesWithoutAnyLearnedBundleWeight() {
        val observation = observation(markerStock = 2, overseasSlots = 1, overseasCost = 1)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 10 domestic")
        assertThat(BotExpansionInteractions.forObservation(observation, model)).isNull()
    }

    @Test
    fun partialPlacementsStillConsumeDistinctOverseasSlots() {
        val observation =
            observation(
                markerStock = 2,
                overseasSlots = 1,
                overseasCost = 1,
                overseasMarkerCost = 0,
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 10 domestic")
    }

    @Test
    fun markerBudgetAppliesWithoutAnyLearnedBundleWeight() {
        val observation = observation(markerStock = 1, overseasSlots = 0, overseasCost = 0)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 10 domestic")
    }

    @Test
    fun existingPresencesCanBeCompletedWithoutANewOverseasSlot() {
        val observation = observation(markerStock = 2, overseasSlots = 0, overseasCost = 0)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 5 a")
    }

    @Test
    fun observationsWithoutResourceFactsRetainLegacyPlanning() {
        val observation =
            observation(markerStock = 1, overseasSlots = 1, overseasCost = 1)
                .copy(expansionPlanning = null)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 5 a")
    }

    private fun observation(
        markerStock: Int,
        overseasSlots: Int,
        overseasCost: Int,
        overseasMarkerCost: Int = 1,
    ): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "expand",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 12,
            candidates =
                listOf(
                    BotCandidate(
                        "place 5 a",
                        mapOf("points" to 7.0),
                        tokenCost = 5,
                        group = "a",
                        family = "expansion",
                    ),
                    BotCandidate(
                        "place 5 b",
                        mapOf("points" to 7.0),
                        tokenCost = 5,
                        group = "b",
                        family = "expansion",
                    ),
                    BotCandidate(
                        "place 10 domestic",
                        mapOf("points" to 13.0),
                        tokenCost = 10,
                        group = "domestic",
                        family = "expansion",
                    ),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            expansionPlanning =
                BotExpansionPlanning(
                    markerStock = markerStock,
                    overseasSlots = overseasSlots,
                    industry = false,
                    commodities = Commodity.entries.map { BotPlanningCommodity(it, 0, 1, 0, 0) },
                    heldCards = emptyList(),
                    steps =
                        mapOf(
                            "place 5 a" to
                                BotExpansionStep(
                                    overseasMarkerCost,
                                    newOverseasCost = overseasCost,
                                ),
                            "place 5 b" to
                                BotExpansionStep(
                                    overseasMarkerCost,
                                    newOverseasCost = overseasCost,
                                ),
                            "place 10 domestic" to BotExpansionStep(1),
                        ),
                ),
        )
}
