package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AcquisitionRivalProfileTest {
    private val profile = AcquisitionRivalProfile.TURN_ROUTE_HALF_1

    @Test
    fun frozenHashVectorsBindSeparateRouteCoins() {
        assertThat(profile.buys(1, 1, 5, AcquisitionRoute.CASH)).isFalse()
        assertThat(profile.buys(1, 1, 5, AcquisitionRoute.EXPANSION)).isTrue()
    }

    @Test
    fun unrelatedCoinCallsCannotChangeAnExistingCoin() {
        val original = profile.buys(2, 1, 5, AcquisitionRoute.CASH)
        (0..10).forEach { seed -> profile.buys(seed, 3, 2, AcquisitionRoute.EXPANSION) }

        assertThat(profile.buys(2, 1, 5, AcquisitionRoute.CASH)).isEqualTo(original)
    }

    @Test
    fun controlledSeatKeepsTheExactControllerObject() {
        val ordinary = SimulationController { "controlled" }

        val controllers = profile.controllers(2, 2, mapOf(2 to ordinary))

        assertThat(controllers[2]).isSameInstanceAs(ordinary)
        assertThat(controllers.keys).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun trueCoinUsesEveryLegalBuyOpportunityInTheTurnWithoutFlags() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = decision(fixture, AcquisitionRoute.EXPANSION)
            val controller = rival(1, SimulationController { error("Buy must precede fallback.") })

            val choices = (0..4).map { offset -> controller.choose(decision.copy(number = offset)) }

            assertThat(choices).containsExactlyElementsIn(List(5) { "buy-expansion-card" })
        }
    }

    @Test
    fun chanceAndPolicyDecisionSeedsDoNotChangeTheRivalTurnCoin() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = decision(fixture, AcquisitionRoute.CASH)
            val controller = rival(2, SimulationController { "fallback" })

            val choices =
                listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE).map { policySeed ->
                    controller.choose(decision.copy(seed = policySeed, batchStartNumber = 900))
                }

            assertThat(choices).containsExactly("buy-card", "buy-card", "buy-card")
        }
    }

    @Test
    fun falseCoinDelegatesTheUnmodifiedDecision() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = decision(fixture, AcquisitionRoute.CASH)
            var observed: SimulationDecision? = null
            val controller =
                rival(
                    0,
                    SimulationController {
                        observed = it
                        "sampled-fallback"
                    },
                )

            val chosen = controller.choose(decision)

            assertThat(chosen).isEqualTo("sampled-fallback")
            assertThat(observed).isSameInstanceAs(decision)
        }
    }

    @Test
    fun trueCoinCannotManufactureAnUnavailableBuy() {
        ArenaPurchaseFixture().use { fixture ->
            val original = decision(fixture, AcquisitionRoute.CASH)
            val unavailable =
                original.copy(
                    observation =
                        original.observation.copy(
                            candidates = listOf(BotCandidate("pass", emptyMap()))
                        )
                )

            val chosen = rival(2, SimulationController { "pass" }).choose(unavailable)

            assertThat(chosen).isEqualTo("pass")
        }
    }

    @Test
    fun unownedDecisionRetainsNullOrdinaryFallback() {
        ArenaPurchaseFixture().use { fixture ->
            val original = decision(fixture, AcquisitionRoute.CASH)
            val ordinary =
                object : SimulationController {
                    override fun controls(decision: String): Boolean = decision == "expand"

                    override fun choose(decision: SimulationDecision): String =
                        error("A controller must not receive a decision it does not own.")
                }

            val chosen = rival(0, ordinary).choose(original)

            assertThat(chosen).isNull()
        }
    }

    @Test
    fun unrelatedPurchasePhaseKeepsExistingControllerChoice() {
        ArenaPurchaseFixture().use { fixture ->
            val original = decision(fixture, AcquisitionRoute.CASH)
            val purchase =
                original.copy(
                    observation = original.observation.copy(decision = "purchase-advances")
                )

            val chosen = rival(2, SimulationController { "sampled-purchase" }).choose(purchase)

            assertThat(chosen).isEqualTo("sampled-purchase")
        }
    }

    @Test
    fun newWrapperForSameWorldReproducesPreviousChoices() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = decision(fixture, AcquisitionRoute.CASH)
            val fallback = SimulationController { "pass" }

            val first = rival(2, fallback).choose(decision)
            val second = rival(2, fallback).choose(decision)

            assertThat(first).isEqualTo(second)
        }
    }

    @Test
    fun controllerCannotBeRoutedToAnotherSeat() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = decision(fixture, AcquisitionRoute.CASH).copy(seat = 2)

            assertFailsWith<IllegalArgumentException> { rival(2, null).choose(decision) }
        }
    }

    @Test
    fun ordinaryCardPhaseDoesNotGainAnOverlayOwner() {
        val controller = rival(2, null)

        assertThat(controller.controls("play-cards")).isFalse()
        assertThat(controller.controls("buy-card")).isTrue()
        assertThat(controller.controls("expand")).isTrue()
    }

    private fun rival(seed: Int, fallback: SimulationController?): SimulationController =
        requireNotNull(
            profile.controllers(0, seed, listOfNotNull(fallback?.let { 1 to it }).toMap())[1]
        ) {
            "The rival controller must exist."
        }

    private fun decision(
        fixture: ArenaPurchaseFixture,
        route: AcquisitionRoute,
    ): SimulationDecision {
        val session = ExperimentSession.start(fixture.configuration, 51L)
        val actor = session.actors().first()
        return SimulationDecision(
            number = 0,
            seat = 1,
            seed = 100,
            view = session.state.viewForPlayer(actor).copy(turnNumber = 5),
            observation =
                session
                    .observation(actor)
                    .copy(
                        decision = route.decision,
                        candidates =
                            listOf(
                                BotCandidate("pass", emptyMap()),
                                BotCandidate(route.buyCommand, emptyMap()),
                            ),
                    ),
        )
    }
}
