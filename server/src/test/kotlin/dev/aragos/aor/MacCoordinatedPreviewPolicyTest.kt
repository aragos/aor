package dev.aragos.aor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented tensors and success claims only; no scientific evidence or model is opened. */
class MacCoordinatedPreviewPolicyTest {
    @Test
    fun aCompleteFreshQualificationRetainsTheFullRobustIdentity() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        assertThat(policy.assetSha256).isEqualTo(fixture.hash(fixture.manifest))
    }

    @Test
    fun theOldTrainingProtocolCannotClaimTheNewQualification() = withFixture { fixture ->
        fixture.changeResearchProtocol { it.put("kind", "robust-league-campaign-1") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun aCountFieldCannotHideAnIncompleteOriginalWorldArray() = withFixture { fixture ->
        fixture.changeResearchProtocol {
            (it["banks"]["mixed"]["seeds"] as ArrayNode).remove(2047)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun missingOriginalDevelopmentEvidenceRejectsBeforeTensorDecode() = withFixture { fixture ->
        fixture.changeQualification {
            (it["nomineeEvidence"] as ObjectNode).remove("completeDevelopmentReport")
        }
        Files.writeString(fixture.root.resolve("candidate/heads/CARD/policy.json"), "not a model")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }

        assertThat(failure).hasMessageThat().contains("missing or unexpected fields")
    }

    @Test
    fun portableEvidenceCannotReplaceTheArchivedOriginalSubject() = withFixture { fixture ->
        fixture.changeResearchProtocol {
            (it["nomineeEvidence"]["finalMeanReceipt"] as ObjectNode).put("sha256", "0".repeat(64))
        }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }

        assertThat(failure).hasMessageThat().contains("exact original89 nomination")
    }

    @Test
    fun anotherTokenSamplerCannotReuseFreshQualification() = withFixture { fixture ->
        fixture.changeRobustInference {
            (it["tokenSelector"] as ObjectNode).put("temperature", 0.7)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anotherCandidateBundleCannotReuseFreshQualification() = withFixture { fixture ->
        fixture.changeResearchProtocol { it.put("candidateBundleSha256", "0".repeat(64)) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anEightGenerationNomineeCannotReplaceTheOriginalFinalSix() = withFixture { fixture ->
        val path = fixture.root.resolve("nominee/sourceProtocol.json")
        val source = mapper.readTree(path.toFile()) as ObjectNode
        source.put("activeGenerations", 8)
        fixture.write("nominee/sourceProtocol.json", source)
        fixture.changeQualification {
            (it["nomineeEvidence"]["sourceProtocol"] as ObjectNode).put(
                "sha256",
                fixture.hash(path),
            )
        }
        fixture.changeResearchProtocol {
            (it["nomineeEvidence"]["sourceProtocol"] as ObjectNode).put(
                "sha256",
                fixture.hash(path),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun bothIndependentFreshBanksMustPass() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["homogeneous"] as ObjectNode).put("win99Passed", false)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    private fun withFixture(block: (PreviewAssetFixture) -> Unit) {
        val directory = Files.createTempDirectory("constructed-mac-qualification-")
        try {
            val fixture = PreviewAssetFixture(directory)
            val sampler =
                mapOf(
                    "kind" to "near-optimal-token-softmax-1",
                    "temperature" to 0.35,
                    "maxScoreGap" to 3.0,
                    "neighborRadius" to 4,
                )
            fixture.toRobustQualified(sampler = sampler)
            val names =
                setOf(
                    "sourceProtocol",
                    "completeTrainingDevelopmentReview",
                    "interruptedPhysicalClosure",
                    "finalMeanReceipt",
                    "completeDevelopmentReport",
                )
            val source = mapper.readTree(directory.resolve("research-protocol.json").toFile())
            val evidence = names.associateWith { name ->
                fixture.subject(
                    fixture.write(
                        "nominee/$name.json",
                        if (name == "sourceProtocol") source else mapOf("constructedOnly" to true),
                    )
                )
            }
            fixture.changeResearchProtocol { protocol ->
                protocol.remove(
                    listOf(
                        "activeGenerations",
                        "numericRecipe",
                        "dataMode",
                        "methodSha256",
                        "minimumDevelopmentDelta",
                    )
                )
                protocol.put("kind", "mac-qualification-campaign-1")
                protocol.put(
                    "rootProspectiveMethodSha256",
                    "0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b",
                )
                protocol.put("maximumTerminalExecutions", 8208)
                protocol.put(
                    "candidateBundleSha256",
                    fixture.hash(directory.resolve("candidate/bundle.json")),
                )
                protocol.put(
                    "baseInferenceAssetSha256",
                    fixture.hash(directory.resolve("inference-asset.json")),
                )
                protocol.put("nominationSha256", "1".repeat(64))
                protocol.set<ObjectNode>("selector", mapper.valueToTree(sampler))
                protocol.set<ObjectNode>(
                    "nomineeEvidence",
                    mapper.valueToTree(
                        evidence.mapValues { (name, value) ->
                            value + ("path" to "/unopened-original-research/$name.json")
                        }
                    ),
                )
                protocol.set<ObjectNode>(
                    "banks",
                    mapper.valueToTree(
                        listOf("mixed", "homogeneous").associateWith { name ->
                            mapOf(
                                "count" to 2048,
                                "seeds" to List(2048) { it },
                                "opponentStyle" to if (name == "mixed") "BALANCED" else "QUALIFIED",
                                "replayIndices" to listOf(0, 682, 1365, 2047),
                            )
                        }
                    ),
                )
            }
            fixture.changeQualification {
                it.put("evaluationVersion", RobustQualificationVersion.MAC_QUALIFICATION.wireValue)
                it.set<ObjectNode>("nomineeEvidence", mapper.valueToTree(evidence))
            }
            block(fixture)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private val mapper = ObjectMapper()
}
