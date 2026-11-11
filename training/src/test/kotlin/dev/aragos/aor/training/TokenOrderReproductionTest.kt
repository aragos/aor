package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderReproductionTest {
    @Test
    fun completedCountsCannotHideSkippedOrigins() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.completed()
            val document =
                PurchaseJson.read(fixture.directory.resolve("dataset.json")) as ObjectNode
            (document["counts"] as ObjectNode).put("SKIPPED", 3)
            fixture.artifacts.write("dataset.json", document)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenOrderReproduction.read(fixture.directory)
                }

            assertThat(failure).hasMessageThat().contains("collected and skipped origin")
        }
    }

    @Test
    fun completedBankBindsAllOriginsIncludingPredeclaredSkips() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.completed()

            val input = TokenOrderReproduction.read(fixture.directory)

            assertThat(input.specification.replayOrigins).containsExactly(0, 3).inOrder()
            assertThat(input.files.keys)
                .containsAtLeast(
                    "origin-0.json",
                    "origin-1.json",
                    "origin-2.json",
                    "origin-3.json",
                    "initial.json",
                    "expansion-imitation-candidate.json",
                    "expansion-imitation-opponent-3.json",
                )
            assertThat(input.specification.identity()).isEqualTo(specification.identity())
        }
    }

    @Test
    fun optInMetadataBindsTheActualSerializedOrdinaryReference() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.snapshots()

            val metadata =
                PurchaseJson.mapper.valueToTree<JsonNode>(
                    TokenOrderContinuationArtifacts.metadata(specification, fixture.directory)
                )

            assertThat(metadata["continuationProtocol"].asText()).isEqualTo("ARENA_OVERLAYS_1")
            assertThat(metadata["tokenReference"]["ordinaryModelSha256"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("initial.json")))
            assertThat(metadata["tokenReference"]["ordinaryModelSnapshot"].asText())
                .isEqualTo("initial.json")
        }
    }

    @Test
    fun sparsePriorEquivalentExpansionDictionaryIsRejectedBeforeCollection() {
        TokenOrderOverlayFixture().use { fixture ->
            val document =
                PurchaseJson.read(fixture.directory.resolve("expansion.json")) as ObjectNode
            (document["weights"] as ObjectNode).remove("tokenPreference")
            fixture.artifacts.write("expansion.json", document)
            val specification = fixture.snapshots()

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenOrderContinuationArtifacts.metadata(specification, fixture.directory)
                }

            assertThat(failure)
                .hasMessageThat()
                .contains("every explicit non-expansion coefficient")
        }
    }

    @Test
    fun changedOrdinarySnapshotCannotReuseACompletedReceipt() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.completed()
            val document =
                PurchaseJson.read(fixture.directory.resolve("initial.json")) as ObjectNode
            (document["weights"] as ObjectNode).put("tokenPreference", 999.0)
            fixture.artifacts.write("initial.json", document)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderReproduction.read(fixture.directory)
            }
        }
    }

    @Test
    fun changedExpansionSnapshotCannotReuseACompletedReceipt() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.completed()
            val document =
                PurchaseJson.read(fixture.directory.resolve("expansion-imitation-candidate.json"))
                    as ObjectNode
            (document["weights"] as ObjectNode).put("tokens", 999.0)
            fixture.artifacts.write("expansion-imitation-candidate.json", document)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderReproduction.read(fixture.directory)
            }
        }
    }

    @Test
    fun matchingTamperedRunAndManifestCannotChangeProspectiveRequests() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.completed()
            for (name in listOf("dataset.json", "run.json")) {
                val document = PurchaseJson.read(fixture.directory.resolve(name)) as ObjectNode
                (document["rootRequests"][0] as ObjectNode).put("requestedOrdinal", 99)
                fixture.artifacts.write(name, document)
            }

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenOrderReproduction.read(fixture.directory)
                }

            assertThat(failure).hasMessageThat().contains("prospective requests")
        }
    }

    @Test
    fun changedOriginBytesCannotReuseTheFinishedHash() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.completed()
            val document =
                PurchaseJson.read(fixture.directory.resolve("origin-1.json")) as ObjectNode
            document.put("originDecisions", 99)
            fixture.artifacts.write("origin-1.json", document)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderReproduction.read(fixture.directory)
            }
        }
    }

    @Test
    fun skippedOriginsCannotExposeObservations() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.specification
            val origin = fixture.skipped(specification, 0, "fixture")
            val document = PurchaseJson.mapper.valueToTree<ObjectNode>(origin)
            document.set<JsonNode>("observation", PurchaseJson.mapper.createObjectNode())

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenOrderCollectionMain.validateOrigin(document, specification, 0, "fixture")
                }

            assertThat(failure).hasMessageThat().contains("cannot publish observations")
        }
    }

    @Test
    fun completeOriginComparisonIncludesSkippedStatusAndRootIdentity() {
        TokenOrderOverlayFixture().use { fixture ->
            val origin = fixture.skipped(fixture.specification, 0, "fixture")

            assertFailsWith<IllegalArgumentException> {
                TokenOrderReproduction.verifyOrigin(
                    PurchaseJson.mapper.valueToTree(origin),
                    origin.copy(originDecisions = 99),
                )
            }
        }
    }
}
