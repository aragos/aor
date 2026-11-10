package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseSharedBaselineTest {
    @Test
    fun absentSharedBaselinePreservesTheLegacySignatureAndManifest() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            fixture.store(input)

            assertThat(input.sharedPurchaseBaseline).isNull()
            assertThat(input.signature())
                .isEqualTo(
                    trainingSignature(
                        listOf(
                                "purchase-collect-1",
                                input.source.toString(),
                                input.sourceSha256,
                                input.baseline.sha256,
                                input.opponents.map { it.sha256 },
                                input.modelSha256,
                                PurchaseObservationEncoder.ENCODER_VERSION,
                                PurchasePolicy.ACTOR_VERSION,
                                input.dictionary.stateFeatureNames,
                                input.dictionary.actionFeatureNames,
                            )
                            .joinToString("\n")
                    )
                )
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("collection.json"))
                        .has("sharedPurchaseBaseline")
                )
                .isFalse()
            assertThat(Files.exists(fixture.output.resolve("shared-purchase-baseline.json")))
                .isFalse()
        }
    }

    @Test
    fun relativeSharedBaselineIsResolvedAndArchivedSeparatelyFromOrdinaryModels() {
        PurchaseCollectionFixture().use { fixture ->
            val input = sharedBaseline(fixture)
            val shared = requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }

            fixture.store(input)

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(shared.path)
                .isEqualTo(fixture.source.parent.resolve("shared.json").toRealPath())
            assertThat(manifest["sharedPurchaseBaseline"]["path"].asText())
                .isEqualTo(shared.path.toString())
            assertThat(manifest["sharedPurchaseBaseline"]["sha256"].asText())
                .isEqualTo(shared.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("shared-purchase-baseline.json")))
                .isEqualTo(shared.sha256)
            assertThat(manifest["baseline"]["sha256"].asText()).isEqualTo(input.baseline.sha256)
            assertThat(manifest["opponents"][0]["sha256"].asText())
                .isEqualTo(input.opponents.first().sha256)
        }
    }

    @Test
    fun presentSharedBaselineRequiresAPathEvenWhenNull() {
        PurchaseCollectionFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            for (value in listOf(null, true, 17, emptyMap<String, String>())) {
                source.set<JsonNode>(
                    "sharedPurchaseBaseline",
                    PurchaseJson.mapper.valueToTree(value),
                )
                PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)

                val failure = assertFailsWith<IllegalArgumentException> { fixture.input }

                assertThat(failure).hasMessageThat().contains("sharedPurchaseBaseline")
            }
        }
    }

    @Test
    fun matchingSharedBaselineResumeRestoresItsMissingArchive() {
        PurchaseCollectionFixture().use { fixture ->
            val input = sharedBaseline(fixture)
            fixture.store(input, input.signature())
            val manifest = Files.readAllBytes(fixture.output.resolve("collection.json"))
            Files.delete(fixture.output.resolve("shared-purchase-baseline.json"))

            fixture.store(input, input.signature())

            assertThat(Files.readAllBytes(fixture.output.resolve("collection.json")))
                .isEqualTo(manifest)
            assertThat(PurchaseJson.hash(fixture.output.resolve("shared-purchase-baseline.json")))
                .isEqualTo(
                    requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }.sha256
                )
        }
    }

    @Test
    fun changedSharedPriorBytesCannotReuseOrRewriteAnExistingCollection() {
        PurchaseCollectionFixture().use { fixture ->
            val input = sharedBaseline(fixture)
            fixture.store(input, input.signature())
            val snapshot = fixture.output.resolve("shared-purchase-baseline.json")
            val before = Files.readAllBytes(snapshot)
            val shared = requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }
            Files.writeString(shared.path, Files.readString(shared.path) + "\n")
            val changed = fixture.input

            assertFailsWith<IllegalArgumentException> {
                fixture.store(changed, changed.signature())
            }

            assertThat(changed.sourceSha256).isEqualTo(input.sourceSha256)
            assertThat(changed.signature()).isNotEqualTo(input.signature())
            assertThat(Files.readAllBytes(snapshot)).isEqualTo(before)
        }
    }

    @Test
    fun sharedPriorChangedAfterReadCannotBeArchivedWithTheOldFingerprint() {
        PurchaseCollectionFixture().use { fixture ->
            val input = sharedBaseline(fixture)
            val shared = requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }
            Files.writeString(shared.path, Files.readString(shared.path) + "\n")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    fixture.store(input, input.signature())
                }

            assertThat(failure).hasMessageThat().contains("changed after signature validation")
            assertThat(Files.exists(fixture.output.resolve("shared-purchase-baseline.json")))
                .isFalse()
        }
    }

    @Test
    fun trajectoryFromAnotherSharedPriorCannotBeReused() {
        PurchaseCollectionFixture().use { fixture ->
            val input = sharedBaseline(fixture)
            val game = fixture.syntheticGame(input).copy(signature = input.signature())
            val shared = requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }
            Files.writeString(shared.path, Files.readString(shared.path) + "\n")
            val changed = fixture.input

            assertFailsWith<IllegalArgumentException> {
                PurchaseTrajectoryValidation(changed, changed.signature())
                    .validate(PurchaseJson.mapper.valueToTree(game), 0)
            }
        }
    }

    private fun sharedBaseline(fixture: PurchaseCollectionFixture): PurchaseCollectionInput {
        Files.copy(fixture.input.baseline.path, fixture.source.parent.resolve("shared.json"))
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.put("sharedPurchaseBaseline", "shared.json")
        PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)
        return fixture.input
    }
}
