package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import kotlin.test.Test

class BotCompleteTokenAmountsTest {
    private val player = PlayerId("p1")
    private val ids = (1..4).map { PlayerId("p$it") }

    @Test
    fun anExactPeacefulBudgetCanBeatBothUnderbuyingAndSurplusTokens() {
        val state = fixture()
        val observation = BotObservations.from(state, player)
        val view = state.viewForDecision(player)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model())
        val quote = view.tokenPurchaseOffers.single { "buy-tokens ${it.amount}" == choice }
        val plan = BotTokenPlanProjection(view, player, 0.0).plan(quote.poolAfter)

        assertThat(choice).isEqualTo("buy-tokens 16")
        assertThat(quote.cashAfter).isEqualTo(24)
        assertThat(plan.destinations).hasSize(5)
        assertThat(plan.commitment).isEqualTo(16)
    }

    @Test
    fun missingAndZeroCompleteAmountGatesKeepTheSixProposalChoice() {
        val observation = BotObservations.from(fixture(), player)
        val original = model().copy(weights = model().weights - BotTokenValue.COMPLETE_AMOUNTS_GATE)
        val disabled =
            original.copy(weights = original.weights + (BotTokenValue.COMPLETE_AMOUNTS_GATE to 0.0))

        val choices =
            listOf(original, disabled).map {
                BotPolicy.choose(observation, BotDifficulty.HARD, 0, it)
            }

        assertThat(choices).containsExactly("buy-tokens 10", "buy-tokens 10")
    }

    @Test
    fun theCompleteAmountGateDoesNotEnableADisabledHead() {
        val observation = BotObservations.from(fixture(), player)
        val disabled =
            model()
                .copy(
                    weights =
                        model().weights +
                            mapOf(
                                BotTokenValue.GATE to 0.0,
                                BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0,
                            )
                )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, disabled)

        assertThat(choice).isEqualTo("buy-tokens 10")
    }

    @Test
    fun emptyPeacefulOpportunityCanFavorKeepingTheCash() {
        val state =
            fixture(
                occupiedNeighbours = listOf("bruges", "strasbourg", "st-malo", "dijon", "bordeaux")
            )
        val observation = BotObservations.from(state, player)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model())

        assertThat(choice).isEqualTo("buy-tokens 0")
    }

    @Test
    fun scarceCashCanOutweighAnotherReachablePeacefulMarket() {
        val observation = BotObservations.from(fixture(cash = 6), player)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model())

        assertThat(choice).isEqualTo("buy-tokens 2")
    }

    @Test
    fun completeAmountsDoNotAddOrderPaymentsAbovePhysicalStock() {
        val observation = BotObservations.from(fixture(cash = 100), player)
        val quantity =
            BotTokenValue.withWeights(
                model(),
                BotTokenValue.featureNames.map {
                    if (it == "planning.tokenValue.v1.signedAmount") 1.0 else 0.0
                },
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, quantity)

        assertThat(choice).isEqualTo("buy-tokens 36")
    }

    @Test
    fun anExactScoreTieKeepsTheBaseline() {
        val observation = BotObservations.from(fixture(), player)
        val peacefulMargin =
            model().copy(weights = model().weights + (BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0))
        val neutral =
            BotTokenValue.withWeights(peacefulMargin, List(BotTokenValue.featureNames.size) { 0.0 })

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, neutral)

        assertThat(choice).isEqualTo("buy-tokens 10")
    }

    @Test
    fun fittingHeadWeightsPreservesTheIndependentControllerGate() {
        val peacefulMargin =
            model().copy(weights = model().weights + (BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0))
        val fitted =
            BotTokenValue.withWeights(peacefulMargin, List(BotTokenValue.featureNames.size) { 0.0 })

        assertThat(fitted.weights).containsEntry(BotTokenValue.COMPLETE_AMOUNTS_GATE, 1.0)
        assertThat(fitted.weights).containsEntry(BotTokenValue.PEACEFUL_MARGIN_GATE, 1.0)
    }

    @Test
    fun peacefulMarginCanSpendOneMoreDollarToCompleteAnotherMarket() {
        val observation = smallIncrement()
        val peacefulMargin =
            model().copy(weights = model().weights + (BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0))

        val choice = BotPolicy.chooseCandidate(observation, BotDifficulty.HARD, 0, peacefulMargin)

        assertThat(choice.command).isEqualTo("buy-tokens 9")
        assertThat(observation.availableCash - choice.cashCost).isEqualTo(24)
    }

    @Test
    fun missingAndDisabledPeacefulMarginGatesStillRequireTheLegacyMargin() {
        val observation = smallIncrement()
        val disabled =
            model().copy(weights = model().weights + (BotTokenValue.PEACEFUL_MARGIN_GATE to 0.0))

        val choices =
            listOf(model(), disabled).map {
                BotPolicy.choose(observation, BotDifficulty.HARD, 0, it)
            }

        assertThat(choices).containsExactly("buy-tokens 8", "buy-tokens 8")
    }

    @Test
    fun peacefulMarginDoesNotEnableTheCompleteAmountSet() {
        val observation = smallIncrement()
        val originalAmounts =
            model()
                .copy(
                    weights =
                        model().weights +
                            mapOf(
                                BotTokenValue.COMPLETE_AMOUNTS_GATE to 0.0,
                                BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0,
                            )
                )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, originalAmounts)

        assertThat(choice).isEqualTo("buy-tokens 8")
    }

    @Test
    fun contestedOpportunityAloneKeepsTheOrdinaryMargin() {
        val state =
            fixture(
                occupiedNeighbours = listOf("bruges", "strasbourg", "st-malo", "dijon", "bordeaux")
            )
        val observation = BotObservations.from(state, player)
        val enabled =
            model().copy(weights = model().weights + (BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0))
        val contestedOnly =
            BotTokenValue.withWeights(
                enabled,
                BotTokenValue.featureNames.map {
                    if (it == "planning.tokenValue.v1.affordableContested") 0.02 else 0.0
                },
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, contestedOnly)

        assertThat(choice).isEqualTo("buy-tokens 10")
    }

    @Test
    fun aSmallerEligibleGainIsConsideredWhenTheBestScoreFailsItsMargin() {
        val original = smallIncrement()
        val contested = "planning.tokenValue.v1.affordableContested"
        val observation =
            original.copy(
                candidates =
                    original.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features +
                                    (contested to
                                        if (candidate.command == "buy-tokens 16") 0.1 else 0.0)
                        )
                    }
            )
        val enabled =
            model()
                .copy(
                    weights =
                        model().weights +
                            mapOf(BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0, contested to 0.24)
                )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, enabled)

        // Sixteen has the higher score, but adds unused tokens and does not clear 0.02.
        assertThat(choice).isEqualTo("buy-tokens 9")
    }

    @Test
    fun continuationCollectionRetainsItsOriginalSixAlternatives() {
        val observation = BotObservations.from(fixture(), player)
        val baseline = observation.candidates.single { it.command == "buy-tokens 10" }

        val alternatives = BotTokenValue.alternatives(observation, baseline)

        assertThat(alternatives.map(BotCandidate::command))
            .containsExactly(
                "buy-tokens 10",
                "buy-tokens 0",
                "buy-tokens -4",
                "buy-tokens 5",
                "buy-tokens 18",
                "buy-tokens 36",
            )
            .inOrder()
    }

    @Test
    fun completeAmountsRetainTheExistingSignedOrderAlternative() {
        val observation = BotObservations.from(fixture(), player)
        val order =
            BotTokenValue.withWeights(
                model(),
                BotTokenValue.featureNames.map {
                    if (
                        it == "planning.tokenValue.v1.signedAmount" ||
                            it == "planning.tokenValue.v1.cost"
                    ) {
                        -1.0
                    } else {
                        0.0
                    }
                },
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, order)

        assertThat(choice).isEqualTo("buy-tokens -4")
    }

    private fun model(): BotModel =
        BotModel(
            weights =
                mapOf(
                    "tokenPreference" to 1.0,
                    BotTokenValue.GATE to 1.0,
                    BotTokenValue.COMPLETE_AMOUNTS_GATE to 1.0,
                    "planning.tokenValue.v1.costFraction" to -0.05,
                    BotTokenPlanFeature.INCOME.key to 0.32,
                    BotTokenPlanFeature.UNCOMMITTED_TOKENS.key to -0.05,
                )
        )

    private fun smallIncrement(): BotObservation =
        BotObservations.from(
            fixture(cash = 33, occupiedNeighbours = listOf("dijon", "bordeaux")),
            player,
        )

    private fun fixture(
        cash: Int = 40,
        occupiedNeighbours: List<String> = emptyList(),
    ): GameState {
        val capitals = listOf("paris", "genoa", "barcelona", "venice")
        val capitalsBoard =
            ids.zip(capitals).fold(BoardState()) { board, (id, capital) ->
                board.placeMarker(ProvinceId(capital), id).board
            }
        val board =
            occupiedNeighbours.fold(capitalsBoard) { current, province ->
                current.placeMarker(ProvinceId(province), PlayerId("p2")).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, player),
            stage = Stage.TOKEN_PURCHASE,
            turnNumber = 1,
            board = board,
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = if (id == player) cash else 40,
                            )
                    }
                    .toMap(),
            flow = TurnFlow(phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
