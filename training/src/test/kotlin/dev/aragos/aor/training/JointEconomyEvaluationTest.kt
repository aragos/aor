package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointEconomyEvaluationTest {
    @Test
    fun inputBindsEconomyModelBytesAndAddsOnlyItsConditionalVersion() {
        CardAdvantageFixture().use { fixture ->
            fixture.artifacts.model(JointEconomyPolicyTest.baseline, "baseline.json")
            val original = fixture.evaluation(listOf(job))
            val candidate =
                JointEconomyCandidates.generate(original.environment.baseline.model).last()
            fixture.artifacts.model(candidate.policy.model, "economy.json")
            val input = economyInput(fixture)

            assertThat(input.manifest()["economyVersion"]).isEqualTo(JointEconomyPolicy.VERSION)
            assertThat(original.manifest()).doesNotContainKey("economyVersion")
            assertThat(original.signature())
                .isEqualTo(
                    trainingSignature(
                        listOf(
                                "card-advantage-evaluate-1",
                                original.source,
                                original.sourceSha256,
                                original.environment.baseline.sha256,
                                original.environment.purchase.sha256,
                                original.policies.mapValues { it.value.sha256 },
                                CardAdvantagePolicy.SELECTOR_VERSION,
                                CardProposals.VERSION,
                            )
                            .joinToString("\n")
                    )
                )
            assertThat(input.policies["candidate"]?.sha256)
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("economy.json")))
            assertThat(input.policies["candidate"]?.economy?.model)
                .isEqualTo(candidate.policy.model)
            assertThat(input.policies["candidate"]?.metadata()?.keys)
                .containsExactly("kind", "path", "sha256")
        }
    }

    @Test
    fun sourceCannotSmugglePurchaseChangesThroughAnEconomyModel() {
        CardAdvantageFixture().use { fixture ->
            val baseline = fixture.input.environment.baseline.model
            fixture.artifacts.model(
                baseline.copy(weights = baseline.weights + ("cash" to 999.0)),
                "economy.json",
            )

            assertFailsWith<IllegalArgumentException> { economyInput(fixture) }
        }
    }

    @Test
    fun changedCandidateBytesInvalidateAnExistingEvaluationResult() {
        CardAdvantageFixture().use { fixture ->
            val baseline = fixture.input.environment.baseline.model
            fixture.artifacts.model(baseline, "economy.json")
            val original = economyInput(fixture)
            fixture.artifacts.model(
                baseline.copy(weights = baseline.weights + ("planning.heldCommodity" to 1.0)),
                "economy.json",
            )
            val changed = CardAdvantageEvaluationInput.read(original.source)

            assertThat(changed.signature()).isNotEqualTo(original.signature())
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageEvaluator(changed, Long.MAX_VALUE)
                    .validate(
                        PurchaseJson.mapper.valueToTree<JsonNode>(
                            mapOf(
                                "schemaVersion" to 1,
                                "signature" to original.signature(),
                                "index" to 0,
                                "seed" to 71,
                                "controlledSeat" to 2,
                                "arm" to "candidate",
                                "terminal" to emptyMap<String, Any>(),
                            )
                        ),
                        0,
                    )
            }
        }
    }

    @Test
    fun unchangedEconomyControllerPreservesEveryCanonicalEntryAndTerminalResult() {
        CardAdvantageFixture().use { fixture ->
            val environment = fixture.input.environment
            fun play(economy: Boolean): Pair<SimulatedGame, Trace> {
                val trace = Trace()
                val controllers: Map<Int, SimulationController> =
                    (0..3).associateWith {
                        if (economy) {
                            JointEconomyController(
                                environment,
                                JointEconomyPolicy(
                                    environment.baseline.model,
                                    environment.baseline.model,
                                ),
                            )
                        } else CardAdvantageController(environment, null)
                    }
                return Simulation()
                    .play(
                        environment.configuration,
                        71,
                        List(4) { Competitor(environment.baseline.model, BotDifficulty.HARD) },
                        verifyReplay = true,
                        trace = trace,
                        controllers = controllers,
                    ) to trace
            }

            val prior = play(false)
            val actual = play(true)

            assertThat(actual.second.entries).isEqualTo(prior.second.entries)
            assertThat(actual.second.header).isEqualTo(prior.second.header)
            assertThat(actual.first.copy(elapsedMillis = 0))
                .isEqualTo(prior.first.copy(elapsedMillis = 0))
        }
    }

    private fun economyInput(fixture: CardAdvantageFixture): CardAdvantageEvaluationInput {
        val original = fixture.evaluation(listOf(job))
        val source = PurchaseJson.read(original.source) as ObjectNode
        (source.required("policies") as ObjectNode).set<JsonNode>(
            "candidate",
            PurchaseJson.mapper.valueToTree(mapOf("kind" to "economy", "path" to "economy.json")),
        )
        PurchaseJson.mapper.writeValue(original.source.toFile(), source)
        return CardAdvantageEvaluationInput.read(original.source)
    }

    private class Trace : SimulationTrace {
        override val seat = 0
        var header: GameHeader? = null
        var entries = emptyList<String>()

        override fun decision(
            number: Int,
            snapshotEntryCount: Int,
            commandEntryIndex: Int,
            view: GameView,
            observation: BotObservation,
            chosen: BotCandidate,
            model: BotModel,
        ) = Unit

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    companion object {
        private val job =
            CardAdvantageEvaluationJob(0, 71, 2, "candidate", "candidate", List(4) { "baseline" })
    }
}
