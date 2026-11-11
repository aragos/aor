package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardPolicy
import kotlin.test.Test

class JointStateActorTest {
    @Test
    fun zeroResidualGreedyCardChoiceEqualsTheDistinctOrdinaryPrior() {
        JointStateFixture().use { fixture ->
            val input = fixture.input
            val state = jointStateFixture()
            val actor = PlayerId("p1")
            val original = BotObservations.from(state, actor)
            val observation =
                original.copy(
                    leaderPlanning = null,
                    candidates =
                        original.candidates
                            .filter { it.command in setOf("pass", "play held") }
                            .map {
                                it.copy(
                                    features =
                                        if (it.command == "pass") {
                                            emptyMap()
                                        } else {
                                            mapOf("points" to 1.0)
                                        }
                                )
                            },
                )
            val records = mutableListOf<CardDecisionRecord>()
            val controller =
                input.bundle.controller(
                    input.purchaseBaseline,
                    cardRecord = records::add,
                    ordinary = input.baseline.model,
                )
            val expected =
                BotPolicy.choose(observation, BotDifficulty.HARD, 71, input.baseline.model)
            val purchasePriorChoice =
                BotPolicy.choose(observation, BotDifficulty.HARD, 71, input.purchaseBaseline.model)

            controller.choose(SimulationDecision(4, 0, 71, state.viewForPlayer(actor), observation))
            val recorded = records.single().observation
            val evaluation =
                CardPolicy.evaluate(
                    recorded,
                    requireNotNull(input.bundle.card) { "Fixture card actor required." }.model,
                )
            val greedy = evaluation.probabilities.indices.maxBy { evaluation.probabilities[it] }

            assertThat(expected).isNotEqualTo(purchasePriorChoice)
            assertThat(recorded.actions[recorded.baselineIndex].command).isEqualTo(expected)
            assertThat(recorded.actions[greedy].command).isEqualTo(expected)
            assertThat(evaluation.probabilities[greedy]).isWithin(1e-12).of(.97)
        }
    }

    @Test
    fun distinctOrdinaryModelCannotChangeTheFrozenPurchaseSampler() {
        JointStateFixture().use { fixture ->
            val input = fixture.input
            val actor = PlayerId("p1")
            val state =
                jointStateFixture()
                    .copy(
                        stage = Stage.PURCHASE,
                        flow =
                            TurnFlow(
                                actor = actor,
                                purchaseStep = PurchaseStep.ADVANCES,
                                phaseStarted = true,
                                phaseBookkeepingApplied = true,
                            ),
                    )
            val ordinary = BotObservations.from(state, actor)
            val advance = ordinary.candidates.first { it.family == "advance" }
            val observation =
                ordinary.copy(
                    candidates =
                        listOf(
                            advance.copy(features = mapOf("points" to 1.0)),
                            ordinary.candidates
                                .single { it.command == "pass" }
                                .copy(features = emptyMap()),
                        ),
                    advancePlanning = null,
                    reserveShortfallBasis = null,
                )
            val controller =
                input.bundle.controller(input.purchaseBaseline, ordinary = input.baseline.model)
            val purchase =
                PurchaseActorController(input.purchaseBaseline.model, input.bundle.purchase.model)
            val decisions =
                listOf(0, 1, -1, 71, Int.MIN_VALUE, Int.MAX_VALUE).map { seed ->
                    SimulationDecision(4, 0, seed, state.viewForPlayer(actor), observation)
                }

            assertThat(decisions.map(controller::choose)).isEqualTo(decisions.map(purchase::choose))
        }
    }

    @Test
    fun printedWordGrossReceiptIsSeparateFromProtectionCash() {
        val state = jointStateFixture(printedWord = true)
        val actor = PlayerId("p1")
        val decision =
            SimulationDecision(
                4,
                0,
                71,
                state.viewForPlayer(actor),
                BotObservations.from(state, actor),
            )

        val receipt =
            requireNotNull(JointDecisionEvidence.from(decision, "play held protect 20")) {
                    "Leader receipt required."
                }
                .leader

        assertThat(receipt).isEqualTo(JointLeaderReceipt("leonardo-da-vinci", true, 20, 20))
    }

    @Test
    fun physicalCardCoverageCombinesProtectionVariantsWithoutChangingProbability() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val state = jointStateFixture(printedWord = true)
            val actor = PlayerId("p1")
            val records = mutableListOf<CardDecisionRecord>()
            input.bundle
                .controller(input.baseline, cardRecord = records::add)
                .choose(
                    SimulationDecision(
                        0,
                        0,
                        71,
                        state.viewForPlayer(actor),
                        BotObservations.from(state, actor),
                    )
                )
            val game =
                PurchaseJson.mapper.valueToTree<JsonNode>(
                    fixture.syntheticGame().copy(cardDecisions = records)
                )
            val coverage =
                PurchaseJson.mapper.valueToTree<JsonNode>(
                    JointExplorationCoverage.project(
                        game,
                        requireNotNull(input.bundle.card) { "Fixture card actor required." }.model,
                    )
                )
            val groups = coverage["rows"][0]["groups"]
            val card = groups.single { it["reference"].asText() == "held" }

            assertThat(card["variants"].intValue()).isGreaterThan(1)
            assertThat(groups.sumOf { it["probability"].doubleValue() }).isWithin(1e-12).of(1.0)
            assertThat(coverage["rows"][0]["offeredPhysicalCards"].intValue()).isEqualTo(1)
            assertThat(coverage["independentWorlds"].intValue()).isEqualTo(1)
        }
    }
}
