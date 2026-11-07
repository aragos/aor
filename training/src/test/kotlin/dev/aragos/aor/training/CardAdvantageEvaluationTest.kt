package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageEvaluationTest {
    @Test
    fun explicitRepeatedPolicyRolesPreserveZeroModelBaselineTerminal() {
        CardAdvantageFixture().use { fixture ->
            val job =
                CardAdvantageEvaluationJob(
                    0,
                    71,
                    2,
                    "candidate",
                    "candidate",
                    List(4) { "candidate" },
                )
            val input = fixture.evaluation(listOf(job))
            val actual = CardAdvantageEvaluator(input, Long.MAX_VALUE).evaluate(0)
            val environment = input.environment
            val prior =
                Simulation()
                    .play(
                        environment.configuration,
                        71,
                        List(4) { Competitor(environment.baseline.model, BotDifficulty.HARD) },
                        controllers =
                            (0..3).associateWith {
                                CardActorController(
                                    environment.baseline.model,
                                    null,
                                    environment.purchase.model,
                                )
                            },
                    )

            assertThat(actual.controlledSeat).isEqualTo(2)
            assertThat(actual.arm).isEqualTo("candidate")
            assertThat(actual.terminal.scores).isEqualTo(prior.scores.map(PilotReplayScore::from))
            assertThat(actual.terminal.gameDecisions).isEqualTo(prior.steps)
            assertThat(actual.terminal.reward).isEqualTo(prior.reward(2))
            CardAdvantageEvaluator(input, Long.MAX_VALUE)
                .validate(PurchaseJson.mapper.valueToTree<JsonNode>(actual), 0)
        }
    }

    @Test
    fun cachedEvaluationCannotChangeRoleOrReward() {
        CardAdvantageFixture().use { fixture ->
            val input =
                fixture.evaluation(
                    listOf(
                        CardAdvantageEvaluationJob(
                            0,
                            71,
                            2,
                            "candidate",
                            "candidate",
                            List(4) { "baseline" },
                        )
                    )
                )
            val game =
                CardAdvantageEvaluationResult(
                    signature = input.signature(),
                    index = 0,
                    seed = 71,
                    controlledSeat = 2,
                    arm = "candidate",
                    terminal =
                        PurchaseTerminal(
                            reward = 1.0,
                            winShare = 1.0,
                            rankReward = 1.0,
                            gameDecisions = 10,
                            scores =
                                List(4) { seat ->
                                    PilotReplayScore(
                                        "p${seat + 1}",
                                        0,
                                        if (seat == 2) 10 else 0,
                                        0,
                                        if (seat == 2) 10 else 0,
                                        seat == 2,
                                    )
                                },
                        ),
                )
            val evaluator = CardAdvantageEvaluator(input, Long.MAX_VALUE)
            val original = PurchaseJson.mapper.valueToTree<ObjectNode>(game)
            evaluator.validate(original, 0)
            val changedRole = original.deepCopy().put("controlledSeat", 1)
            val changedReward = original.deepCopy()
            (changedReward.required("terminal") as ObjectNode).put("reward", 0.5)

            assertFailsWith<IllegalArgumentException> { evaluator.validate(changedRole, 0) }
            assertFailsWith<IllegalArgumentException> { evaluator.validate(changedReward, 0) }
        }
    }
}
