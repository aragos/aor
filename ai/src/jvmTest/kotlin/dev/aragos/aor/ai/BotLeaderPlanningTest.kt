package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BotLeaderPlanningTest {
    @Test
    fun anImpossibleDiscountDoesNotOutweighKeepingItsFutureOption() {
        val observation = observation()

        val command = choose(observation)

        assertThat(command).isEqualTo("pass")
    }

    @Test
    fun aDiscountThatUnlocksThePreferredPurchaseCanBeUsed() {
        val observation = observation(with = listOf(advance("N", cost = 10, value = 15.0)))

        val command = choose(observation)

        assertThat(command).isEqualTo("play hand-1")
    }

    @Test
    fun anAffordableDiscountIsWorthlessWhenAnotherPurchaseDominatesBothPlans() {
        val preferred = advance("R", cost = 40, value = 30.0)
        val observation =
            observation(
                without = listOf(preferred, advance("N", cost = 30, value = 2.0)),
                with = listOf(preferred, advance("N", cost = 10, value = 12.0)),
                cash = 45,
            )

        val command = choose(observation)

        assertThat(command).isEqualTo("pass")
    }

    @Test
    fun theSharedBeamRecognizesADiscountThatMakesTwoPurchasesFit() {
        val preferred = advance("R", cost = 40, value = 30.0)
        val observation =
            observation(
                without = listOf(preferred, advance("N", cost = 25, value = 0.0)),
                with = listOf(preferred, advance("N", cost = 5, value = 20.0)),
                cash = 45,
            )

        val command = choose(observation)

        assertThat(command).isEqualTo("play hand-1")
    }

    @Test
    fun aWorthwhileCashCardSettlesTheBudgetBeforeCommittingTheLeader() {
        val observation =
            observation(
                with = listOf(advance("N", cost = 10, value = 40.0)),
                cashCardValue = 5.0,
            )

        val command = choose(observation)

        assertThat(command).isEqualTo("play hand-2 commodity stone")
    }

    @Test
    fun anUnwantedCashCardDoesNotDistractFromAUsefulLeader() {
        val observation =
            observation(
                with = listOf(advance("N", cost = 10, value = 40.0)),
                cashCardValue = -5.0,
            )

        val command = choose(observation)

        assertThat(command).isEqualTo("play hand-1")
    }

    @Test
    fun shippingUsesTheExistingScorerAndItsSelectedRemainingBudget() {
        val initial = observation(with = listOf(advance("N", cost = 10, value = 40.0)), cash = 15)
        val planning =
            checkNotNull(initial.leaderPlanning) { "The fixture requires leader forecasts." }
        val shippingCandidate =
            BotCandidate("upgrade-ships", mapOf("utility" to 3.0), cashCost = 10)
        val updated =
            planning.copy(
                candidates = planning.candidates + shippingCandidate,
                forecasts =
                    planning.forecasts.mapIndexed { index, forecast ->
                        if (index == 0)
                            forecast.copy(
                                candidates = forecast.candidates + planning.candidates.size
                            )
                        else forecast
                    } + BotPurchaseForecast(BotPurchaseForecastStage.ADVANCES, 5, 0.0, listOf(0)),
                leaders =
                    planning.leaders.mapValues { (_, leader) ->
                        // The actual shipping choice leaves too little cash for either advance
                        // plan.
                        leader.copy(
                            withoutLeader =
                                BotLeaderContinuation(0, mapOf("pass" to 1, "upgrade-ships" to 3)),
                            withLeader =
                                BotLeaderContinuation(0, mapOf("pass" to 2, "upgrade-ships" to 3)),
                        )
                    },
            )

        val command = choose(initial.copy(leaderPlanning = updated))

        assertThat(command).isEqualTo("pass")
    }

    @Test
    fun immediateRebatesAreScoredOnceWhenNeitherPlanUsesACredit() {
        val initial = observation()
        val observation =
            initial.copy(
                candidates =
                    initial.candidates.map {
                        if (it.command == "play hand-1") it.copy(features = mapOf("cash" to 20.0))
                        else it
                    },
                leaderPlanning =
                    checkNotNull(initial.leaderPlanning) {
                            "The fixture requires leader forecasts."
                        }
                        .let { planning ->
                            planning.copy(
                                leaders =
                                    planning.leaders.mapValues { (_, leader) ->
                                        leader.copy(retainedDiscount = 0.0)
                                    }
                            )
                        },
            )

        val scores = correctedScores(observation)

        assertThat(scores["play hand-1"]).isEqualTo(20.0)
    }

    @Test
    fun finalCardPlayHasNoPurchaseOrRetentionValue() {
        val initial = observation()
        val observation =
            initial.copy(
                leaderPlanning =
                    BotLeaderPlanning(
                        emptyList(),
                        emptyList(),
                        mapOf("play hand-1" to BotLeaderAlternative(null, null, 0.0)),
                        emptySet(),
                    )
            )

        val scores = correctedScores(observation)

        assertThat(scores["play hand-1"]).isEqualTo(2.0)
    }

    @Test
    fun absentZeroAndNonUnitGatesKeepEveryLegacyChoice() {
        val observation = observation(cashCardValue = 5.0)
        for (weight in listOf(null, 0.0, 0.5, 2.0)) {
            val weights =
                model.weights - BotLeaderTiming.GATE +
                    (weight?.let { mapOf(BotLeaderTiming.GATE to it) } ?: emptyMap())
            for (difficulty in BotDifficulty.entries) {
                val current =
                    BotPolicy.choose(observation, difficulty, 17, BotModel(weights = weights))
                val legacy =
                    BotPolicy.choose(
                        observation.copy(leaderPlanning = null),
                        difficulty,
                        17,
                        BotModel(weights = weights),
                    )
                assertThat(current).isEqualTo(legacy)
            }
        }
    }

    @Test
    fun anActiveGateWithoutOptionalMetadataKeepsLegacyChoices() {
        val observation = observation().copy(leaderPlanning = null)

        val command = choose(observation)

        assertThat(command).isEqualTo("play hand-1")
    }

    @Test
    fun malformedForecastIndicesAreRejectedByTheirOwner() {
        val planning =
            checkNotNull(observation().leaderPlanning) { "The fixture requires leader forecasts." }

        assertFailsWith<IllegalArgumentException> {
            planning.copy(
                forecasts =
                    planning.forecasts.map {
                        it.copy(candidates = listOf(planning.candidates.size))
                    }
            )
        }
    }

    @Test
    fun anAdvancePlanningStepCannotReferToAnotherForecastsCandidate() {
        val planning =
            checkNotNull(observation().leaderPlanning) { "The fixture requires leader forecasts." }

        assertFailsWith<IllegalArgumentException> {
            planning.copy(
                forecasts =
                    planning.forecasts.mapIndexed { index, forecast ->
                        if (index != 1) forecast
                        else
                            forecast.copy(
                                advancePlanning =
                                    BotAdvancePlanning(
                                        mapOf(
                                            "buy-advance N" to
                                                BotAdvanceStep(AdvanceId.WRITTEN_RECORD, 0)
                                        )
                                    )
                            )
                    }
            )
        }
    }

    @Test
    fun aForecastRejectsANonUnitReserveBasisBeforeAnyModelCanUseIt() {
        assertFailsWith<IllegalArgumentException> {
            BotPurchaseForecast(
                BotPurchaseForecastStage.ADVANCES,
                20,
                1.0,
                listOf(0),
                reserveShortfallBasis = mapOf("reserveShortfall" to 2.0),
            )
        }
    }

    @Test
    fun differentPostPlayBudgetsCannotBeComparedAsALeaderBenefit() {
        val planning =
            checkNotNull(observation().leaderPlanning) { "The fixture requires leader forecasts." }
        val baseline = planning.forecasts.first()
        val differentBudgets =
            listOf(
                baseline.copy(cash = baseline.cash + 1),
                baseline.copy(stabilization = baseline.stabilization + 1.0),
            )

        for (discounted in differentBudgets) {
            assertFailsWith<IllegalArgumentException> {
                planning.copy(
                    forecasts = planning.forecasts + discounted,
                    leaders =
                        planning.leaders.mapValues { (_, leader) ->
                            leader.copy(
                                withLeader =
                                    BotLeaderContinuation(
                                        planning.forecasts.size,
                                        mapOf("pass" to 2),
                                    )
                            )
                        },
                )
            }
        }
    }

    private val model =
        BotModel(
            weights =
                mapOf(
                    "discount" to 1.0,
                    "cash" to 1.0,
                    "utility" to 1.0,
                    BotLeaderTiming.GATE to 1.0,
                    BotLeaderTiming.RETENTION to 0.5,
                )
        )

    private fun choose(observation: BotObservation): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

    private fun correctedScores(observation: BotObservation): Map<String, Double> =
        BotLeaderTiming.scores(
            observation,
            model,
            observation.candidates.associate { it.command to BotPolicy.score(it, model) },
        )

    private fun advance(letter: String, cost: Int, value: Double): BotCandidate =
        BotCandidate(
            "buy-advance $letter",
            mapOf("utility" to value),
            cashCost = cost,
            group = "advance.$letter",
            family = "advance",
        )

    private fun observation(
        without: List<BotCandidate> = emptyList(),
        with: List<BotCandidate> = emptyList(),
        cash: Int = 20,
        cashCardValue: Double? = null,
    ): BotObservation {
        val pass = BotCandidate("pass", emptyMap(), family = "pass")
        val pool = listOf(pass) + without + with
        val withoutIndices = listOf(0) + without.indices.map { it + 1 }
        val withIndices = listOf(0) + with.indices.map { it + without.size + 1 }
        val cashCard = cashCardValue?.let {
            BotCandidate(
                "play hand-2 commodity stone",
                mapOf("utility" to it),
                group = "card.hand-2",
                family = "play",
            )
        }
        return BotObservation(
            playerId = "p1",
            decision = "play-cards",
            context = emptyMap(),
            availableCash = cash,
            availableTokens = 0,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            candidates =
                listOf(
                    pass,
                    BotCandidate(
                        "play hand-1",
                        mapOf("discount" to 20.0, "cash" to 2.0),
                        group = "card.hand-1",
                        family = "play",
                    ),
                ) + listOfNotNull(cashCard),
            leaderPlanning =
                BotLeaderPlanning(
                    pool,
                    listOf(
                        BotPurchaseForecast(
                            BotPurchaseForecastStage.SHIPPING,
                            cash,
                            0.0,
                            listOf(0),
                        ),
                        BotPurchaseForecast(
                            BotPurchaseForecastStage.ADVANCES,
                            cash,
                            0.0,
                            withoutIndices,
                        ),
                        BotPurchaseForecast(
                            BotPurchaseForecastStage.ADVANCES,
                            cash,
                            0.0,
                            withIndices,
                        ),
                    ),
                    mapOf(
                        "play hand-1" to
                            BotLeaderAlternative(
                                BotLeaderContinuation(0, mapOf("pass" to 1)),
                                BotLeaderContinuation(0, mapOf("pass" to 2)),
                                20.0,
                            )
                    ),
                    setOfNotNull(cashCard?.command),
                ),
        )
    }
}
