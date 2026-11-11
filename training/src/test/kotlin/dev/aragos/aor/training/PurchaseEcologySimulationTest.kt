package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseEcologySimulationTest {
    @Test
    fun collectorReproducesArenaWithDistinctSampledLearnerAndFrozenRivalActors() {
        PurchaseEcologyFixture().use { fixture ->
            val input = fixture.input
            val collected = PurchaseCollector(input, input.signature(), Long.MAX_VALUE).collect(0)

            val arena =
                StrategyArena.game(
                    fixture.purchase.specification,
                    0,
                    ArenaSide.CANDIDATE,
                    Long.MAX_VALUE,
                )

            assertThat(collected.terminal.scores).isEqualTo(arena.scores)
            assertThat(collected.terminal.gameDecisions).isEqualTo(arena.decisions)
            assertThat(collected.terminal.reward).isEqualTo(arena.rewards[0])
            assertThat(collected.decisions).isNotEmpty()
            assertThat(collected.decisions.all { it.behaviorLogProbability != null }).isTrue()
            assertThat(
                    collected.decisions.any { row ->
                        row.selectedIndex !=
                            PurchasePolicy.choose(
                                    row.observation,
                                    requireNotNull(input.opponentPurchasePolicy) {
                                            "The fixture requires a frozen rival purchase actor."
                                        }
                                        .model,
                                    row.actorSeed,
                                )
                                .index
                    }
                )
                .isTrue()
        }
    }

    @Test
    fun historicalReproductionRetainsExactSamplingAndCompleteCanonicalHistory() {
        PurchaseEcologyFixture().use { fixture ->
            val input = fixture.input
            val store = PurchaseCollectionStore(input, fixture.output, input.signature())
            store.save(PurchaseCollector(input, input.signature(), Long.MAX_VALUE).collect(0))
            store.finish()
            val replayOutput = fixture.directory.resolve("reproduced")

            PurchaseReproduction.run(
                listOf(
                    "--mode",
                    "purchase-replay",
                    "--source",
                    fixture.output.toString(),
                    "--output",
                    replayOutput.toString(),
                    "--hours",
                    ".05",
                )
            )

            assertThat(PurchaseJson.hash(replayOutput.resolve("game-0.json")))
                .isEqualTo(PurchaseJson.hash(fixture.output.resolve("game-0.json")))
            assertThat(PurchaseJson.hash(replayOutput.resolve("replay-0.json")))
                .isEqualTo(PurchaseJson.hash(fixture.output.resolve("replay-0.json")))
            assertThat(
                    PurchaseJson.read(replayOutput.resolve("reproduction.json"))[
                            "allCanonicalHistoriesExact"]
                        .booleanValue()
                )
                .isTrue()
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("game-0.json")).has("canonicalReplay")
                )
                .isFalse()
        }
    }

    @Test
    fun changedCanonicalHistoryCannotPassHistoricalReproductionEvenWithRehashedReceipt() {
        PurchaseEcologyFixture().use { fixture ->
            val input = fixture.input
            val store = PurchaseCollectionStore(input, fixture.output, input.signature())
            store.save(PurchaseCollector(input, input.signature(), Long.MAX_VALUE).collect(0))
            store.finish()
            val artifacts = TrainingArtifacts(fixture.output)
            val replay = PurchaseJson.read(fixture.output.resolve("replay-0.json")) as ObjectNode
            replay.set<JsonNode>("entries", PurchaseJson.mapper.valueToTree(emptyList<String>()))
            artifacts.write("replay-0.json", replay)
            val finished = PurchaseJson.read(fixture.output.resolve("finished.json")) as ObjectNode
            (finished["replaySha256"] as ObjectNode).put(
                "replay-0.json",
                PurchaseJson.hash(fixture.output.resolve("replay-0.json")),
            )
            artifacts.write("finished.json", finished)
            val replayOutput = fixture.directory.resolve("reproduced")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PurchaseReproduction.run(
                        listOf(
                            "--mode",
                            "purchase-replay",
                            "--source",
                            fixture.output.toString(),
                            "--output",
                            replayOutput.toString(),
                            "--hours",
                            ".05",
                        )
                    )
                }

            assertThat(failure).hasMessageThat().contains("full canonical history")
            assertThat(Files.exists(replayOutput.resolve("finished.json"))).isFalse()
        }
    }
}
