package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageCollectionBatchTest {
    @Test
    fun cachedOriginsKeepTheirBytesAndModificationTimesAcrossBatchResume() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val paths =
                (0..1).map { index ->
                    output.resolve("origin-$index.json.gz").also {
                        CardTrajectoryFiles.save(it, skipped(input, index))
                    }
                }
            val bytes = paths.map(Files::readAllBytes)
            val times = paths.map { Files.getLastModifiedTime(it) }

            CardAdvantageMain.run(arguments(input.source, output, "1,0"))

            paths.forEachIndexed { index, path ->
                assertThat(Files.readAllBytes(path)).isEqualTo(bytes[index])
                assertThat(Files.getLastModifiedTime(path)).isEqualTo(times[index])
            }
        }
    }

    @Test
    fun invalidLaterOriginPreservesTheCompletedPrefixAndStopsTheBatch() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val first = output.resolve("origin-0.json.gz")
            CardTrajectoryFiles.save(first, skipped(input, 0))
            CardTrajectoryFiles.save(
                output.resolve("origin-1.json.gz"),
                skipped(input, 1).put("signature", "wrong"),
            )
            val bytes = Files.readAllBytes(first)

            assertFailsWith<IllegalArgumentException> {
                CardAdvantageMain.run(arguments(input.source, output, "0,1,2"))
            }

            assertThat(Files.readAllBytes(first)).isEqualTo(bytes)
            assertThat(Files.exists(output.resolve("origin-2.json.gz"))).isFalse()
        }
    }

    @Test
    fun everyCollectionIndexIsValidatedBeforeAnyEarlierRecordOrLock() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val first = output.resolve("origin-0.json.gz")
            Files.write(first, byteArrayOf(1, 2, 3))

            assertFailsWith<IllegalArgumentException> {
                CardAdvantageMain.run(arguments(input.source, output, "0,3"))
            }

            assertThat(Files.readAllBytes(first)).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(Files.exists(output.resolve(".origin-0.lock"))).isFalse()
        }
    }

    @Test
    fun originalCollectionBatchDeadlineIncludesCachedOriginValidation() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val first = output.resolve("origin-0.json.gz")
            CardTrajectoryFiles.save(first, skipped(input, 0))
            val bytes = Files.readAllBytes(first)
            val expired = arguments(input.source, output, "0,1").dropLast(1) + "0.000000000001"

            assertFailsWith<CancellationException> { CardAdvantageMain.run(expired) }

            assertThat(Files.readAllBytes(first)).isEqualTo(bytes)
            assertThat(Files.exists(output.resolve("origin-1.json.gz"))).isFalse()
        }
    }

    private fun source(fixture: CardAdvantageFixture): CardAdvantageCollectionInput {
        fixture.artifacts.write(
            "source.json",
            fixture.source(
                List(3) { index ->
                    fixture.request.copy(index = index, seed = 71 + index, ordinal = Int.MAX_VALUE)
                }
            ) +
                mapOf(
                    "schemaVersion" to 2,
                    "collection" to CardRootPairCollection("root-pairs-1", listOf("train"), 1),
                ),
        )
        return fixture.input
    }

    private fun skipped(input: CardAdvantageCollectionInput, index: Int): ObjectNode {
        val request = input.origins[index]
        val origin =
            CardAdvantageOrigin(
                schemaVersion = 2,
                signature = input.signature(),
                index = index,
                split = request.split,
                seed = request.seed,
                seat = request.seat,
                status = "skipped",
                skipReason = "requested-root-not-reached",
                request = request,
                backboneDecisions = 1,
                backboneCanonicalSha256 = "a".repeat(64),
                observations = emptyMap(),
                groups = emptyList(),
                outcomes = emptyMap(),
            )
        return PurchaseJson.mapper.valueToTree<JsonNode>(origin) as ObjectNode
    }

    private fun arguments(source: Path, output: Path, indices: String): List<String> =
        listOf(
            "--mode",
            "card-advantage-collect",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
            "--indices",
            indices,
            "--hours",
            ".05",
        )
}
