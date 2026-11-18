package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionPairedStoreTest {
    @Test
    fun compressedMissingReceiptRetainsOriginalRequestAndCanonicalBytes() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val store = ExpansionPairedStore(input, fixture.base.output)
            val origin = missing(input)

            val receipt = store.save(origin)

            val raw =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(fixture.base.output.resolve(receipt.file))
                )
            assertThat(raw["request"]["index"].intValue()).isEqualTo(0)
            assertThat(raw["prefix"]["entries"].map { it.asText() })
                .containsExactlyElementsIn(origin.prefix.entries)
                .inOrder()
            assertThat(raw["arms"].size()).isEqualTo(0)
            assertThat(receipt.tailGames).isEqualTo(0)
            assertThat(receipt.sha256)
                .isEqualTo(PurchaseJson.hash(fixture.base.output.resolve(receipt.file)))
        }
    }

    @Test
    fun aCompletedOriginCannotBeOverwrittenEvenWithIdenticalEvidence() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val store = ExpansionPairedStore(input, fixture.base.output)
            val origin = missing(input)
            store.save(origin)

            assertFailsWith<IllegalArgumentException> { store.save(origin) }
        }
    }

    @Test
    fun incompleteOriginSetCannotEmitCompletion() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val store = ExpansionPairedStore(input, fixture.base.output)
            val receipt = store.save(missing(input))

            assertFailsWith<IllegalArgumentException> { store.finish(listOf(receipt)) }
            assertThat(Files.exists(fixture.base.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun mismatchedCanonicalHashIsRejectedBeforeAnyOriginWrite() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val store = ExpansionPairedStore(input, fixture.base.output)
            val original = missing(input)

            assertFailsWith<IllegalArgumentException> {
                store.save(
                    original.copy(prefix = original.prefix.copy(canonicalSha256 = "0".repeat(64)))
                )
            }
            assertThat(Files.exists(fixture.base.output.resolve("origin-0.json.gz"))).isFalse()
        }
    }

    /** Synthetic wire receipt only; tests never claim this empty canonical list played a game. */
    private fun missing(input: ExpansionPairedInput): ExpansionPairedOrigin =
        ExpansionPairedOrigin(
            signature = input.signature,
            request = ExpansionPairedRequest(0),
            streams = input.origins.first(),
            status = ExpansionOriginStatus.MISSING,
            missingReason = ExpansionMissingReason.TARGET_WINDOW_NOT_REACHED,
            firstExpansionTurn = null,
            observedWindows = 0,
            prefix =
                ExpansionPairedPrefix(
                    expansionPairedState().header,
                    emptyList(),
                    CardAdvantageExecutor.hash(emptyList<String>()),
                    0,
                    true,
                ),
            actorInput = null,
            incumbentCommand = null,
            exactIncumbentVerified = false,
            arms = emptyList(),
        )
}
