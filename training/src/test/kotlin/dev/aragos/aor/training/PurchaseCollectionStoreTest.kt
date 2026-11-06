package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseCollectionStoreTest {
    @Test
    fun freshStoreFreezesCompleteDictionaryAndExactModelBytes() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input

            fixture.store(input)

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(manifest["stateFeatureNames"].map { it.asText() })
                .isEqualTo(input.dictionary.stateFeatureNames)
            assertThat(PurchaseJson.hash(fixture.output.resolve("policy.json")))
                .isEqualTo(input.modelSha256)
            assertThat(PurchaseJson.read(fixture.output.resolve("policy.json")).has("stateIndices"))
                .isFalse()
            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun resumedStoreRestoresMissingSnapshotsWithoutChangingPresentEvidence() {
        PurchaseCollectionFixture().use { fixture ->
            fixture.store()
            val archive = fixture.output.resolve("baseline.json")
            val before = Files.readAllBytes(archive)
            Files.delete(fixture.output.resolve("opponent-0.json"))

            fixture.store()

            assertThat(Files.readAllBytes(archive)).isEqualTo(before)
            assertThat(Files.readAllBytes(fixture.output.resolve("opponent-0.json")))
                .isEqualTo(before)
        }
    }

    @Test
    fun wrongSignatureCannotCreateMissingArchives() {
        PurchaseCollectionFixture().use { fixture ->
            fixture.store()
            Files.delete(fixture.output.resolve("opponent-0.json"))

            assertFailsWith<IllegalArgumentException> { fixture.store(signature = "changed") }

            assertThat(Files.exists(fixture.output.resolve("opponent-0.json"))).isFalse()
        }
    }

    @Test
    fun emptyExistingArchiveIsRejectedAndPreserved() {
        PurchaseCollectionFixture().use { fixture ->
            fixture.store()
            val archive = fixture.output.resolve("baseline.json")
            Files.writeString(archive, "")

            assertFailsWith<IllegalArgumentException> { fixture.store() }

            assertThat(Files.size(archive)).isEqualTo(0L)
        }
    }

    @Test
    fun presentEmptyStateCannotBeTreatedAsFresh() {
        PurchaseCollectionFixture().use { fixture ->
            Files.createDirectories(fixture.output)
            Files.createFile(fixture.output.resolve("state.json"))

            assertFailsWith<IllegalArgumentException> { fixture.store() }

            assertThat(Files.exists(fixture.output.resolve("collection.json"))).isFalse()
        }
    }

    @Test
    fun completedGameAndHashesRoundTripThroughIdempotentResume() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.save(fixture.syntheticGame(input))
            store.finish()
            val before = Files.readAllBytes(fixture.output.resolve("finished.json"))

            val restored = fixture.store(input)
            val completed = restored.completedIndices()
            restored.finish()

            assertThat(completed).containsExactly(0)
            assertThat(Files.readAllBytes(fixture.output.resolve("finished.json")))
                .isEqualTo(before)
            assertThat(PurchaseJson.read(fixture.output.resolve("state.json"))["phase"].asText())
                .isEqualTo("complete")
        }
    }

    @Test
    fun tamperedBehaviorCannotBeReusedOrReplaced() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.save(fixture.syntheticGame(input))
            val path = fixture.output.resolve("game-0.json")
            val altered = PurchaseJson.read(path)
            (altered["decisions"][0] as ObjectNode).put("behaviorLogProbability", -1.0)
            TrainingArtifacts(fixture.output).write("game-0.json", altered)
            val evidence = Files.readAllBytes(path)

            assertFailsWith<IllegalArgumentException> { store.completedIndices() }

            assertThat(Files.readAllBytes(path)).isEqualTo(evidence)
        }
    }

    @Test
    fun partialCollectionCannotProduceACompletionMarker() {
        PurchaseCollectionFixture(games = 2).use { fixture ->
            val store = fixture.store()
            store.save(fixture.syntheticGame())

            assertFailsWith<IllegalArgumentException> { store.finish() }

            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun exhaustedCumulativeBudgetDoesNotLaunchOrArchiveAGame() {
        PurchaseCollectionFixture().use { fixture ->
            val input = fixture.input
            fixture.store(input, input.signature())
            val state = PurchaseJson.read(fixture.output.resolve("state.json")) as ObjectNode
            state.put("startedAt", Instant.EPOCH.toString())
            TrainingArtifacts(fixture.output).write("state.json", state)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PurchaseCollectionMain.run(fixture.arguments())
                }

            assertThat(failure).hasMessageThat().contains("exhausted")
            assertThat(Files.exists(fixture.output.resolve("game-0.json"))).isFalse()
        }
    }

    @Test
    fun deadlineCancellationLeavesNoPartialGameSnapshot() {
        PurchaseCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            assertFailsWith<java.util.concurrent.CancellationException> {
                store.save(PurchaseCollector(input, "fixture", System.nanoTime() - 1).collect(0))
            }

            assertThat(store.completedIndices()).isEmpty()
            assertThat(Files.exists(fixture.output.resolve("game-0.json"))).isFalse()
        }
    }
}
