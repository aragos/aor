package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CatalogConfiguration
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.MiseryChangeView
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotPurchaseProjectionTest {
    private val p1 = PlayerId("p1")
    private val ids = listOf(p1, PlayerId("p2"), PlayerId("p3"))
    private val prerequisites =
        setOf(AdvanceId.CARAVAN, AdvanceId.WIND_WATERMILL, AdvanceId.IMPROVED_AGRICULTURE)
    private val correctionKeys =
        setOf(
            BotEconomicFeature.TOKEN_CAP_CORRECTION.key,
            BotEconomicFeature.ADVANCE_INCOME_CORRECTION.key,
            BotEconomicFeature.ENDGAME_ADVANCE_INCOME_CORRECTION.key,
            BotEconomicFeature.FOREGONE_INTEREST.key,
            BotEconomicFeature.FINAL_ADVANCE_SCORE.key,
        )

    @Test
    fun finalMasterArtValuesItsDiscountedPriceToPointsExchange() {
        val state =
            fixture(
                    Stage.PURCHASE,
                    cash = 386,
                    advances = setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD),
                )
                .copy(endingTriggered = true)
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "buy-advance P" }

        assertThat(candidate.cashCost).isEqualTo(70)
        assertThat(candidate.features[BotEconomicFeature.FINAL_ADVANCE_SCORE.key]).isEqualTo(20.0)
    }

    @Test
    fun anOrdinaryTurnDoesNotReceiveTheFinalScoreResidual() {
        val state =
            fixture(
                Stage.PURCHASE,
                cash = 386,
                advances = setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD),
            )
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "buy-advance P" }

        assertThat(candidate.features).doesNotContainKey(BotEconomicFeature.FINAL_ADVANCE_SCORE.key)
    }

    @Test
    fun zeroFinalScoreRemainsPresentForResearchRepricing() {
        val state = fixture(Stage.PURCHASE, cash = 100).copy(endingTriggered = true)
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "buy-advance B" }

        assertThat(candidate.cashCost).isEqualTo(60)
        assertThat(candidate.features)
            .containsEntry(BotEconomicFeature.FINAL_ADVANCE_SCORE.key, 0.0)
    }

    @Test
    fun immediateOwnMiseryReliefIncreasesTheFinalScore() {
        val projection = finalProjection()

        val features = projection.finalAdvanceScore(60, 40, listOf(MiseryChangeView(p1, 30, 20)))

        assertThat(features[BotEconomicFeature.FINAL_ADVANCE_SCORE.key]).isEqualTo(30.0)
    }

    @Test
    fun immediateOwnMiseryIncreaseReducesTheFinalScore() {
        val projection = finalProjection()

        val features = projection.finalAdvanceScore(60, 40, listOf(MiseryChangeView(p1, 20, 30)))

        assertThat(features[BotEconomicFeature.FINAL_ADVANCE_SCORE.key]).isEqualTo(10.0)
    }

    @Test
    fun opponentMiseryDoesNotAlterTheOwnersFinalScore() {
        val projection = finalProjection()

        val features =
            projection.finalAdvanceScore(60, 40, listOf(MiseryChangeView(ids[1], 20, 30)))

        assertThat(features[BotEconomicFeature.FINAL_ADVANCE_SCORE.key]).isEqualTo(20.0)
    }

    @Test
    fun chaosDoesNotInventANumericTerminalScore() {
        val projection = finalProjection()

        val features =
            projection.finalAdvanceScore(60, 40, listOf(MiseryChangeView(p1, 1000, null)))

        assertThat(features).isEmpty()
    }

    @Test
    fun theResidualCanCorrectTheObservedMasterArtPass() {
        val state =
            fixture(
                    Stage.PURCHASE,
                    cash = 386,
                    advances = setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD),
                )
                .copy(endingTriggered = true)
        val observation = onlyPurchase(BotObservations.from(state, p1), "buy-advance P")
        val original = BotModel(weights = mapOf("advance.P" to -5.35))
        val corrected =
            original.copy(
                weights = original.weights + (BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 1.0)
            )

        val before = BotPolicy.choose(observation, BotDifficulty.HARD, 0, original)
        val after = BotPolicy.choose(observation, BotDifficulty.HARD, 0, corrected)

        assertThat(before).isEqualTo("pass")
        assertThat(after).isEqualTo("buy-advance P")
    }

    @Test
    fun aFinalPointsDiscountDoesNotEraseItsLargerInterestLoss() {
        val state =
            fixture(
                    Stage.PURCHASE,
                    cash = 65,
                    markers = 19,
                    advances =
                        prerequisites +
                            setOf(
                                AdvanceId.INTEREST_PROFIT,
                                AdvanceId.WRITTEN_RECORD,
                                AdvanceId.INSTITUTIONAL_RESEARCH,
                            ),
                )
                .copy(endingTriggered = true)
        val observation = onlyPurchase(BotObservations.from(state, p1), "buy-advance O")
        val model =
            BotModel(
                weights =
                    mapOf(
                        "advance.O" to 1.0,
                        BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 1.0,
                        BotEconomicFeature.FOREGONE_INTEREST.key to 1.0,
                    )
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("pass")
    }

    @Test
    fun aLearnedCorrectionCanBuyMoreThanSixteenTokensWithoutChangingTheLegacyPrior() {
        val state = fixture(Stage.TOKEN_PURCHASE, cash = 200)
        val observation = BotObservations.from(state, p1)
        val model =
            BotModels.default.copy(
                weights =
                    BotModels.default.weights +
                        (BotEconomicFeature.TOKEN_CAP_CORRECTION.key to 12.0)
            )

        val legacy = BotPolicy.choose(observation, BotDifficulty.HARD, 17)
        val corrected = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(legacy).isEqualTo("buy-tokens 16")
        assertThat(corrected).isEqualTo("buy-tokens 36")
        assertThat(state.step(TextCodec.parse("p1: $corrected")).playerState(p1).tokenPurchase)
            .isNotNull()
    }

    @Test
    fun aQuarterCashBudgetBelowTheCapNeedsNoCorrection() {
        val observation = BotObservations.from(fixture(Stage.TOKEN_PURCHASE, cash = 64), p1)

        assertThat(
                observation.candidates.map { it.feature(BotEconomicFeature.TOKEN_CAP_CORRECTION) }
            )
            .containsExactlyElementsIn(List(observation.candidates.size) { 0.0 })
    }

    @Test
    fun stockBelowTheCapNeedsNoCorrection() {
        val observation =
            BotObservations.from(fixture(Stage.TOKEN_PURCHASE, cash = 200, stock = 12), p1)

        assertThat(
                observation.candidates.map { it.feature(BotEconomicFeature.TOKEN_CAP_CORRECTION) }
            )
            .containsExactlyElementsIn(List(observation.candidates.size) { 0.0 })
    }

    @Test
    fun middleClassCorrectionMatchesItsAuthoritativeIncomeIncrease() {
        val state = fixture(Stage.PURCHASE, cash = 200, advances = prerequisites)
        val before =
            checkNotNull(state.viewForPlayer(p1).players.single { it.id == p1 }.income) {
                "The active purchase fixture requires an income quote."
            }
        val observation = BotObservations.from(state, p1)
        val candidate = observation.candidates.single { it.command == "buy-advance Z" }
        val after =
            state
                .step(TextCodec.parse("p1: buy-advance Z"))
                .viewForPlayer(p1)
                .players
                .single { it.id == p1 }
                .income

        val corrected =
            (candidate.features["income"] ?: 0.0) +
                candidate.feature(BotEconomicFeature.ADVANCE_INCOME_CORRECTION)

        assertThat(candidate.features["income"]).isEqualTo(1.0)
        val actual = checkNotNull(after) { "Buying Middle Class must retain its income quote." }
        assertThat(corrected).isEqualTo((actual.ordinary - before.ordinary).toDouble())
        assertThat(corrected).isEqualTo(10.0)
    }

    @Test
    fun interestProfitCorrectionUsesCashAfterPurchaseAndTheActualIncomeCap() {
        val state = fixture(Stage.PURCHASE, cash = 100, advances = prerequisites)
        val observation = BotObservations.from(state, p1)
        val candidate = observation.candidates.single { it.command == "buy-advance L" }
        val after =
            state
                .step(TextCodec.parse("p1: buy-advance L"))
                .viewForPlayer(p1)
                .players
                .single { it.id == p1 }
                .income

        val corrected =
            (candidate.features["income"] ?: 0.0) +
                candidate.feature(BotEconomicFeature.ADVANCE_INCOME_CORRECTION)

        assertThat(candidate.features["income"]).isEqualTo(5.0)
        val actual = checkNotNull(after) { "Buying Interest & Profit requires its income quote." }
        assertThat(corrected).isEqualTo(actual.interestProfit.toDouble())
        assertThat(corrected).isEqualTo(18.0)
    }

    @Test
    fun interestProfitCannotExceedTheCashLeftAfterItsPurchase() {
        val state = fixture(Stage.PURCHASE, cash = 55, advances = prerequisites)
        val observation = BotObservations.from(state, p1)
        val candidate = observation.candidates.single { it.command == "buy-advance L" }

        val corrected =
            (candidate.features["income"] ?: 0.0) +
                candidate.feature(BotEconomicFeature.ADVANCE_INCOME_CORRECTION)

        assertThat(corrected).isEqualTo(5.0)
    }

    @Test
    fun incomeCorrectionUsesTheSameEndgameFractionAsLegacyIncome() {
        val state =
            fixture(Stage.PURCHASE, cash = 100, advances = prerequisites)
                .copy(endingTriggered = true)
        val observation = BotObservations.from(state, p1)
        val candidate = observation.candidates.single { it.command == "buy-advance L" }

        assertThat(candidate.features[BotEconomicFeature.ENDGAME_ADVANCE_INCOME_CORRECTION.key])
            .isEqualTo(candidate.features[BotEconomicFeature.ADVANCE_INCOME_CORRECTION.key])
    }

    @Test
    fun discountedAdvanceIncludesTheInterestLostBeforeTheLastIncome() {
        val state =
            fixture(
                    Stage.PURCHASE,
                    cash = 65,
                    markers = 19,
                    advances =
                        prerequisites +
                            setOf(
                                AdvanceId.INTEREST_PROFIT,
                                AdvanceId.WRITTEN_RECORD,
                                AdvanceId.INSTITUTIONAL_RESEARCH,
                            ),
                )
                .copy(endingTriggered = true)
        val observation = BotObservations.from(state, p1)
        val candidate = observation.candidates.single { it.command == "buy-advance O" }
        val before = state.viewForPlayer(p1).players.single { it.id == p1 }.income
        val after =
            state
                .step(TextCodec.parse("p1: buy-advance O"))
                .viewForPlayer(p1)
                .players
                .single { it.id == p1 }
                .income

        val lost =
            checkNotNull(after) { "The purchased advance must retain its income quote." }
                .interestProfit -
                checkNotNull(before) { "The purchase requires an income quote." }.interestProfit

        assertThat(candidate.cashCost).isEqualTo(40)
        assertThat(candidate.feature(BotEconomicFeature.FOREGONE_INTEREST))
            .isEqualTo(lost.toDouble())
        assertThat(lost).isEqualTo(-40)
    }

    @Test
    fun spendingAboveTheIncomeCapDoesNotForfeitInterest() {
        val state =
            fixture(
                Stage.PURCHASE,
                cash = 100,
                advances = prerequisites + AdvanceId.INTEREST_PROFIT,
            )
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "buy-advance V" }

        assertThat(candidate.feature(BotEconomicFeature.FOREGONE_INTEREST)).isEqualTo(0.0)
    }

    @Test
    fun cashStabilizationIncludesItsLostInterest() {
        val initial =
            fixture(
                Stage.PURCHASE,
                cash = 25,
                advances = prerequisites + AdvanceId.INTEREST_PROFIT,
                hand = listOf("armor", "stirrups", "wool-1", "wool-2"),
            )
        val state =
            initial.copy(flow = initial.flow.copy(purchaseStep = PurchaseStep.STABILIZATION))
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "stabilize cash" }

        assertThat(candidate.cashCost).isEqualTo(10)
        assertThat(candidate.feature(BotEconomicFeature.FOREGONE_INTEREST)).isEqualTo(-3.0)
    }

    @Test
    fun acquiringInterestProfitDoesNotChargeForInterestNotYetOwned() {
        val state = fixture(Stage.PURCHASE, cash = 100, advances = prerequisites)
        val candidate =
            BotObservations.from(state, p1).candidates.single { it.command == "buy-advance L" }

        assertThat(candidate.feature(BotEconomicFeature.FOREGONE_INTEREST)).isEqualTo(0.0)
    }

    @Test
    fun finalCardSpendingCannotForfeitAnIncomeAlreadyCollected() {
        val view =
            fixture(
                    Stage.FINAL_CARD_PLAY,
                    cash = 25,
                    advances = prerequisites + AdvanceId.INTEREST_PROFIT,
                )
                .copy(endingTriggered = true)
                .viewForDecision(p1)

        val features = BotPurchaseProjection(view, p1).foregoneInterest(cashCost = 20)

        assertThat(features).isEmpty()
    }

    @Test
    fun modelsWithoutCorrectionWeightsRetainEveryScoreAndDifficultyChoice() {
        val states =
            listOf(
                fixture(Stage.TOKEN_PURCHASE, cash = 200),
                fixture(Stage.PURCHASE, cash = 200, advances = prerequisites),
                fixture(Stage.PURCHASE, cash = 100, advances = prerequisites)
                    .copy(endingTriggered = true),
                fixture(
                    Stage.PURCHASE,
                    cash = 25,
                    advances = prerequisites + AdvanceId.INTEREST_PROFIT,
                ),
            )
        for (state in states) {
            val observation = BotObservations.from(state, p1)
            val original =
                observation.copy(
                    candidates =
                        observation.candidates.map { candidate ->
                            candidate.copy(
                                features = candidate.features.filterKeys { it !in correctionKeys }
                            )
                        }
                )

            assertThat(observation.candidates.map { BotPolicy.score(it) })
                .containsExactlyElementsIn(original.candidates.map { BotPolicy.score(it) })
                .inOrder()
            for (difficulty in BotDifficulty.entries) {
                assertThat(BotPolicy.choose(observation, difficulty, 17))
                    .isEqualTo(BotPolicy.choose(original, difficulty, 17))
            }
        }
    }

    private fun BotCandidate.feature(feature: BotEconomicFeature): Double =
        features[feature.key] ?: 0.0

    private fun finalProjection(): BotPurchaseProjection =
        BotPurchaseProjection(
            fixture(Stage.PURCHASE, cash = 100).copy(endingTriggered = true).viewForDecision(p1),
            p1,
        )

    private fun onlyPurchase(observation: BotObservation, command: String): BotObservation =
        observation.copy(
            candidates = observation.candidates.filter { it.command in setOf(command, "pass") },
            advancePlanning =
                observation.advancePlanning?.let { planning ->
                    planning.copy(steps = planning.steps.filterKeys { it == command })
                },
        )

    private fun fixture(
        stage: Stage,
        cash: Int,
        stock: Int = 36,
        advances: Set<AdvanceId> = emptySet(),
        markers: Int = 1,
        hand: List<String> = emptyList(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val capitalBoard =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        val board =
            CatalogConfiguration(3)
                .provinces
                .filter { !it.isSatellite && it.id.value !in capitals }
                .take(markers - 1)
                .fold(capitalBoard) { current, province ->
                    current.placeMarker(province.id, p1).board
                }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = stage,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = cash,
                                stockTokens = stock,
                                poolTokens = 36 - stock,
                                advances =
                                    PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) }),
                                hand =
                                    if (id == p1) {
                                        hand.mapIndexed { cardIndex, card ->
                                            HandCard("held-$cardIndex", CardData.definition(card))
                                        }
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = p1,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
        )
    }
}
