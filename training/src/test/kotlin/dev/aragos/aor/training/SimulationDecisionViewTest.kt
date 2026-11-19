package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test

class SimulationDecisionViewTest {
    @Test
    fun controllerObservationsMatchTheDecisionOnlyProjectionThroughPlay() {
        val decisions = mutableSetOf<String>()
        val controller =
            object : SimulationController {
                override fun controls(decision: String): Boolean = true

                override fun choose(decision: SimulationDecision): String? = null
            }
        val observer =
            object : SimulationStateObserver {
                override val seat = 0

                override fun chosen(
                    state: GameState,
                    number: Int,
                    observation: BotObservation,
                    command: String,
                ) {
                    val expected = BotObservations.from(state, PlayerId(observation.playerId))
                    assertThat(observation).isEqualTo(expected)
                    decisions.add(observation.decision)
                }
            }

        Simulation()
            .play(
                configuration =
                    TrainingConfiguration(
                        4,
                        RuleOptions(initialCard = InitialCardRule.NO_CRUSADES),
                    ),
                seed = 42012,
                competitors = List(4) { Competitor(BotModels.default, BotDifficulty.HARD) },
                controllers = mapOf(0 to controller),
                stateObserver = observer,
            )

        assertThat(decisions)
            .containsAtLeast("buy-tokens", "play-cards", "purchase-advances", "expand")
    }
}
