package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointCollectionStoreTest {
    @Test
    fun manifestAndSnapshotsBindBothCompletePhaseDictionaries() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input

            fixture.store(input)

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(
                    manifest
                        .required("bundle")
                        .required("card")
                        .required("stateFeatureNames")
                        .size()
                )
                .isEqualTo(input.bundle.cardDictionary.stateFeatureNames.size)
            assertThat(
                    manifest
                        .required("bundle")
                        .required("purchase")
                        .required("actionFeatureNames")
                        .size()
                )
                .isEqualTo(input.bundle.purchaseDictionary.actionFeatureNames.size)
            assertThat(PurchaseJson.hash(fixture.output.resolve("card-policy.json")))
                .isEqualTo(input.bundle.card?.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("purchase-policy.json")))
                .isEqualTo(input.bundle.purchase.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("opponent-0-bundle.json")))
                .isEqualTo(input.opponents[0].sha256)
            assertThat(Files.exists(fixture.output.resolve("opponent-0-card-policy.json")))
                .isFalse()
        }
    }

    @Test
    fun exactResumePreservesBothPhaseRowsAndCompletionHashes() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.save(fixture.syntheticGame(input))
            store.finish()
            val file = fixture.output.resolve("game-0.json.gz")
            val before = Files.getLastModifiedTime(file)
            val hash = PurchaseJson.hash(file)

            val resumed = fixture.store(input)
            resumed.finish()

            assertThat(resumed.completedIndices()).containsExactly(0)
            assertThat(PurchaseJson.hash(file)).isEqualTo(hash)
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(before)
        }
    }

    @Test
    fun compressedBytesCannotChangeAfterCompletion() {
        JointCollectionFixture().use { fixture ->
            val store = fixture.store()
            store.save(fixture.syntheticGame())
            store.finish()
            Files.write(
                fixture.output.resolve("game-0.json.gz"),
                byteArrayOf(0),
                StandardOpenOption.APPEND,
            )

            assertFailsWith<IllegalArgumentException> { store.completedIndices() }
        }
    }

    @Test
    fun changedSignatureCannotRepairMissingComponentSnapshots() {
        JointCollectionFixture().use { fixture ->
            fixture.store()
            val path = fixture.output.resolve("purchase-policy.json")
            Files.delete(path)

            assertFailsWith<IllegalArgumentException> { fixture.store(signature = "substituted") }

            assertThat(Files.exists(path)).isFalse()
        }
    }

    @Test
    fun missingSnapshotCanBeRestoredOnlyFromTheSameFrozenComponent() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val before = fixture.store(input).startedAt
            Files.delete(fixture.output.resolve("purchase-policy.json"))

            val resumed = fixture.store(input)

            assertThat(resumed.startedAt).isEqualTo(before)
            assertThat(PurchaseJson.hash(fixture.output.resolve("purchase-policy.json")))
                .isEqualTo(input.bundle.purchase.sha256)
        }
    }

    @Test
    fun incompleteOriginSetCannotPublishLabelsAsComplete() {
        JointCollectionFixture(games = 2).use { fixture ->
            val store = fixture.store()
            store.save(fixture.syntheticGame())

            assertFailsWith<IllegalArgumentException> { store.finish() }

            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun cancellationCannotArchiveHalfOfAJointGame() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            assertFailsWith<CancellationException> {
                store.save(JointCollector(input, "fixture", Long.MIN_VALUE).collect(0))
            }

            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
            assertThat(store.completedIndices()).isEmpty()
        }
    }

    @Test
    fun exhaustedOriginalCollectionAllowanceCannotBeRenewed() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            fixture.store(input, input.signature())
            val path = fixture.output.resolve("state.json")
            val state = PurchaseJson.read(path) as ObjectNode
            state.put("startedAt", Instant.now().minusSeconds(73 * 3600).toString())
            PurchaseJson.mapper.writeValue(path.toFile(), state)

            assertFailsWith<IllegalArgumentException> {
                JointCollectionMain.run(fixture.arguments())
            }

            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
            assertThat(PurchaseJson.read(path).required("startedAt"))
                .isEqualTo(state.required("startedAt"))
        }
    }
}
