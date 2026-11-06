package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.CommodityCardCountView
import dev.aragos.aor.Epoch
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotEconomicProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val ids = listOf(p1, p2, PlayerId("p3"))

    @Test
    fun firstMarketHeldValueIncludesIndustryAndShortage() {
        val state =
            fixture("wool-2", advances = setOf(AdvanceId.INDUSTRY))
                .copy(adjustments = mapOf(Commodity.WOOL to listOf(CommodityAdjustment.SHORTAGE)))
        val view = state.viewForDecision(p1)

        val features =
            BotEconomicProjection(view, p1)
                .expansion(
                    ProvinceId("smyrna"),
                    offer("smyrna", 2),
                    2,
                )

        assertThat(features[BotEconomicFeature.HELD_COMMODITY.key]).isEqualTo(18.0)
    }

    @Test
    fun sharedCardCountsOnlyItsBestCommodityChoice() {
        val original = fixture("cloth-wine")
        val state =
            original.copy(
                board =
                    original.board
                        .placeMarker(ProvinceId("lisbon"), p1)
                        .board
                        .placeMarker(ProvinceId("crete"), p1)
                        .board
            )
        val view = state.viewForDecision(p1)

        val features =
            BotEconomicProjection(view, p1)
                .expansion(
                    ProvinceId("tunis"),
                    offer("tunis", 4),
                    4,
                )

        // Venice already supplies cloth. Acquiring Tunis pays 20, matching the two wine markets.
        assertThat(features[BotEconomicFeature.HELD_COMMODITY.key]).isNull()
    }

    @Test
    fun publicExposureExcludesTheViewersOwnPhysicalCards() {
        val view =
            fixture("wool-1", "wool-2")
                .viewForDecision(p1)
                .copy(
                    commodityCards =
                        listOf(CommodityCardCountView(Commodity.WOOL, listOf(1, 1, 1), 3))
                )

        val features =
            BotEconomicProjection(view, p1)
                .expansion(
                    ProvinceId("smyrna"),
                    offer("smyrna", 2),
                    2,
                )

        assertThat(features[BotEconomicFeature.HELD_COMMODITY.key]).isEqualTo(4.0)
        assertThat(features[BotEconomicFeature.PUBLIC_COMMODITY.key]).isEqualTo(2.0)
    }

    @Test
    fun retentionCountsOnlyCurrentPoolAndUnlockedDirectLandMarkets() {
        val view = fixture("wool-2", pool = 4).viewForDecision(p1)

        val features = BotEconomicProjection(view, p1).card("held-1")

        // Smyrna is adjacent; Angora needs the missing Overland East advance.
        assertThat(features[BotEconomicFeature.RETAINED_COMMODITY.key]).isEqualTo(-2.0)
    }

    @Test
    fun zeroCurrentPoolDoesNotEraseTheValueOfKeepingAFutureOption() {
        val view = fixture("wool-2", pool = 0).viewForDecision(p1)

        val features = BotEconomicProjection(view, p1).card("held-1")

        assertThat(features[BotEconomicFeature.RETAINED_COMMODITY.key]).isNull()
        assertThat(features[BotEconomicFeature.COMMODITY_OPTION.key]).isEqualTo(-1.0)
    }

    @Test
    fun finalCardWindowDoesNotChargeForFutureRetention() {
        val view =
            fixture("wool-2")
                .copy(stage = Stage.FINAL_CARD_PLAY, endingTriggered = true)
                .viewForDecision(p1)

        val features = BotEconomicProjection(view, p1).card("held-1")

        assertThat(features).isEmpty()
    }

    @Test
    fun modelsWithoutNewWeightsKeepTheirExactScoresAndMove() {
        val observation = BotObservations.from(fixture("wool-2"), p1)
        val original =
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(
                            features = candidate.features.filterKeys { !it.startsWith("planning.") }
                        )
                    }
            )
        val legacy = BotModel(weights = BotModels.default.weights + ("heldCommodity" to 100000.0))

        val scores = observation.candidates.map { BotPolicy.score(it, legacy) }

        assertThat(
                observation.candidates.any { candidate ->
                    candidate.features.keys.any { it.startsWith("planning.") }
                }
            )
            .isTrue()
        assertThat(scores)
            .containsExactlyElementsIn(original.candidates.map { BotPolicy.score(it, legacy) })
            .inOrder()
        assertThat(BotPolicy.choose(observation, BotDifficulty.HARD, 17, legacy))
            .isEqualTo(BotPolicy.choose(original, BotDifficulty.HARD, 17, legacy))
    }

    @Test
    fun publicCommodityCountsAreAvailableInTheSameDecisionViewAsFeatures() {
        val state = fixture("wool-2")

        val decision = state.viewForDecision(p1)

        assertThat(decision.commodityCards).isNotEmpty()
        assertThat(decision.commodityCards).isEqualTo(state.viewForPlayer(p1).commodityCards)
    }

    private fun offer(destination: String, tokens: Int): ExpansionOfferView =
        ExpansionOfferView(
            destination = ProvinceId(destination),
            primaryAction = ExpansionAction.PLACE,
            requiredTokens = tokens,
            availablePool = 15,
            resultingPool = 15 - tokens,
            establishesMarker = true,
            uncertain = false,
            blockers = emptyList(),
            partialPlacements = emptyList(),
            shipping = null,
            incomeBefore = 20,
            incomeAfter = 23,
            reachable = true,
        )

    private fun fixture(
        vararg cards: String,
        pool: Int = 4,
        advances: Set<AdvanceId> = emptySet(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals)
                .fold(BoardState()) { current, (id, capital) ->
                    current.placeMarker(ProvinceId(capital), id).board
                }
                .placeMarker(ProvinceId("constantinople"), p1)
                .board
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            currentEpoch = Epoch.THREE,
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 100,
                                poolTokens = pool,
                                stockTokens = 36 - pool,
                                advances =
                                    PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) }),
                                hand =
                                    if (id == p1) {
                                        cards.mapIndexed { cardIndex, card ->
                                            HandCard(
                                                "held-${cardIndex + 1}",
                                                CardData.definition(card),
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
