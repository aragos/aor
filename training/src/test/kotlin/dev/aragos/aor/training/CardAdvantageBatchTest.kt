package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageBatchTest {
    @Test
    fun evaluationBatchKeepsExplicitOrderAndAcceptsTheBoundedMaximum() {
        CardAdvantageFixture().use { fixture ->
            val source = fixture.evaluation(jobs()).source
            val selected =
                CardAdvantageOptions.parse(
                    arguments(source, fixture.directory.resolve("out"), "2,0,1")
                )
            val maximum =
                CardAdvantageOptions.parse(
                    arguments(source, fixture.directory.resolve("out"), (0..63).joinToString(","))
                )

            assertThat(selected.index).isNull()
            assertThat(selected.indices).containsExactly(2, 0, 1).inOrder()
            assertThat(maximum.indices).hasSize(64)
        }
    }

    @Test
    fun evaluationBatchRejectsInvalidOrDuplicateIndicesBeforeExecution() {
        CardAdvantageFixture().use { fixture ->
            val source = fixture.evaluation(jobs()).source
            listOf(
                    "",
                    "-1",
                    "true",
                    "1.0",
                    "1,",
                    "1,,2",
                    "1,1",
                    "0,00",
                    "2147483648",
                    (0..64).joinToString(","),
                )
                .forEach { indices ->
                    assertFailsWith<IllegalArgumentException> {
                        CardAdvantageOptions.parse(
                            arguments(source, fixture.directory.resolve("out"), indices)
                        )
                    }
                }
        }
    }

    @Test
    fun collectionAcceptsBatchModeAndMixedSelectionFlagsRemainRejected() {
        CardAdvantageFixture().use { fixture ->
            val output = fixture.directory.resolve("out")
            val collection =
                CardAdvantageOptions.parse(
                    arguments(fixture.source, output, "0").map {
                        if (it == "card-advantage-evaluate") "card-advantage-collect" else it
                    }
                )
            assertThat(collection.indices).containsExactly(0)
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageOptions.parse(
                    arguments(fixture.source, output, "0") + listOf("--index", "0")
                )
            }
        }
    }

    @Test
    fun batchValidatesEveryIndexBeforeTouchingAnEarlierCachedRecord() {
        CardAdvantageFixture().use { fixture ->
            val source = fixture.evaluation(jobs()).source
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val record = output.resolve("game-0.json.gz")
            Files.write(record, byteArrayOf(1, 2, 3))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    CardAdvantageMain.run(arguments(source, output, "0,3"))
                }
            assertThat(failure).hasMessageThat().contains("before any batch work")
            assertThat(Files.readAllBytes(record)).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(Files.exists(output.resolve(".game-0.lock"))).isFalse()
        }
    }

    @Test
    fun completedGamesAreIdenticalWithSingleAndBatchedExecution() {
        CardAdvantageFixture(preferPass = true).use { fixture ->
            val input = fixture.evaluation(jobs().take(2))
            val single = fixture.directory.resolve("single")
            val batch = fixture.directory.resolve("batch")
            for (index in 0..1) {
                CardAdvantageMain.run(singleArguments(input.source, single, index))
            }
            CardAdvantageMain.run(arguments(input.source, batch, "1,0"))

            for (index in 0..1) {
                val filename = "game-$index.json.gz"
                assertThat(Files.readAllBytes(batch.resolve(filename)))
                    .isEqualTo(Files.readAllBytes(single.resolve(filename)))
            }
        }
    }

    @Test
    fun failedBatchPreservesEarlierRecordAndResumeValidatesItWithoutOverwrite() {
        CardAdvantageFixture().use { fixture ->
            val input = fixture.evaluation(jobs())
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val first = output.resolve("game-0.json.gz")
            val second = output.resolve("game-1.json.gz")
            CardTrajectoryFiles.save(first, cached(input, 0))
            CardTrajectoryFiles.save(second, cached(input, 1).put("signature", "wrong"))
            val bytes = Files.readAllBytes(first)

            assertFailsWith<IllegalArgumentException> {
                CardAdvantageMain.run(arguments(input.source, output, "0,1,2"))
            }
            assertThat(Files.readAllBytes(first)).isEqualTo(bytes)
            assertThat(Files.exists(output.resolve("game-2.json.gz"))).isFalse()
            assertThat(CardTrajectoryFiles.readIfPresent(second)?.required("signature")?.asText())
                .isEqualTo("wrong")
            CardAdvantageMain.run(arguments(input.source, output, "0"))
            assertThat(Files.readAllBytes(first)).isEqualTo(bytes)
        }
    }

    @Test
    fun originalBatchDeadlineAlsoCoversCachedGames() {
        CardAdvantageFixture().use { fixture ->
            val input = fixture.evaluation(jobs())
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val first = output.resolve("game-0.json.gz")
            CardTrajectoryFiles.save(first, cached(input, 0))
            val bytes = Files.readAllBytes(first)
            val expired = arguments(input.source, output, "0,1").dropLast(1) + "0.000000000001"

            assertFailsWith<CancellationException> { CardAdvantageMain.run(expired) }
            assertThat(Files.readAllBytes(first)).isEqualTo(bytes)
            assertThat(Files.exists(output.resolve("game-1.json.gz"))).isFalse()
        }
    }

    private fun jobs(): List<CardAdvantageEvaluationJob> =
        List(3) { index ->
            CardAdvantageEvaluationJob(
                index,
                71 + index,
                index,
                "candidate",
                if (index == 0) "baseline" else "candidate",
                List(4) { "candidate" },
            )
        }

    private fun arguments(source: Path, output: Path, indices: String): List<String> =
        listOf(
            "--mode",
            "card-advantage-evaluate",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
            "--indices",
            indices,
            "--hours",
            ".05",
        )

    private fun singleArguments(source: Path, output: Path, index: Int): List<String> =
        arguments(source, output, index.toString()).map { if (it == "--indices") "--index" else it }

    private fun cached(input: CardAdvantageEvaluationInput, index: Int): ObjectNode {
        val job = input.jobs[index]
        val result =
            CardAdvantageEvaluationResult(
                signature = input.signature(),
                index = index,
                seed = job.seed,
                controlledSeat = job.seat,
                arm = job.arm,
                terminal =
                    PurchaseTerminal(
                        reward = 1.0,
                        winShare = 1.0,
                        rankReward = 1.0,
                        scores =
                            List(4) { seat ->
                                PilotReplayScore(
                                    "p${seat + 1}",
                                    0,
                                    if (seat == job.seat) 10 else 0,
                                    0,
                                    if (seat == job.seat) 10 else 0,
                                    seat == job.seat,
                                )
                            },
                        gameDecisions = 10,
                    ),
            )
        return PurchaseJson.mapper.valueToTree<JsonNode>(result) as ObjectNode
    }
}
