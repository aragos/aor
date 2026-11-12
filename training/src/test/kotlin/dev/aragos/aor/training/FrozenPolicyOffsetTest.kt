package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FrozenPolicyOffsetTest {
    @Test
    fun rebasedForkAndUninterruptedContinuationKeepExactPlayerDecisionSeeds() {
        ArenaPurchaseFixture().use { fixture ->
            val session = ExperimentSession.start(fixture.configuration, 51L)
            session.apply(session.actors().associateWith { "bid-capital 0" })
            val uninterrupted = session.fork(seed = 991L)
            val rebased = ExperimentSession(session.state, 991L)
            val ordinary =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    emptyMap(),
                    51,
                    Long.MAX_VALUE,
                )
            val offset =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    emptyMap(),
                    51,
                    Long.MAX_VALUE,
                    decisionOffset = session.decisions,
                )

            repeat(8) {
                val originalCommands =
                    ordinary.commands(
                        uninterrupted,
                        uninterrupted.actors().associateWith(uninterrupted::observation),
                    )
                val forkCommands =
                    offset.commands(rebased, rebased.actors().associateWith(rebased::observation))
                assertThat(forkCommands).isEqualTo(originalCommands)
                uninterrupted.apply(originalCommands)
                rebased.apply(forkCommands)
            }

            assertThat(rebased.state).isEqualTo(uninterrupted.state)
        }
    }

    @Test
    fun sealedControllersReceiveAbsoluteNumbersAndOneBatchStart() {
        ArenaPurchaseFixture().use { fixture ->
            val session = ExperimentSession.start(fixture.configuration, 51L)
            val received = mutableListOf<SimulationDecision>()
            val controllers =
                (0..3).associateWith {
                    SimulationController { decision ->
                        received.add(decision)
                        "bid-capital 0"
                    }
                }
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    controllers,
                    71,
                    Long.MAX_VALUE,
                    decisionOffset = 37,
                )

            continuation.commands(session, session.actors().associateWith(session::observation))

            assertThat(received.map { it.number }).containsExactly(37, 38, 39, 40).inOrder()
            assertThat(received.map { it.batchStartNumber }.distinct()).containsExactly(37)
            assertThat(received.map { it.seed })
                .containsExactlyElementsIn((0..3).map { simulationPolicySeed(71, 37, it) })
                .inOrder()
        }
    }

    @Test
    fun offsetCannotHideExhaustedAbsoluteDecisionBudget() {
        ArenaPurchaseFixture().use { fixture ->
            val session = ExperimentSession.start(fixture.configuration, 51L)
            session.apply(session.actors().associateWith { "bid-capital 0" })
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    emptyMap(),
                    51,
                    Long.MAX_VALUE,
                    decisionOffset = 11996,
                )

            assertFailsWith<IllegalStateException> { continuation.check(session) }
        }
    }
}
