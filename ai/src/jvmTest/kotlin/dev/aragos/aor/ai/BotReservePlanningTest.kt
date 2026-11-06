package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test

class BotReservePlanningTest {
    private val model =
        BotModel(
            weights =
                mapOf(
                    "points" to 1.0,
                    "reserveShortfall" to -1.0,
                    "count.4.reserveShortfall" to -4.0,
                )
        )
    private val basis = mapOf("reserveShortfall" to 1.0, "count.4.reserveShortfall" to 1.0)

    @Test
    fun hardDoesNotReplaceTheContextualReservePenaltyWithTheGlobalWeight() {
        val observation = singlePurchaseObservation()

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(choice).isEqualTo("pass")
    }

    @Test
    fun oldObservationsWithoutReserveMetadataRetainTheirPriorPlanningBehavior() {
        val observation = singlePurchaseObservation().copy(reserveShortfallBasis = null)

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(choice).isEqualTo("buy-advance A")
    }

    @Test
    fun combinedPurchasesUseTheReserveBasisEvenWhenEverySinglePurchaseHasNoShortfall() {
        val observation =
            observation(
                candidates =
                    listOf(
                        candidate("A", cost = 70, points = 100.0),
                        candidate("B", cost = 40, points = 70.0),
                        candidate("C", cost = 30, points = 60.0),
                        BotCandidate("pass", emptyMap()),
                    ),
                reserve = 30.0,
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(choice).isEqualTo("buy-advance B")
    }

    @Test
    fun anEndgameBasisKeepsItsFractionalCoefficient() {
        val endgameModel =
            BotModel(
                weights =
                    mapOf(
                        "points" to 1.0,
                        "reserveShortfall" to -1.0,
                        "endgame.reserveShortfall" to -4.0,
                    )
            )
        val observation =
            observation(
                    candidates =
                        listOf(
                            candidate(
                                "A",
                                cost = 90,
                                points = 20.0,
                                features =
                                    mapOf(
                                        "reserveShortfall" to 10.0,
                                        "endgame.reserveShortfall" to 5.0,
                                    ),
                            ),
                            candidate("B", cost = 0, points = -100.0),
                            BotCandidate("pass", emptyMap()),
                        ),
                    reserve = 20.0,
                )
                .copy(
                    reserveShortfallBasis =
                        mapOf("reserveShortfall" to 1.0, "endgame.reserveShortfall" to 0.5)
                )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 17, endgameModel)

        assertThat(choice).isEqualTo("pass")
    }

    private fun singlePurchaseObservation(): BotObservation =
        observation(
            candidates =
                listOf(
                    candidate(
                        "A",
                        cost = 90,
                        points = 40.0,
                        features =
                            mapOf(
                                "reserveShortfall" to 10.0,
                                "count.4.reserveShortfall" to 10.0,
                            ),
                    ),
                    candidate("B", cost = 0, points = -100.0),
                    BotCandidate("pass", emptyMap()),
                ),
            reserve = 20.0,
        )

    private fun candidate(
        letter: String,
        cost: Int,
        points: Double,
        features: Map<String, Double> = emptyMap(),
    ): BotCandidate =
        BotCandidate(
            command = "buy-advance $letter",
            features = mapOf("points" to points) + features,
            cashCost = cost,
            group = "advance.$letter",
            family = "advance",
        )

    private fun observation(candidates: List<BotCandidate>, reserve: Double): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "purchase-advances",
            context = mapOf("stabilization" to reserve),
            availableCash = 100,
            availableTokens = 0,
            candidates = candidates,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            reserveShortfallBasis = basis,
        )
}
