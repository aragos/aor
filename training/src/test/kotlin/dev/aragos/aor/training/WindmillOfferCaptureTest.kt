package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardDecision
import dev.aragos.aor.CardData
import dev.aragos.aor.HandCard
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotModels
import kotlin.test.Test

/** One deterministic offer decision and random trade; no complete games or counterfactual tails. */
class WindmillOfferCaptureTest {
    @Test
    fun recordingTheImmediateNormalOfferPreservesExactTransitionAndAbsoluteSeed() {
        WindmillFixture().use { fixture ->
            val state = offerState()
            val actual = ExperimentSession(state, 71, recordEntries = true)
            val expected = ExperimentSession(state, 71, recordEntries = true)
            val recorded = mutableListOf<SimulationDecision>()
            val controller = SimulationController { decision ->
                recorded += decision
                "offer-card hand-1"
            }
            val normal =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    mapOf(0 to controller),
                    13,
                    Long.MAX_VALUE,
                    decisionOffset = 43,
                )
            val expectedNormal =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    mapOf(0 to SimulationController { "offer-card hand-1" }),
                    13,
                    Long.MAX_VALUE,
                    decisionOffset = 43,
                )
            val expectedCommands =
                expectedNormal.commands(
                    expected,
                    expected.actors().associateWith(expected::observation),
                )
            expected.apply(expectedCommands)

            val receipt =
                WindmillCollector(fixture.input, Long.MAX_VALUE)
                    .captureImmediateOffer(
                        actual,
                        PlayerId("p1"),
                        "windmill",
                        normal,
                        43,
                    )

            assertThat(actual.state).isEqualTo(expected.state)
            assertThat(actual.entries).isEqualTo(expected.entries)
            assertThat(actual.decisions).isEqualTo(1)
            assertThat(recorded).hasSize(1)
            assertThat(recorded.single().number).isEqualTo(43)
            assertThat(recorded.single().seed).isEqualTo(simulationPolicySeed(13, 43, 0))
            assertThat(receipt.single()["normalCommand"]).isEqualTo("offer-card hand-1")
            assertThat(receipt.single()["absolutePlayerDecision"]).isEqualTo(43)
            val input = receipt.single()["actorInput"] as WindmillActorInput
            assertThat(input.view.hand.map { it.definition.id })
                .containsExactly("leonardo-da-vinci")
            assertThat(input.observation.candidates.map { it.command })
                .contains("offer-card hand-1")
        }
    }

    @Test
    fun passRootDoesNotRecordOrExecuteAnOffer() {
        WindmillFixture().use { fixture ->
            val session = ExperimentSession(offerState(), 71, recordEntries = true)
            val normal =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    emptyMap(),
                    13,
                    Long.MAX_VALUE,
                )
            val receipt =
                WindmillCollector(fixture.input, Long.MAX_VALUE)
                    .captureImmediateOffer(
                        session,
                        PlayerId("p1"),
                        "pass",
                        normal,
                        0,
                    )
            assertThat(receipt).isEmpty()
            assertThat(session.decisions).isEqualTo(0)
        }
    }

    @Test
    fun nonOfferDecisionIsLeftForUnchangedContinuation() {
        WindmillFixture().use { fixture ->
            val session = ExperimentSession(jointStateFixture(), 71, recordEntries = true)
            val normal =
                FrozenPolicyContinuation(
                    List(4) { BotModels.default },
                    emptyMap(),
                    13,
                    Long.MAX_VALUE,
                )
            val receipt =
                WindmillCollector(fixture.input, Long.MAX_VALUE)
                    .captureImmediateOffer(
                        session,
                        PlayerId("p1"),
                        "windmill",
                        normal,
                        0,
                    )
            assertThat(receipt).isEmpty()
            assertThat(session.decisions).isEqualTo(0)
        }
    }

    private fun offerState() =
        jointStateFixture().let { initial ->
            val victim = PlayerId("p2")
            initial.copy(
                stage = Stage.EXPANSION,
                players =
                    initial.players.mapValues { (id, player) ->
                        when (id) {
                            victim ->
                                player.copy(
                                    hand =
                                        listOf(HandCard("hand-2", CardData.definition("cloth-2")))
                                )
                            PlayerId("p1") ->
                                player.copy(
                                    hand = player.hand.map { it.copy(reference = "hand-1") }
                                )
                            else -> player
                        }
                    },
                flow =
                    TurnFlow(
                        actor = PlayerId("p1"),
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                        decisions = listOf(BoardDecision.TradeOffer(PlayerId("p1"), victim)),
                    ),
            )
        }
}
