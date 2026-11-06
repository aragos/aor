package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Commodity
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test

class BotExpansionPlanningTest {
    @Test
    fun twoRelatedMarketsCanBeatTheLargestSingleOffer() {
        val observation = woolObservation()

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel())

        assertThat(selected).isEqualTo("place 1 a")
    }

    @Test
    fun jointHeldPayoutSubtractsTheStandaloneGains() {
        val interaction = interactions(woolObservation(), heldModel())

        val value = interaction.value(setOf("place 1 a", "place 1 b"))

        // One wool market pays 2; each standalone second market gains 6. Three pay 18.
        assertThat(value).isEqualTo(4.0)
    }

    @Test
    fun eachPhysicalDuplicateCardRetainsItsOwnGain() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val observation =
            original.copy(
                expansionPlanning = planning.copy(heldCards = List(2) { setOf(Commodity.WOOL) })
            )

        val value = interactions(observation, heldModel()).value(setOf("place 1 a", "place 1 b"))

        assertThat(value).isEqualTo(8.0)
    }

    @Test
    fun publicInteractionUsesTheDeclaredOutsideCardExposure() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val observation =
            original.copy(
                expansionPlanning =
                    planning.copy(
                        heldCards = emptyList(),
                        commodities =
                            planning.commodities.map {
                                if (it.commodity == Commodity.WOOL) it.copy(outsideCards = 3)
                                else it
                            },
                    )
            )
        val model = BotModel(weights = mapOf(BotBundleFeature.PUBLIC_COMMODITY.key to 1.0))

        val value = interactions(observation, model).value(setOf("place 1 a", "place 1 b"))

        assertThat(value).isEqualTo(12.0)
    }

    @Test
    fun oneSharedChoiceCardDoesNotPayBothCommodities() {
        val observation = sharedChoiceObservation()

        val value = interactions(observation, heldModel()).value(setOf("place 1 a", "place 1 b"))

        assertThat(value).isEqualTo(-5.0)
    }

    @Test
    fun negativeSharedCardInteractionCanRejectAnOvervaluedPair() {
        val observation = sharedChoiceObservation()

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel())

        assertThat(selected).isEqualTo("place 2 c")
    }

    @Test
    fun industryAndShortageDoNotPayTheZeroMarketOwner() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val observation =
            original.copy(
                expansionPlanning =
                    planning.copy(
                        industry = true,
                        commodities =
                            planning.commodities.map {
                                if (it.commodity == Commodity.WOOL)
                                    it.copy(markets = 0, adjustmentOffset = 1)
                                else it
                            },
                    )
            )

        val value = interactions(observation, heldModel()).value(setOf("place 1 a", "place 1 b"))

        // Each first marker gains 18, whereas two together gain 32, not 36.
        assertThat(value).isEqualTo(-4.0)
    }

    @Test
    fun aBundleCannotSpendMoreMarkersThanThePlayerHas() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val observation = original.copy(expansionPlanning = planning.copy(markerStock = 1))

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel())

        assertThat(selected).isEqualTo("place 2 c")
    }

    @Test
    fun aCompetitionReservesAStockMarkerWithoutEarningCommoditySynergy() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val attack =
            BotCandidate(
                "attack c",
                mapOf("points" to 7.0),
                tokenCost = 1,
                group = "c",
                family = "expansion",
            )
        val observation =
            original.copy(
                availableTokens = 3,
                candidates = original.candidates.take(2) + attack,
                expansionPlanning =
                    planning.copy(
                        steps =
                            planning.steps - "place 2 c" + (attack.command to BotExpansionStep(1))
                    ),
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel())

        assertThat(selected).isEqualTo("place 1 a")
    }

    @Test
    fun relatedMarketSynergyCannotExceedTheTokenBudget() {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        val candidates =
            original.candidates.map { candidate ->
                candidate.copy(
                    command = candidate.command.replace("place 1", "place 2"),
                    tokenCost = 2,
                )
            }
        val observation =
            original.copy(
                candidates = candidates,
                expansionPlanning =
                    planning.copy(
                        steps =
                            planning.steps.mapKeys { (command, _) ->
                                command.replace("place 1", "place 2")
                            }
                    ),
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel(weight = 3.0))

        assertThat(selected).isEqualTo("place 2 c")
    }

    @Test
    fun missingMetadataKeepsTheLegacyPlannerEvenWhenTheNewWeightExists() {
        val observation = woolObservation().copy(expansionPlanning = null)

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, heldModel())

        assertThat(selected).isEqualTo("place 2 c")
    }

    @Test
    fun zeroBundleWeightsKeepChoicesWhenProjectedBudgetsAreNonbinding() {
        val observation = woolObservation()
        val original = observation.copy(expansionPlanning = null)
        val models =
            listOf(
                BotModels.default,
                BotModel(weights = mapOf("points" to 1.0)),
                BotModel(
                    weights =
                        BotModels.default.weights +
                            mapOf("planning.heldCommodity" to 2.0, "heldCommodity" to 100000.0)
                ),
            )

        for (model in models) {
            for (difficulty in BotDifficulty.entries) {
                for (seed in 0..20) {
                    assertThat(BotPolicy.choose(observation, difficulty, seed, model))
                        .isEqualTo(BotPolicy.choose(original, difficulty, seed, model))
                }
            }
            assertThat(BotExpansionInteractions.forObservation(observation, model)).isNull()
        }
    }

    private fun sharedChoiceObservation(): BotObservation {
        val original = woolObservation()
        val planning =
            checkNotNull(original.expansionPlanning) { "The fixture requires planning facts." }
        return original.copy(
            candidates =
                original.candidates.map {
                    if (it.command == "place 2 c") it.copy(features = mapOf("points" to 10.0))
                    else it
                },
            expansionPlanning =
                planning.copy(
                    heldCards = listOf(setOf(Commodity.CLOTH, Commodity.WINE)),
                    commodities = planning.commodities.map { it.copy(markets = 0, baseValue = 5) },
                    steps =
                        planning.steps +
                            mapOf(
                                "place 1 a" to BotExpansionStep(1, setOf(Commodity.CLOTH)),
                                "place 1 b" to BotExpansionStep(1, setOf(Commodity.WINE)),
                            ),
                ),
        )
    }

    private fun woolObservation(): BotObservation {
        val candidates =
            listOf(
                BotCandidate(
                    "place 1 a",
                    mapOf("points" to 6.0),
                    tokenCost = 1,
                    group = "a",
                    family = "expansion",
                ),
                BotCandidate(
                    "place 1 b",
                    mapOf("points" to 6.0),
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
            )
        return BotObservation(
            playerId = "p1",
            decision = "expand",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 2,
            candidates = candidates,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            expansionPlanning =
                BotExpansionPlanning(
                    markerStock = 2,
                    industry = false,
                    commodities =
                        Commodity.entries.map { commodity ->
                            BotPlanningCommodity(
                                commodity,
                                if (commodity == Commodity.WOOL) 1 else 0,
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

    private fun heldModel(weight: Double = 1.0): BotModel =
        BotModel(
            weights =
                mapOf(
                    "points" to 1.0,
                    BotBundleFeature.HELD_COMMODITY.key to weight,
                )
        )

    private fun interactions(
        observation: BotObservation,
        model: BotModel,
    ): BotExpansionInteractions =
        checkNotNull(BotExpansionInteractions.forObservation(observation, model)) {
            "The interaction fixture requires an enabled bundle weight and metadata."
        }
}
