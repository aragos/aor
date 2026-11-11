package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointStateStoreTest {
    @Test
    fun legacySourceAndTrajectoryOmitEveryJointStateExtension() {
        JointCollectionFixture().use { fixture ->
            val game = PurchaseJson.mapper.valueToTree<JsonNode>(fixture.syntheticGame())
            val store = fixture.store()
            store.save(fixture.syntheticGame())
            store.finish()

            assertThat(fixture.input.ecology).isNull()
            assertThat(game.has("jointEvidence")).isFalse()
            assertThat(Files.exists(fixture.output.resolve("joint-state-0.json"))).isFalse()
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("finished.json"))
                        .has("jointStateSha256")
                )
                .isFalse()
        }
    }

    @Test
    fun optInBindsDistinctOrdinaryAndPurchasePriorsWithCompleteOverlays() {
        JointStateFixture().use { fixture ->
            val input = fixture.input
            fixture.base.store(input)
            val manifest = PurchaseJson.read(fixture.base.output.resolve("collection.json"))
            val controllers =
                requireNotNull(input.ecology) { "Fixture ecology required." }
                    .controllers(input, 0, emptyMap())

            assertThat(input.baseline.sha256).isNotEqualTo(input.purchaseBaseline.sha256)
            assertThat(input.bundle.baselineSha256).isEqualTo(input.purchaseBaseline.sha256)
            assertThat(manifest["sharedPurchaseBaseline"]["sha256"].asText())
                .isEqualTo(input.purchaseBaseline.sha256)
            assertThat(
                    controllers.values.all { it.controls("expand") && it.controls("buy-tokens") }
                )
                .isTrue()
            assertThat(
                    controllers.values.none {
                        it.controls("play-cards") || it.controls("purchase-advances")
                    }
                )
                .isTrue()
        }
    }

    @Test
    fun ecologyCannotEnterAnUnversionedLegacySource() {
        JointStateFixture().use { fixture ->
            fixture.edit { remove("jointStateChronology") }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun typedStateLabelsAndCoverageHaveSeparateImmutableBindings() {
        JointStateFixture().use { fixture ->
            val store = fixture.base.store(fixture.input)
            store.save(fixture.game())
            store.finish()
            val state = PurchaseJson.read(fixture.base.output.resolve("joint-state-0.json"))
            val labels = PurchaseJson.read(fixture.base.output.resolve("joint-label-0.json"))
            val coverage = PurchaseJson.read(fixture.base.output.resolve("joint-coverage-0.json"))

            assertThat(state.has("terminal")).isFalse()
            assertThat(state["rows"].map { it["number"].intValue() })
                .containsExactly(0, 1)
                .inOrder()
            assertThat(state["rows"].all { it["stateFeatures"].isEmpty }).isTrue()
            assertThat(labels.has("rows")).isFalse()
            assertThat(labels["sourceGameSha256"]).isEqualTo(state["sourceGameSha256"])
            assertThat(coverage["observedSelectedCommandQuotes"].size()).isEqualTo(1)
            assertThat(store.completedIndices()).containsExactly(0)
        }
    }

    @Test
    fun changedSidecarIsRejectedWithoutChangingOriginalGameBytes() {
        JointStateFixture().use { fixture ->
            val store = fixture.base.store(fixture.input)
            store.save(fixture.game())
            store.finish()
            val path = fixture.base.output.resolve("joint-state-0.json")
            val changed = PurchaseJson.read(path) as ObjectNode
            changed.put("originIndex", 1)
            Files.writeString(path, PurchaseJson.mapper.writeValueAsString(changed))

            assertFailsWith<IllegalArgumentException> { store.completedIndices() }
        }
    }

    @Test
    fun emptyPhaseWorldsAreRetainedInIndependentOriginCounts() {
        JointStateFixture().use { fixture ->
            val store = fixture.base.store(fixture.input)
            store.save(
                fixture
                    .game()
                    .copy(
                        cardDecisions = emptyList(),
                        purchaseDecisions = emptyList(),
                        jointEvidence = emptyList(),
                    )
            )
            store.finish()
            val summary =
                PurchaseJson.read(fixture.base.output.resolve("joint-coverage-summary.json"))

            assertThat(summary["independentWorlds"].intValue()).isEqualTo(1)
            assertThat(summary["worldCounts"]["zeroCardDecisions"].intValue()).isEqualTo(1)
            assertThat(summary["worldCounts"]["zeroPurchaseDecisions"].intValue()).isEqualTo(1)
        }
    }

    @Test
    fun quoteEvidenceCannotSilentlyOmitARecordedPurchase() {
        JointStateFixture().use { fixture ->
            val store = fixture.base.store(fixture.input)

            assertFailsWith<IllegalArgumentException> {
                store.save(fixture.game().copy(jointEvidence = emptyList()))
            }
        }
    }

    @Test
    fun schemaExportUsesCompleteOwnerDictionariesWithoutGames() {
        JointCollectionFixture().use { fixture ->
            JointStateSchema.run(
                listOf(
                    "--mode",
                    "joint-state-schema",
                    "--configuration",
                    fixture.configuration.id,
                    "--output",
                    fixture.output.toString(),
                )
            )
            val card = PurchaseJson.read(fixture.output.resolve("card-schema.json"))
            val joint = PurchaseJson.read(fixture.output.resolve("joint-state-dictionary.json"))

            assertThat(card["stateFeatureNames"].map { it.asText() })
                .isEqualTo(fixture.input.bundle.cardDictionary.stateFeatureNames)
            assertThat(joint["stateFeatureNames"].size())
                .isEqualTo(
                    fixture.input.bundle.cardDictionary.stateFeatureNames.size +
                        fixture.input.bundle.purchaseDictionary.stateFeatureNames.size
                )
            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
        }
    }
}
