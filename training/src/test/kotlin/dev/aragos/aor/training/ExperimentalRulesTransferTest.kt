package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExperimentalRulesTransferTest {
    @Test
    fun legacyModelStillRequiresExplicitTransfer() {
        ArenaPurchaseFixture().use { fixture ->
            val model = legacyBaseline(fixture)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TrainingArtifacts.readModel(model)
                }

            assertThat(failure).hasMessageThat().contains("different rules version")
        }
    }

    @Test
    fun transferLoadsLegacyWeightsWithoutChangingTheirDocument() {
        ArenaPurchaseFixture().use { fixture ->
            val source = legacyBaseline(fixture)
            val original = Files.readString(source)

            val model = TrainingArtifacts.readModel(source, transfer())

            assertThat(model.weights).isNotEmpty()
            assertThat(Files.readString(source)).isEqualTo(original)
        }
    }

    @Test
    fun transferAllowsCurrentGeneratedModels() {
        ArenaPurchaseFixture().use { fixture ->
            val source = fixture.directory.resolve("baseline.json")

            val model = TrainingArtifacts.readModel(source, transfer())

            assertThat(model).isEqualTo(TrainingArtifacts.readModel(source))
        }
    }

    @Test
    fun transferRetainsTheMetadataFreeHistoricalPriorContract() {
        ArenaPurchaseFixture().use { fixture ->
            val source = fixture.directory.resolve("baseline.json")
            val document = PurchaseJson.read(source) as ObjectNode
            document.remove(listOf("rulesVersion", "dataVersion"))
            fixture.artifacts.write("baseline.json", document)

            val model = TrainingArtifacts.readModel(source, transfer())

            assertThat(model).isEqualTo(TrainingArtifacts.readModel(source))
            assertThat(PurchaseJson.read(source).has("rulesVersion")).isFalse()
        }
    }

    @Test
    fun transferDoesNotAcceptAnotherSourceRulesVersion() {
        ArenaPurchaseFixture().use { fixture ->
            val source = legacyBaseline(fixture, rulesVersion = "ah1996-three-epoch-3")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TrainingArtifacts.readModel(source, transfer())
                }

            assertThat(failure).hasMessageThat().contains("different rules version")
        }
    }

    @Test
    fun transferDoesNotRelaxDataCompatibility() {
        ArenaPurchaseFixture().use { fixture ->
            val source = legacyBaseline(fixture, dataVersion = "different-catalog")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TrainingArtifacts.readModel(source, transfer())
                }

            assertThat(failure).hasMessageThat().contains("different data version")
        }
    }

    @Test
    fun transferRejectsUnsupportedDeclarations() {
        for (document in
            listOf(
                "null",
                "{}",
                """{"sourceRulesVersion":"ah1996-three-epoch-3","targetRulesVersion":"$RULES_VERSION"}""",
                """{"sourceRulesVersion":"ah1996-three-epoch-4","targetRulesVersion":"ah1996-three-epoch-4"}""",
                """{"sourceRulesVersion":"ah1996-three-epoch-4","targetRulesVersion":"$RULES_VERSION","qualificationTransferred":true}""",
            )) {
            assertFailsWith<IllegalArgumentException> {
                ExperimentalRulesTransfer.read(PurchaseJson.mapper.readTree(document))
            }
        }
    }

    @Test
    fun arenaArchivesOriginalOrdinaryAndPriorBytesWithUnqualifiedMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val original = Files.readString(legacyBaseline(fixture))
            enableArenaTransfer(fixture)
            val specification = fixture.specification
            val output = TrainingArtifacts(fixture.directory.resolve("output"))

            specification.snapshotModels(output)
            specification.snapshotOverlays(output)

            assertThat(Files.readString(output.directory.resolve("candidate.json")))
                .isEqualTo(original)
            assertThat(Files.readString(output.directory.resolve("frozen-purchase-baseline.json")))
                .isEqualTo(original)
            val metadata =
                PurchaseJson.mapper.valueToTree<ObjectNode>(specification.overlayMetadata())
            assertThat(metadata["rulesVersion"].asText()).isEqualTo(RULES_VERSION)
            assertThat(metadata["experimentalRulesTransfer"]["initializationOnly"].booleanValue())
                .isTrue()
            assertThat(
                    metadata["experimentalRulesTransfer"]["qualificationTransferred"].booleanValue()
                )
                .isFalse()
            assertThat(metadata["ordinaryModelSources"]["candidate"]["sha256"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("baseline.json")))
            assertThat(
                    PurchaseJson.read(output.directory.resolve("candidate.json"))["rulesVersion"]
                        .asText()
                )
                .isEqualTo("ah1996-three-epoch-4")
        }
    }

    @Test
    fun tokenCollectionUsesTheSameVerbatimInitializationBoundary() {
        ArenaPurchaseFixture().use { fixture ->
            val original = Files.readString(legacyBaseline(fixture))
            enableArenaTransfer(fixture)
            val specification = TokenOrderSpecification.read(fixture.source)
            val output = TrainingArtifacts(fixture.directory.resolve("output"))

            specification.arena.snapshotModels(output, "initial.json")

            assertThat(Files.readString(output.directory.resolve("initial.json")))
                .isEqualTo(original)
            val metadata =
                PurchaseJson.mapper.valueToTree<ObjectNode>(
                    specification.arena.overlayMetadata("initial.json")
                )
            assertThat(metadata["ordinaryModelSources"]["candidate"]["snapshot"].asText())
                .isEqualTo("initial.json")
        }
    }

    @Test
    fun arenaIdentityBindsOriginalModelBytesEvenWhenWeightsDoNotChange() {
        ArenaPurchaseFixture().use { fixture ->
            val source = legacyBaseline(fixture)
            enableArenaTransfer(fixture)
            val before = fixture.specification.identity()

            Files.writeString(source, Files.readString(source) + "\n")

            assertThat(fixture.specification.identity()).isNotEqualTo(before)
        }
    }

    @Test
    fun changedOriginalBytesCannotBeArchivedUnderTheValidatedIdentity() {
        ArenaPurchaseFixture().use { fixture ->
            val source = legacyBaseline(fixture)
            enableArenaTransfer(fixture)
            val specification = fixture.specification
            Files.writeString(source, Files.readString(source) + "\n")
            val output = TrainingArtifacts(fixture.directory.resolve("output"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    specification.snapshotModels(output)
                }

            assertThat(failure).hasMessageThat().contains("changed while archiving")
            assertThat(Files.exists(output.directory.resolve("candidate.json"))).isFalse()
        }
    }

    @Test
    fun purchaseCollectionRecordsTransferAndPreservesOriginalBytes() {
        PurchaseCollectionFixture().use { fixture ->
            val baseline = fixture.source.parent.resolve("baseline.json")
            val model = PurchaseJson.read(baseline) as ObjectNode
            model.put("rulesVersion", "ah1996-three-epoch-4")
            val artifacts = TrainingArtifacts(fixture.source.parent)
            artifacts.write("baseline.json", model)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<ObjectNode>("experimentalRulesTransfer", transferNode())
            artifacts.write(fixture.source.fileName.toString(), source)
            val input = fixture.input
            fixture.store(input, input.signature())

            val metadata = PurchaseJson.read(fixture.output.resolve("collection.json"))

            assertThat(metadata["rulesVersion"].asText()).isEqualTo(RULES_VERSION)
            assertThat(
                    metadata["experimentalRulesTransfer"]["qualificationTransferred"].booleanValue()
                )
                .isFalse()
            assertThat(metadata["ordinaryModelSources"]["baseline"]["snapshot"].asText())
                .isEqualTo("baseline.json")
            assertThat(Files.readString(fixture.output.resolve("baseline.json")))
                .isEqualTo(Files.readString(baseline))
        }
    }

    @Test
    fun transferDoesNotRelaxRawPurchaseEncoderValidation() {
        ArenaPurchaseFixture().use { fixture ->
            legacyBaseline(fixture)
            enableArenaTransfer(fixture)
            val policy = PurchaseJson.read(fixture.directory.resolve("policy.json")) as ObjectNode
            policy.put("encoderVersion", "different-encoder")
            fixture.artifacts.write("policy.json", policy)

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun purchaseCollectionWithoutTransferRejectsLegacyOrdinaryInputs() {
        PurchaseCollectionFixture().use { fixture ->
            val baseline = fixture.source.parent.resolve("baseline.json")
            val model = PurchaseJson.read(baseline) as ObjectNode
            model.put("rulesVersion", "ah1996-three-epoch-4")
            TrainingArtifacts(fixture.source.parent).write("baseline.json", model)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.input }

            assertThat(failure).hasMessageThat().contains("different rules version")
        }
    }

    @Test
    fun arenaTransferFlagChangesIdentityForCurrentModelsToo() {
        ArenaPurchaseFixture().use { fixture ->
            val before = fixture.specification.identity()
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<ObjectNode>("experimentalRulesTransfer", transferNode())
            fixture.artifacts.write("arena.json", source)

            assertThat(fixture.specification.identity()).isNotEqualTo(before)
        }
    }

    @Test
    fun ordinarySearchRejectsExperimentalTransferBeforeWritingSnapshots() {
        ArenaPurchaseFixture().use { fixture ->
            legacyBaseline(fixture)
            enableArenaTransfer(fixture)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    fixture.search(fixture.specification)
                }

            assertThat(failure).hasMessageThat().contains("not ordinary model search")
        }
    }

    @Test
    fun completedReplayRestoresOriginalInitializationSnapshotsBeforeReturning() {
        ArenaPurchaseFixture().use { fixture ->
            val original = Files.readString(legacyBaseline(fixture))
            enableArenaTransfer(fixture)
            val specification = fixture.specification
            val output = TrainingArtifacts(fixture.directory.resolve("output"))
            val signature =
                trainingSignature("strategy-replay-1:${specification.identity()}:0:CANDIDATE:null")
            output.write("state.json", mapOf("kind" to "strategy-replay", "signature" to signature))
            output.write("replay.json", mapOf("replayVerified" to true))

            StrategyReplayMain.run(
                listOf(
                    "--mode",
                    "strategy-replay",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.directory.toString(),
                    "--index",
                    "0",
                    "--side",
                    "candidate",
                )
            )

            assertThat(Files.readString(output.directory.resolve("candidate.json")))
                .isEqualTo(original)
            assertThat(Files.readString(output.directory.resolve("model.json"))).isEqualTo(original)
            assertThat(Files.readString(output.directory.resolve("opponent-0.json")))
                .isEqualTo(original)
        }
    }

    private fun legacyBaseline(
        fixture: ArenaPurchaseFixture,
        rulesVersion: String = "ah1996-three-epoch-4",
        dataVersion: String = DATA_VERSION,
    ): Path {
        val path = fixture.directory.resolve("baseline.json")
        val document = PurchaseJson.read(path) as ObjectNode
        document.put("rulesVersion", rulesVersion)
        document.put("dataVersion", dataVersion)
        fixture.artifacts.write("baseline.json", document)
        return path
    }

    private fun enableArenaTransfer(fixture: ArenaPurchaseFixture) {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.put("candidate", "baseline.json")
        source.put("incumbent", "baseline.json")
        source.putArray("opponents").add("baseline.json")
        source.set<ObjectNode>("experimentalRulesTransfer", transferNode())
        fixture.artifacts.write("arena.json", source)
    }

    private fun transfer(): ExperimentalRulesTransfer =
        requireNotNull(ExperimentalRulesTransfer.read(transferNode())) {
            "The test requires transfer."
        }

    private fun transferNode(): ObjectNode =
        PurchaseJson.mapper
            .createObjectNode()
            .put("sourceRulesVersion", "ah1996-three-epoch-4")
            .put("targetRulesVersion", "ah1996-three-epoch-5")
}
