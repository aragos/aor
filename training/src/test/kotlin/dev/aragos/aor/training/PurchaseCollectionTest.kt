package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseCollectionTest {
    @Test
    fun baselineCollectionKeepsTheOrdinaryGameAndRecordsAllPurchaseRecommendations() {
        PurchaseCollectionFixture().use { fixture ->
            val input = fixture.input
            val ordinary =
                Simulation()
                    .play(
                        input.configuration,
                        input.seeds[0],
                        List(input.configuration.playerCount) {
                            Competitor(input.baseline.model, BotDifficulty.HARD)
                        },
                    )

            val actual = PurchaseCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal.scores)
                .isEqualTo(ordinary.scores.map(PilotReplayScore::from))
            assertThat(actual.terminal.gameDecisions).isEqualTo(ordinary.steps)
            assertThat(actual.terminal.reward).isEqualTo(ordinary.reward(0))
            assertThat(actual.decisions.map { it.decision })
                .containsAtLeast("purchase-shipping", "purchase-advances")
            assertThat(
                    actual.decisions.all {
                        it.selectedIndex == it.observation.baselineIndex &&
                            it.behaviorLogProbability == null &&
                            it.behaviorValue == null
                    }
                )
                .isTrue()
            PurchaseTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

    @Test
    fun completeOnPolicyGameUsesSharedSamplingAndSurvivesArtifactValidation() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            val actual = PurchaseCollector(input, "fixture", Long.MAX_VALUE).collect(0)
            store.save(actual)
            store.finish()

            assertThat(actual.decisions).isNotEmpty()
            actual.decisions.forEach { decision ->
                val choice =
                    PurchasePolicy.choose(
                        decision.observation,
                        requireNotNull(input.policy) { "The fixture requires its frozen actor." },
                        decision.actorSeed,
                    )
                assertThat(decision.selectedIndex).isEqualTo(choice.index)
                assertThat(decision.behaviorLogProbability).isEqualTo(choice.logProbability)
                assertThat(decision.behaviorValue).isEqualTo(choice.value)
            }
            assertThat(store.completedIndices()).containsExactly(0)
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("finished.json"))["gameSha256"][0]
                        .asText()
                )
                .isEqualTo(PurchaseJson.hash(fixture.output.resolve("game-0.json")))
        }
    }

    @Test
    fun pairedSourcesShareSeedsSeatsAndOpponentLineups() {
        PurchaseCollectionFixture(games = 8).use { fixture ->
            val input = fixture.input

            assertThat(input.seeds).isEqualTo(List(8) { StrategyArena.seed(71, it) })
            assertThat(input.seeds.indices.map(input::seat))
                .containsExactly(0, 1, 2, 0, 1, 2, 0, 1)
                .inOrder()
            assertThat(input.opponentIndices(7)).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun sourceRejectsFractionalGameCount() {
        PurchaseCollectionFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("games", 1.5)
            PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun modelRejectsUndeclaredSerializedCaches() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val path =
                requireNotNull(fixture.input.policyPath) {
                    "The fixture requires its frozen actor file."
                }
            val model = PurchaseJson.read(path) as ObjectNode
            model.set<JsonNode>("stateIndices", PurchaseJson.mapper.createObjectNode())
            PurchaseJson.mapper.writeValue(path.toFile(), model)

            assertFailsWith<IllegalArgumentException> { PurchasePolicyInput.read(path) }
        }
    }

    @Test
    fun duplicateJsonFieldsCannotChangeFrozenInputMeaning() {
        PurchaseCollectionFixture().use { fixture ->
            Files.writeString(fixture.source, """{"schemaVersion":1,"schemaVersion":2}""")

            assertFailsWith<com.fasterxml.jackson.core.JsonParseException> { fixture.input }
        }
    }

    @Test
    fun commandLineRejectsUnboundedAndRepeatedOptions() {
        PurchaseCollectionFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                PurchaseCollectionOptions.parse(fixture.arguments() + listOf("--workers", "1"))
            }
            assertFailsWith<IllegalArgumentException> {
                PurchaseCollectionOptions.parse(
                    fixture.arguments().dropLast(2) + listOf("--hours", "NaN")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                PurchaseCollectionOptions.parse(
                    fixture.arguments() + listOf("--hidden-world", "true")
                )
            }
        }
    }
}
