package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointReproductionTest {
    @Test
    fun declaredHistoryStaysOutsideTypedRowsAndHasAnExactCompletionBinding() {
        JointStateFixture().use { fixture ->
            val input = enableReplay(fixture)
            val signature = input.signature()
            val store = fixture.base.store(input, signature)
            // Synthetic history tests its persistence binding, not full-game replay correctness.
            val history = PurchaseCanonicalReplay(jointStateFixture().header, emptyList())
            store.save(fixture.game().copy(signature = signature, canonicalReplay = history))
            store.finish()
            val game =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(fixture.base.output.resolve("game-0.json.gz"))
                ) {
                    "Fixture game required."
                }
            val replay = PurchaseJson.read(fixture.base.output.resolve("replay-0.json"))
            val admitted = JointReproduction.read(fixture.base.output)

            assertThat(game.has("canonicalReplay")).isFalse()
            assertThat(replay["trajectorySha256"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.base.output.resolve("game-0.json.gz")))
            assertThat(admitted.indices).containsExactly(0)
            assertThat(admitted.collection.signature()).isEqualTo(signature)
        }
    }

    @Test
    fun missingDeclaredHistoryRejectsBeforeArchivingATrajectory() {
        JointStateFixture().use { fixture ->
            val input = enableReplay(fixture)
            val store = fixture.base.store(input)

            assertFailsWith<IllegalArgumentException> { store.save(fixture.game()) }
            assertThat(Files.exists(fixture.base.output.resolve("game-0.json.gz"))).isFalse()
        }
    }

    @Test
    fun mutatedCanonicalBytesCannotEnterHistoricalReproduction() {
        JointStateFixture().use { fixture ->
            val input = enableReplay(fixture)
            val signature = input.signature()
            val store = fixture.base.store(input, signature)
            store.save(
                fixture
                    .game()
                    .copy(
                        signature = signature,
                        canonicalReplay =
                            PurchaseCanonicalReplay(jointStateFixture().header, emptyList()),
                    )
            )
            store.finish()
            val path = fixture.base.output.resolve("replay-0.json")
            val changed = PurchaseJson.read(path) as ObjectNode
            changed.put("trajectorySha256", "changed")
            Files.writeString(path, PurchaseJson.mapper.writeValueAsString(changed))

            assertFailsWith<IllegalArgumentException> {
                JointReproduction.read(fixture.base.output)
            }
        }
    }

    @Test
    fun unchangedNoHistoryProfileDoesNotAuthorizeHistoricalExecutions() {
        JointStateFixture().use { fixture ->
            val input = fixture.input
            val signature = input.signature()
            val store = fixture.base.store(input, signature)
            store.save(fixture.game().copy(signature = signature))
            store.finish()

            assertFailsWith<IllegalArgumentException> {
                JointReproduction.read(fixture.base.output)
            }
        }
    }

    @Test
    fun duplicateProspectiveOriginsAreRejected() {
        JointStateFixture().use { fixture ->
            fixture.edit {
                set<JsonNode>("replayIndices", PurchaseJson.mapper.valueToTree(listOf(0, 0)))
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    private fun enableReplay(fixture: JointStateFixture): JointCollectionInput {
        fixture.edit { set<JsonNode>("replayIndices", PurchaseJson.mapper.valueToTree(listOf(0))) }
        return fixture.input
    }
}
