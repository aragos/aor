package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Generic admission exercises real readers, with invented certificates and zero simulations. */
class CoordinatedWinQualificationTest {
    @Test
    fun genericAssetNeedsNoFamilyContainerPinSubstitution() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = CoordinatedWinQualificationInput.read(fixture.source().path)
            input.verify()
            assertThat(input.asset.original.source.sha256)
                .isEqualTo(fixture.toy.original.source.sha256)
            assertThat(input.admissionBindings.keys)
                .containsExactly("inferenceAssetSha256", "nominationSha256")
            assertThat(input.executionReceipt(0)["completedGames"]).isEqualTo(0)
        }
    }

    @Test
    fun positiveMixedEvidenceOpensOnlyTheIndependentHomogeneousBank() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input =
                CoordinatedWinQualificationInput.read(
                    fixture.source("homogeneous", fixture.confirmation()).path
                )
            assertThat(input.expectedOpponents(4095)).containsExactly(0, 0, 0, 0)
        }
    }

    @Test
    fun missingMixedEvidenceRejectsHomogeneous() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                CoordinatedWinQualificationInput.read(fixture.source("homogeneous").path)
            }
        }
    }

    @Test
    fun wrongNominationPinRejectsBeforeOpeningTheAsset() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val protocol = CoordinatedWinQualificationProtocol(fixture.protocol)
            val missing =
                TokenPairedSubject(
                    fixture.root.resolve("absent.json"),
                    "0".repeat(64),
                    "absent.json",
                )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    protocol.bindInputs(missing, fixture.nomination)
                }
            assertThat(error.message).contains("before model access")
        }
    }

    @Test
    fun alternateCandidateCertificateIsRejected() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val node =
                (PurchaseJson.read(fixture.nomination.path) as ObjectNode).put(
                    "candidateAlternatives",
                    1,
                )
            val certificate = fixture.save("bad-nomination.json", node)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPolicyNomination.read(certificate, fixture.asset)
            }
        }
    }

    @Test
    fun validLegacyCalibrationCannotEnterTheV2InferenceAsset() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val factory =
                CoordinatedEsCalibrationTest::class
                    .java
                    .getDeclaredMethod("record", CoordinatedEsFixture::class.java)
                    .also { it.isAccessible = true }
            val legacy = factory.invoke(CoordinatedEsCalibrationTest(), fixture.toy) as JsonNode
            val seal = fixture.save("valid-legacy-calibration.json", legacy)
            assertThat(CoordinatedEsCalibration.read(seal, fixture.toy.original).version)
                .isEqualTo(CoordinatedEsCalibrationVersion.V1)
            val node = PurchaseJson.read(fixture.asset.path) as ObjectNode
            node.set<JsonNode>("calibration", ExpansionOnPolicyStore.wire(fixture.ref(seal)))
            val asset = fixture.save("legacy-calibrated-asset.json", node)
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedInferenceAsset.read(asset, fixture.toy.input.configuration)
                }
            assertThat(failure.message).contains("exact V2 seal")
        }
    }

    @Test
    fun portableSubjectCannotEscapeItsOwner() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val node =
                ExpansionOnPolicyStore.wire(
                    mapOf("path" to "../outside.json", "sha256" to "0".repeat(64))
                )
            assertFailsWith<IllegalArgumentException> {
                CoordinatedInferenceAsset.subject(fixture.asset, node)
            }
        }
    }

    @Test
    fun changedPackedReferenceCannotPassTheNativeCompositionJoin() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val original = TrainingArtifacts.readModel(fixture.root.resolve("packed.json"))
            fixture.files.model(
                original.copy(weights = original.weights + ("tokens" to 99.0)),
                "packed-wrong.json",
            )
            val node = PurchaseJson.read(fixture.asset.path) as ObjectNode
            (node["references"] as ObjectNode).set<JsonNode>(
                "qualifiedOrdinary",
                ExpansionOnPolicyStore.wire(fixture.ref(fixture.subject("packed-wrong.json"))),
            )
            val asset = fixture.save("bad-asset.json", node)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedInferenceAsset.read(asset, fixture.toy.input.configuration)
            }
        }
    }

    @Test
    fun currentValueChangeCannotEnterTheQualifiedUnit() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val model =
                PurchaseJson.read(fixture.toy.original.subject(CoordinatedEsHead.TOKEN).path)
                    as ObjectNode
            model.put("valueBias", 3.0)
            val changed = fixture.save("changed-token.json", model)
            val bundle = PurchaseJson.read(fixture.toy.original.source.path) as ObjectNode
            (bundle["heads"]["TOKEN"] as ObjectNode)
                .put("path", "changed-token.json")
                .put("sha256", changed.sha256)
            val current = fixture.save("changed-current.json", bundle)
            val asset = PurchaseJson.read(fixture.asset.path) as ObjectNode
            asset.set<JsonNode>("currentBundle", ExpansionOnPolicyStore.wire(fixture.ref(current)))
            val subject = fixture.save("changed-value-asset.json", asset)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedInferenceAsset.read(subject, fixture.toy.input.configuration)
            }
        }
    }

    @Test
    fun incompleteDictionaryCannotEnterTheQualifiedUnit() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val bundle = PurchaseJson.read(fixture.toy.original.source.path) as ObjectNode
            (bundle["heads"]["TOKEN"] as ObjectNode).put("dictionarySha256", "0".repeat(64))
            val current = fixture.save("wrong-dictionary-current.json", bundle)
            val asset = PurchaseJson.read(fixture.asset.path) as ObjectNode
            asset.set<JsonNode>("currentBundle", ExpansionOnPolicyStore.wire(fixture.ref(current)))
            val subject = fixture.save("wrong-dictionary-asset.json", asset)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedInferenceAsset.read(subject, fixture.toy.input.configuration)
            }
        }
    }

    @Test
    fun wrongReferenceEcologyIsRejected() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = CoordinatedWinQualificationInput.read(fixture.source().path)
            val arena = PurchaseJson.read(input.arenaSource.path) as ObjectNode
            (arena["tokenOrder"] as ObjectNode).put("candidateSelector", "ORDINARY")
            val source = fixture.save("wrong-arena.json", arena)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedInferenceEcology.read(
                    source,
                    input.asset,
                    requireNotNull(input.protocol.banks["mixed"]) { "Toy mixed bank." },
                    "mixed",
                )
            }
        }
    }

    @Test
    fun winOnlyVersionPassesNegativeRewardWhileLegacyGateStillRejects() {
        val worlds = CoordinatedWinQualificationFixture.worlds()
        val report =
            ExpansionOnPolicyStore.wire(CoordinatedWinQualificationProtocol.REPORT.report(worlds))
        CoordinatedWinQualificationProtocol.REPORT.requirePassed(report)
        assertThat(report["gates"].fieldNames().asSequence().toSet())
            .containsExactly("wins", "passed")
        val legacy = ExpansionOnPolicyStore.wire(CoordinatedQualificationReport.report(worlds))
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationReport.requirePassed(legacy)
        }
    }

    @Test
    fun legacyReportCannotMasqueradeAsWinOnlyVersion() {
        val legacy =
            ExpansionOnPolicyStore.wire(
                CoordinatedQualificationReport.report(CoordinatedWinQualificationFixture.worlds())
            )
        assertFailsWith<IllegalArgumentException> {
            CoordinatedWinQualificationProtocol.REPORT.requirePassed(legacy)
        }
    }

    @Test
    fun partialBankCannotOpenWinOnlyEffects() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedWinQualificationProtocol.REPORT.report(
                CoordinatedWinQualificationFixture.worlds().dropLast(1)
            )
        }
    }

    @Test
    fun constructedNativeSerializationExportsWithoutSimulation() {
        val target = System.getenv("AOR_WIN_QUALIFICATION_FIXTURE") ?: return
        CoordinatedWinQualificationFixture().use { fixture -> export(fixture, Path.of(target)) }
    }

    private fun export(fixture: CoordinatedWinQualificationFixture, target: Path) {
        require(!Files.exists(target)) { "Constructed evidence output must be absent." }
        val originalSource = fixture.source()
        val gate = fixture.confirmation()
        val gateRecord = PurchaseJson.read(gate.path)
        val certificate =
            fixture.save(
                "qualification.json",
                mapOf(
                    "kind" to "coordinated-win-qualification-1",
                    "schemaVersion" to 1,
                    "passed" to true,
                    "complete" to true,
                    "profile" to CoordinatedInferenceAsset.PROFILE,
                    "supportProfile" to CoordinatedInferenceAsset.SUPPORT,
                    "rulesVersion" to fixture.protocolRecord()["rulesVersion"],
                    "dataVersion" to fixture.protocolRecord()["dataVersion"],
                    "configuration" to CoordinatedInferenceAsset.CONFIGURATION,
                    "inferenceAssetSha256" to fixture.asset.sha256,
                    "nominationSha256" to fixture.nomination.sha256,
                    "banks" to
                        listOf("mixed", "homogeneous").associateWith {
                            mapOf(
                                "complete" to true,
                                "pairs" to 4096,
                                "win99Passed" to true,
                                "evidence" to gateRecord["evidence"],
                                "report" to gateRecord["report"],
                            )
                        },
                ),
            )
        fixture.save(
            "preview.json",
            mapOf(
                "kind" to "coordinated-win-support-preview-1",
                "schemaVersion" to 1,
                "profile" to CoordinatedInferenceAsset.PROFILE,
                "inferenceAsset" to fixture.ref(fixture.asset),
                "qualification" to fixture.ref(certificate),
            ),
        )
        val inputs = target.resolve("inputs")
        Files.walk(fixture.root).use { paths ->
            paths.forEach { path ->
                val copy = inputs.resolve(fixture.root.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(copy)
                else Files.copy(path, copy)
            }
        }
        val input =
            CoordinatedWinQualificationInput.read(inputs.resolve(originalSource.path.fileName))
        val chunk = target.resolve("chunk")
        val artifacts = TrainingArtifacts(chunk)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        artifacts.write(
            "started.json",
            mapOf("signature" to input.signature, "workers" to 10, "maximumSeconds" to 60),
        )
        val terminal =
            ArenaGame(
                List(4) { .25 },
                List(4) { .3 },
                List(4) { .5 },
                List(4) { PilotReplayScore("p${it + 1}", 0, 10, 0, 10, true) },
                1,
                0,
            )
        var calls = 0
        val constructed =
            object : ThreeRoleScreenInput by input {
                override fun executeGame(
                    deadline: Long,
                    operation: (Long) -> ArenaGame,
                ): ArenaGame {
                    calls++
                    return terminal
                }

                override fun executionReceipt(expected: Int?): Map<String, Int> {
                    require(expected == calls) { "Only the exact constructed calls count." }
                    return mapOf("attemptedGames" to calls, "completedGames" to calls)
                }
            }
        val rows =
            input.indices.map { index ->
                val world =
                    ThreeRoleScreen.play(constructed, index, Long.MAX_VALUE) { _, _ ->
                        error("No replay in this constructed shard.")
                    }
                val value = ExpansionOnPolicyStore.wire(world)
                val name = "world-$index.json.gz"
                CardTrajectoryFiles.save(chunk.resolve(name), value)
                mapOf(
                    "index" to index,
                    "file" to name,
                    "sha256" to PurchaseJson.hash(chunk.resolve(name)),
                    "semanticSha256" to ThreeRoleScreen.semanticHash(value),
                )
            }
        val field =
            CoordinatedWinQualificationMain::class.java.getDeclaredField("delegate").also {
                it.isAccessible = true
            }
        val finish =
            ThreeRoleScreenMain::class
                .java
                .getDeclaredMethod(
                    "finish",
                    ThreeRoleScreenInput::class.java,
                    Path::class.java,
                    String::class.java,
                    List::class.java,
                )
                .also { it.isAccessible = true }
        finish.invoke(field.get(null), constructed, chunk, "chunk", rows)
        TrainingArtifacts(target)
            .write(
                "CONSTRUCTED-FIXTURE.json",
                mapOf(
                    "kind" to "coordinated-win-qualification-native-fixture-1",
                    "constructedOnly" to true,
                    "simulationCalls" to 0,
                    "newGames" to 0,
                    "modelForwards" to 0,
                    "inventedTerminalRecords" to calls,
                    "inventedCertificate" to true,
                    "protocol" to "inputs/protocol.json",
                    "source" to "inputs/mixed-source.json",
                    "preview" to "inputs/preview.json",
                    "chunk" to "chunk",
                    "inputPins" to input.inputPins,
                ),
            )
    }
}
