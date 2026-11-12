package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.LEGACY_RULES_VERSION
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchasePlanAdmissionTest {
    @Test
    fun sourceBindsEveryPredictorSubjectWithoutEvaluatingIt() {
        Fixture().use { fixture ->
            assertThat(fixture.input.files)
                .contains(fixture.files.directory.resolve("checkpoint.json").toRealPath())
            fixture.files.write("checkpoint.json", mapOf("changed" to true))
            assertFailsWith<IllegalArgumentException> { fixture.input.unchanged() }
        }
    }

    @Test
    fun sourceRejectsReusedFutureOrProposalStreams() {
        Fixture().use { fixture ->
            val node = PurchaseJson.read(fixture.source) as ObjectNode
            (node.required("streams")[0] as ObjectNode).set<JsonNode>(
                "futures",
                node.required("streams")[0].required("proposals").deepCopy<JsonNode>(),
            )
            fixture.files.write("plan-source.json", node)
            assertFailsWith<IllegalArgumentException> { PurchasePlanInput.read(fixture.source) }
        }
    }

    @Test
    fun allMissingPublicOriginsStillRequireCompleteSealedPredictions() {
        Fixture().use { fixture ->
            fixture.publicBank()
            val admission =
                PurchasePlanTailAdmission(fixture.input, fixture.output, fixture.predictions)
            assertThat(admission.public(255).status).isEqualTo(PurchasePlanOriginStatus.MISSING)
        }
    }

    @Test
    fun admissionRejectsAnOmittedCanonicalBackbonePin() {
        Fixture().use { fixture ->
            fixture.publicBank()
            val finished = PurchaseJson.read(fixture.output.resolve("finished.json")) as ObjectNode
            (finished.required("artifactSha256") as ObjectNode).remove("backbone-255.json")
            TrainingArtifacts(fixture.output).write("finished.json", finished)
            fixture.predictionBank()
            assertFailsWith<IllegalArgumentException> {
                PurchasePlanTailAdmission(fixture.input, fixture.output, fixture.predictions)
            }
        }
    }

    @Test
    fun admissionRejectsAnIncompletePredictionBankBeforeAnyTail() {
        Fixture().use { fixture ->
            fixture.publicBank()
            val prediction = PurchaseJson.read(fixture.predictions) as ObjectNode
            prediction.put("allPredictionsComplete", false)
            fixture.files.write("predictions.json", prediction)
            assertFailsWith<IllegalArgumentException> {
                PurchasePlanTailAdmission(fixture.input, fixture.output, fixture.predictions)
            }
        }
    }

    @Test
    fun admissionRejectsSubjectHashMismatch() {
        Fixture().use { fixture ->
            fixture.publicBank()
            val prediction = PurchaseJson.read(fixture.predictions) as ObjectNode
            (prediction.required("decisions")[0] as ObjectNode).put(
                "checkpointSha256",
                "0".repeat(64),
            )
            fixture.files.write("predictions.json", prediction)
            assertFailsWith<IllegalArgumentException> {
                PurchasePlanTailAdmission(fixture.input, fixture.output, fixture.predictions)
            }
        }
    }

    @Test
    fun publicStageCannotAcceptTailAdmissionArguments() {
        assertFailsWith<IllegalArgumentException> {
            PurchasePlanMain.run(listOf("--mode", "purchase-plan-public", "--public", "unused"))
        }
    }

    @Test
    fun actualDescribeParsesTheCompleteSourceWithoutGames() {
        Fixture().use { fixture ->
            val output = fixture.output.parent.resolve("describe")
            PurchasePlanMain.run(
                listOf(
                    "--mode",
                    "purchase-plan-public",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.toString(),
                    "--workers",
                    "1",
                    "--hours",
                    "1",
                    "--describe",
                    "true",
                )
            )
            assertThat(
                    PurchaseJson.read(output.resolve("manifest.json"))
                        .required("maximumTerminalGames")
                        .intValue()
                )
                .isEqualTo(8448)
            Files.list(output).use { paths -> assertThat(paths.count()).isEqualTo(1) }
        }
    }

    @Test
    fun canonicalHeaderRetainsLegacyVersionsAndRenaissanceTiming() {
        val header =
            jointStateFixture()
                .header
                .copy(
                    rulesVersion = LEGACY_RULES_VERSION,
                    drawRenaissanceFromTurn = 7,
                )
        assertThat(TrainingCanonicalHeader.read(PurchasePlanWire.wire(header))).isEqualTo(header)
    }

    /** Receipt-only synthetic fixture: no games, retained worlds, model inference or labels. */
    private class Fixture : AutoCloseable {
        val ordinary = JointStateFixture()
        val files = ordinary.base.artifacts
        val source = files.directory.resolve("plan-source.json")
        val output = ordinary.base.directory.resolve("public")
        val predictions = files.directory.resolve("predictions.json")
        val input: PurchasePlanInput

        init {
            ordinary.edit {
                put("games", 256)
                set<JsonNode>("opponents", PurchasePlanWire.wire(listOf("bundle.json")))
            }
            for (name in listOf("checkpoint", "dictionary", "contract")) files.write(
                "$name.json",
                mapOf("synthetic" to name),
            )
            files.write(
                "predictor.json",
                mapOf(
                    "kind" to "purchase-plan-predictor-1",
                    "checkpointPath" to files.directory.resolve("checkpoint.json").toString(),
                    "checkpointSha256" to
                        PurchaseJson.hash(files.directory.resolve("checkpoint.json")),
                    "dictionaryPath" to files.directory.resolve("dictionary.json").toString(),
                    "dictionarySha256" to
                        PurchaseJson.hash(files.directory.resolve("dictionary.json")),
                    "inferenceContractPath" to files.directory.resolve("contract.json").toString(),
                    "inferenceContractSha256" to
                        PurchaseJson.hash(files.directory.resolve("contract.json")),
                ),
            )
            fun subject(name: String) =
                mapOf("path" to name, "sha256" to PurchaseJson.hash(files.directory.resolve(name)))
            files.write(
                "plan-source.json",
                mapOf(
                    "kind" to PurchasePlanProtocol.VERSION,
                    "collection" to subject("source.json"),
                    "predictorDescriptor" to subject("predictor.json"),
                    "streams" to
                        (0 until 256).map { index ->
                            mapOf(
                                "proposals" to (0L..5L).map { 100_000_000_000L + index * 10 + it },
                                "futures" to (6L..9L).map { 100_000_000_000L + index * 10 + it },
                            )
                        },
                ),
            )
            input = PurchasePlanInput.read(source)
        }

        fun publicBank() {
            Files.createDirectory(output)
            val store = PurchasePlanPublicStore(input, output)
            for (index in 0 until 256) {
                store.save(
                    PurchasePlanPublicResult(
                        PublicPurchasePlanOrigin(
                            originIndex = index,
                            status = PurchasePlanOriginStatus.MISSING,
                            plans = emptyList(),
                        ),
                        PurchasePlanBackbone(
                            index,
                            input.study.collection.seeds[index],
                            index % 4,
                            input.study.epoch(index),
                            jointStateFixture().header,
                            emptyList(),
                            null,
                            null,
                            null,
                            null,
                        ),
                    )
                )
            }
            store.finish()
            predictionBank()
        }

        fun predictionBank() {
            val finished = PurchaseJson.read(output.resolve("finished.json"))
            val descriptor = PurchaseJson.read(input.predictorDescriptor)
            files.write(
                "predictions.json",
                mapOf(
                    "kind" to "purchase-plan-predictions-1",
                    "signature" to input.signature,
                    "publicFinishedSha256" to PurchaseJson.hash(output.resolve("finished.json")),
                    "predictorDescriptorSha256" to PurchaseJson.hash(input.predictorDescriptor),
                    "publicSha256" to finished.required("publicSha256"),
                    "allPredictionsComplete" to true,
                    "terminalTailsStarted" to false,
                    "decisions" to
                        (0 until 256).map { index ->
                            mapOf(
                                "kind" to "purchase-plan-ranking-decision-1",
                                "originIndex" to index,
                                "status" to "MISSING",
                                "selectedIndex" to null,
                                "fallbackReason" to "MISSING",
                                "predictorDescriptorSha256" to
                                    PurchaseJson.hash(input.predictorDescriptor),
                                "checkpointSha256" to
                                    descriptor.required("checkpointSha256").asText(),
                                "dictionarySha256" to
                                    descriptor.required("dictionarySha256").asText(),
                                "plans" to emptyList<Any>(),
                            )
                        },
                ),
            )
        }

        override fun close() = ordinary.close()
    }
}
