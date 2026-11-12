package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AfterstateStoreTest {
    private fun input(fixture: JointStateFixture): AfterstateInput {
        fixture.base.writeBundle("rival.json")
        fixture.edit { putArray("replayIndices").add(0) }
        val original = fixture.input
        fixture.base.artifacts.write("legacy.json", JointStateChronology.from(original).metadata())
        val dictionary = fixture.base.artifacts.directory.resolve("legacy.json")
        fixture.base.artifacts.write(
            "afterstate.json",
            mapOf(
                "kind" to AfterstateValueEncoder.VERSION,
                "collection" to
                    mapOf("path" to original.source.toString(), "sha256" to original.sourceSha256),
                "legacyDictionary" to
                    mapOf(
                        "path" to dictionary.toString(),
                        "sha256" to PurchaseJson.hash(dictionary),
                    ),
            ),
        )
        return AfterstateInput.read(fixture.base.artifacts.directory.resolve("afterstate.json"))
    }

    private fun game(fixture: JointStateFixture, input: AfterstateInput): AfterstateGame =
        AfterstateGame(
            0,
            listOf(
                AfterstateRow(
                    1,
                    0,
                    1,
                    AfterstateContext.ADVANCES,
                    mapOf("purchase.decision.purchase-advances" to 1.0),
                    false,
                    null,
                )
            ),
            emptyList(),
            fixture.base
                .syntheticGame(input.collection)
                .copy(
                    canonicalReplay =
                        PurchaseCanonicalReplay(jointStateFixture().header, emptyList())
                ),
        )

    @Test
    fun completeOriginKeepsStateAndTerminalLabelsSeparate() {
        JointStateFixture().use { fixture ->
            val input = input(fixture)
            val store = AfterstateStore(input, fixture.base.output)
            store.save(game(fixture, input))
            store.finish()
            val states =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(store.directory.resolve("states-0.json.gz"))
                ) {
                    "Saved synthetic state is required."
                }
            assertThat(states.has("terminal")).isFalse()
            assertThat(
                    PurchaseJson.read(store.directory.resolve("finished.json"))
                        .required("games")
                        .intValue()
                )
                .isEqualTo(1)
        }
    }

    @Test
    fun changedLabelsCannotReuseCompletedStates() {
        JointStateFixture().use { fixture ->
            val input = input(fixture)
            val store = AfterstateStore(input, fixture.base.output)
            store.save(game(fixture, input))
            val path = store.directory.resolve("label-0.json")
            val changed =
                PurchaseJson.read(path).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            changed.put("statesSha256", "changed")
            TrainingArtifacts(store.directory).write("label-0.json", changed)
            assertFailsWith<IllegalArgumentException> { store.verify(0) }
        }
    }

    @Test
    fun noResumeOrOverwriteOfExistingOutput() {
        JointStateFixture().use { fixture ->
            val input = input(fixture)
            AfterstateStore(input, fixture.base.output)
            assertFailsWith<IllegalArgumentException> {
                AfterstateStore(input, fixture.base.output)
            }
        }
    }

    @Test
    fun finderMetadataIsNotScientificIdentity() {
        JointStateFixture().use { fixture ->
            val input = input(fixture)
            val store = AfterstateStore(input, fixture.base.output)
            store.save(game(fixture, input))
            Files.writeString(store.directory.resolve(".DS_Store"), "incidental")
            store.finish()
            assertThat(
                    PurchaseJson.read(store.directory.resolve("finished.json"))
                        .required("artifactSha256")
                        .has(".DS_Store")
                )
                .isFalse()
        }
    }
}
