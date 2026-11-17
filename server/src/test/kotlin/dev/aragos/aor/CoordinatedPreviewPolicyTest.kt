package dev.aragos.aor

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotExpansionModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotTokenOrderControl
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.JointPolicyScope
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedPreviewPolicyTest {
    @Test
    fun completeConstructedAssetLoadsTheExplicitProfile() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_83_SUPPORT_1)
        assertThat(policy.assetSha256).isEqualTo(fixture.hash(fixture.manifest))
    }

    @Test
    fun loadedPolicyDoesNotReopenChangedFiles() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        Files.writeString(fixture.manifest, "broken after the immutable asset was cached")
        val players = (1..4).map { Player(PlayerId("p$it"), "Constructed $it") }
        val view =
            GameState(GameHeader(players, players.first().id)).viewForPlayer(players.first().id)
        val observation =
            BotObservation(
                playerId = "p1",
                decision = "bid",
                context = emptyMap(),
                availableCash = 0,
                availableTokens = 0,
                candidates = listOf(BotCandidate("bid 0", emptyMap())),
                rulesVersion = JointPolicyScope.RULES_VERSION,
                dataVersion = JointPolicyScope.DATA_VERSION,
                configuration = JointPolicyScope.configuration,
            )

        assertThat(policy.choose(view, observation, -17)).isEqualTo("bid 0")
    }

    @Test
    fun changedRawHeadIsRejectedBeforeDecode() = withFixture { fixture ->
        Files.writeString(fixture.root.resolve("candidate/heads/CARD/policy.json"), "not a model")

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun changedValueParametersAreRejectedDespiteResealedTransport() = withFixture { fixture ->
        fixture.changeHead("CARD") { it.put("valueBias", 0.25) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun incompleteBundleIsRejected() = withFixture { fixture ->
        fixture.changeCandidate { (it["heads"] as ObjectNode).remove("TOKEN") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun incompletePublicDictionaryIsRejected() = withFixture { fixture ->
        fixture.changeHead("PURCHASE") { node ->
            node.putArray("stateFeatureNames").add("invented.partial.dictionary")
            node.putArray("stateWeights").also { array -> repeat(64) { array.add(0.0) } }
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun unqualifiedSupportProfileIsRejected() = withFixture { fixture ->
        fixture.changeQualification { it.put("supportProfile", "raw-family83") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun failedHomogeneousGateCannotQualifyTheAsset() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["homogeneous"] as ObjectNode).put("reward99Passed", false)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun mismatchedQualificationCandidateIsRejected() = withFixture { fixture ->
        fixture.changeQualification { it.put("candidateBundleSha256", "0".repeat(64)) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun changedQualificationEvidenceIsRejected() = withFixture { fixture ->
        Files.writeString(fixture.root.resolve("evidence/mixed-report.json"), "{}")

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun legacyCalibrationCannotQualifyTheNewAsset() = withFixture { fixture ->
        fixture.changeCalibration { it.put("kind", "coordinated-es-calibration-1") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun headAndOuterScalesMustAgree() = withFixture { fixture ->
        fixture.changeCalibration { (it["scales"] as ObjectNode).put("TOKEN", 2.0) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun extraPackedReferenceWeightIsRejected() = withFixture { fixture ->
        fixture.changeReference("qualifiedOrdinary") {
            (it["weights"] as ObjectNode).put("cash", 5.0)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun expansionReferenceCannotChangeOtherM8Weights() = withFixture { fixture ->
        fixture.changeReference("expansionE") { (it["weights"] as ObjectNode).put("cash", 5.0) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun parentTraversalIsRejected() = withFixture { fixture ->
        fixture.changeManifest {
            (it["candidateBundle"] as ObjectNode).put("path", "../outside.json")
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun duplicateJsonKeysAreRejected() = withFixture { fixture ->
        val text = Files.readString(fixture.manifest)
        Files.writeString(fixture.manifest, text.replaceFirst("{", "{\"schemaVersion\":1,"))

        assertFailsWith<JsonParseException> { CoordinatedPreviewPolicy.read(fixture.manifest) }
    }

    /**
     * Retained only for the isolated production-server/browser integration; never qualification.
     */
    @Test
    fun exportsClearlyLabeledConstructedAssetForServerIntegration() {
        val directory = Path.of("build/test-fixtures/coordinated-preview").toAbsolutePath()
        val fixture = PreviewAssetFixture(directory)
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        fixture.write(
            "constructed-fixture.json",
            mapOf(
                "kind" to "constructed-coordinated-preview-fixture-1",
                "constructedOnly" to true,
                "realModels" to 0,
                "scientificGames" to 0,
                "fits" to 0,
                "qualificationClaimsInventedForTransportTest" to true,
                "manifest" to fixture.subject(fixture.manifest),
            ),
        )

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_83_SUPPORT_1)
    }

    private fun withFixture(block: (PreviewAssetFixture) -> Unit) {
        val directory = Files.createTempDirectory("constructed-coordinated-asset-")
        try {
            block(PreviewAssetFixture(directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

/** Every tensor and every success claim here is invented; no scientific model or row is opened. */
internal class PreviewAssetFixture(val root: Path) {
    private val mapper = ObjectMapper()
    private val profile = ServerBotProfile.COORDINATED_83_SUPPORT_1.version
    private val support = "known-own-chaos-support-1"
    private val config = JointPolicyScope.configuration
    private val configuration =
        mapOf(
            "playerCount" to config.playerCount,
            "map" to config.map.name,
            "initialCard" to config.initialCard.name,
            "deckModification" to config.deckModification,
            "immediateMasterArtDiscard" to config.immediateMasterArtDiscard,
            "drawThreeKeepTwo" to config.drawThreeKeepTwo,
        )
    private val scope =
        mapOf(
            "profile" to profile,
            "supportProfile" to support,
            "rulesVersion" to JointPolicyScope.RULES_VERSION,
            "dataVersion" to JointPolicyScope.DATA_VERSION,
            "configuration" to configuration,
        )
    private val digest = "1".repeat(64)
    val manifest = root.resolve("manifest.json")

    init {
        Files.createDirectories(root)
        val catalog =
            PublicCatalog.forPlayers(
                4,
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                ),
            )
        val dictionaries =
            mapOf(
                "CARD" to
                    CardObservationEncoder.dictionary(catalog).let {
                        Triple(
                            CardObservationEncoder.ENCODER_VERSION,
                            it.stateFeatureNames,
                            it.actionFeatureNames,
                        )
                    },
                "PURCHASE" to
                    PurchaseObservationEncoder.dictionary(catalog).let {
                        Triple(
                            PurchaseObservationEncoder.ENCODER_VERSION,
                            it.stateFeatureNames,
                            it.actionFeatureNames,
                        )
                    },
                "EXPANSION" to
                    ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2)
                        .let {
                            Triple(
                                TerritoryEncoder.EXPANSION_2.version,
                                it.stateFeatureNames,
                                it.actionFeatureNames,
                            )
                        },
                "TOKEN" to
                    TokenObservationEncoder.dictionary(catalog, TerritoryEncoder.TOKEN_2).let {
                        Triple(
                            TerritoryEncoder.TOKEN_2.version,
                            it.stateFeatureNames,
                            it.actionFeatureNames,
                        )
                    },
            )
        for (role in listOf("original", "candidate")) {
            val heads = dictionaries.mapValues { (head, dictionary) ->
                val schema =
                    when (head) {
                        "EXPANSION" -> 2
                        "TOKEN" -> 3
                        else -> 1
                    }
                val originalTerritory = role == "original" && head in listOf("EXPANSION", "TOKEN")
                val model =
                    mapOf(
                        "schemaVersion" to schema,
                        "encoderVersion" to dictionary.first,
                        "hiddenSize" to 64,
                        "baselineProbability" to 0.97,
                        "stateFeatureNames" to dictionary.second,
                        "actionFeatureNames" to dictionary.third,
                        "stateWeights" to List(64 * dictionary.second.size) { 0.0 },
                        "actionWeights" to List(64 * dictionary.third.size) { 0.0 },
                        "stateBias" to List(64) { 0.0 },
                        "actionBias" to List(64) { 0.0 },
                        "actorWeights" to List(64) { if (originalTerritory) 0.0 else 0.01 },
                        "valueWeights" to List(64) { 0.0 },
                        "valueBias" to 0.0,
                    )
                val path = write("$role/heads/$head/policy.json", model)
                mapOf(
                    "path" to "heads/$head/policy.json",
                    "sha256" to hash(path),
                    "dictionarySha256" to
                        sha(
                            mapper.writeValueAsBytes(
                                sortedMapOf(
                                    "encoderVersion" to dictionary.first,
                                    "stateFeatureNames" to dictionary.second,
                                    "actionFeatureNames" to dictionary.third,
                                )
                            )
                        ),
                )
            }
            write("$role/bundle.json", mapOf("kind" to "coordinated-es-bundle-1", "heads" to heads))
        }
        val card = BotModels.default.weights
        val expansion =
            card + BotExpansionModel.Coefficient.entries.associate { it.feature to 0.125 }
        val packed =
            card +
                mapOf(BotExpansionModel.GATE to 1.0, BotTokenOrderControl.FIXED_16_TO_15 to 1.0) +
                BotExpansionModel.Coefficient.entries.associate { it.key to 0.125 }
        val refs =
            mapOf(
                    "qualifiedOrdinary" to packed,
                    "cardM8" to card,
                    "purchaseF0" to BotModels.default.weights,
                    "expansionE" to expansion,
                )
                .mapValues { (name, weights) ->
                    subject(
                        write(
                            "references/$name.json",
                            mapOf("schemaVersion" to 1, "weights" to weights) +
                                if (name == "qualifiedOrdinary")
                                    mapOf(
                                        "rulesVersion" to JointPolicyScope.RULES_VERSION,
                                        "dataVersion" to JointPolicyScope.DATA_VERSION,
                                        "configuration" to configuration,
                                    )
                                else emptyMap(),
                        )
                    )
                } + mapOf("tokenControlVersion" to "FIXED_16_TO_15")
        val calibration =
            mapOf(
                "kind" to "coordinated-es-calibration-2",
                "passed" to true,
                "complete" to true,
                "protocolSha256" to digest,
                "originalBundleSha256" to hash(root.resolve("original/bundle.json")),
                "projectionSha256" to digest,
                "panelSha256" to digest,
                "probePopulationSha256" to digest,
                "directionsSha256" to digest,
                "prescribedMeanSha256" to digest,
                "scales" to mapOf("EXPANSION" to 1.0, "TOKEN" to 1.0),
                "semanticFitnessDecoded" to false,
                "calibrationAttempts" to 1,
                "heads" to
                    listOf("EXPANSION", "TOKEN").associateWith { head ->
                        mapOf(
                            "kind" to "coordinated-es-feasible-gap-calibration-2",
                            "head" to head,
                            "complete" to true,
                            "scale" to 1.0,
                            "subjects" to
                                mapOf(
                                    "originalModelSha256" to
                                        hash(root.resolve("original/heads/$head/policy.json")),
                                    "protocolSha256" to digest,
                                    "projectionSha256" to digest,
                                    "directionsSha256" to digest,
                                    "prescribedMeanSha256" to digest,
                                    "publicPanelSha256" to digest,
                                ),
                        )
                    },
            )
        write("calibration.json", calibration)
        val banks =
            listOf("mixed", "homogeneous").associateWith { bank ->
                mapOf(
                    "complete" to true,
                    "pairs" to 4096,
                    "win99Passed" to true,
                    "reward99Passed" to true,
                    "evidence" to
                        subject(
                            write("evidence/$bank-evidence.json", mapOf("constructedOnly" to true))
                        ),
                    "report" to
                        subject(
                            write("evidence/$bank-report.json", mapOf("constructedOnly" to true))
                        ),
                )
            }
        val referenceHashes =
            refs
                .filterKeys { it != "tokenControlVersion" }
                .mapValues { (_, value) ->
                    @Suppress("UNCHECKED_CAST") (value as Map<String, String>)["sha256"]
                }
                .mapKeys { (name, _) -> name + "Sha256" } +
                mapOf("tokenControlVersion" to "FIXED_16_TO_15")
        write(
            "qualification.json",
            scope +
                mapOf(
                    "kind" to "coordinated-support-qualification-1",
                    "schemaVersion" to 1,
                    "passed" to true,
                    "complete" to true,
                    "candidateBundleSha256" to hash(root.resolve("candidate/bundle.json")),
                    "originalBundleSha256" to hash(root.resolve("original/bundle.json")),
                    "calibrationSha256" to hash(root.resolve("calibration.json")),
                    "references" to referenceHashes,
                    "banks" to banks,
                ),
        )
        write(
            "manifest.json",
            scope +
                mapOf(
                    "kind" to "coordinated-83-support-preview-1",
                    "schemaVersion" to 1,
                    "candidateBundle" to subject(root.resolve("candidate/bundle.json")),
                    "originalBundle" to subject(root.resolve("original/bundle.json")),
                    "references" to refs,
                    "calibration" to subject(root.resolve("calibration.json")),
                    "qualification" to subject(root.resolve("qualification.json")),
                ),
        )
    }

    fun subject(path: Path): Map<String, String> =
        mapOf("path" to root.relativize(path).toString(), "sha256" to hash(path))

    fun hash(path: Path): String = sha(Files.readAllBytes(path))

    fun write(name: String, value: Any): Path =
        root.resolve(name).also {
            Files.createDirectories(it.parent)
            Files.write(it, mapper.writeValueAsBytes(value))
        }

    fun changeManifest(transform: (ObjectNode) -> Unit) = change("manifest.json", transform)

    /**
     * Reuses only invented tensors; these success claims are transport fixtures, never evidence.
     */
    fun toWinQualified() {
        val behavior = mapper.readTree(manifest.toFile()) as ObjectNode
        behavior.put("kind", "coordinated-inference-asset-1")
        behavior.put("profile", ServerBotProfile.COORDINATED_WIN_SUPPORT_1.version)
        behavior.put("selectorVersion", "incumbent-residual-margin-1")
        behavior.set<ObjectNode>("currentBundle", behavior.remove("candidateBundle"))
        behavior.remove("qualification")
        val asset = write("inference-asset.json", behavior)
        change("qualification.json") { certificate ->
            certificate.put("kind", "coordinated-win-qualification-1")
            certificate.put("profile", ServerBotProfile.COORDINATED_WIN_SUPPORT_1.version)
            certificate.put("inferenceAssetSha256", hash(asset))
            certificate.put("nominationSha256", digest)
            certificate.remove(
                listOf(
                    "candidateBundleSha256",
                    "originalBundleSha256",
                    "calibrationSha256",
                    "references",
                )
            )
            for (name in listOf("mixed", "homogeneous")) {
                (certificate["banks"][name] as ObjectNode).remove("reward99Passed")
            }
        }
        write(
            "manifest.json",
            mapOf(
                "kind" to "coordinated-win-support-preview-1",
                "schemaVersion" to 1,
                "profile" to ServerBotProfile.COORDINATED_WIN_SUPPORT_1.version,
                "inferenceAsset" to subject(asset),
                "qualification" to subject(root.resolve("qualification.json")),
            ),
        )
    }

    /** Invented transport fixture only; no real model, research outcome or certificate is used. */
    fun toRobustQualified(
        version: RobustQualificationVersion = RobustQualificationVersion.LEAGUE_SIX,
        sampler: Map<String, Any>? =
            mapOf(
                "kind" to "near-optimal-token-softmax-1",
                "temperature" to 0.7,
                "maxScoreGap" to 3.0,
                "neighborRadius" to 4,
            ),
        denial: Map<String, Any>? = null,
    ) {
        toWinQualified()
        val robustProfile = ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1.version
        val asset =
            write(
                "robust-inference-asset.json",
                scope +
                    mapOf(
                        "kind" to "coordinated-robust-inference-asset-1",
                        "schemaVersion" to 1,
                        "profile" to robustProfile,
                        "baseInferenceAsset" to subject(root.resolve("inference-asset.json")),
                        "currentBundle" to subject(root.resolve("candidate/bundle.json")),
                        "tokenSelector" to sampler,
                        "expansionDenial" to denial,
                    ),
            )
        val protocol =
            write(
                "research-protocol.json",
                mapOf(
                    "kind" to
                        if (version == RobustQualificationVersion.LEAGUE_SIX)
                            "robust-league-campaign-1"
                        else "leader-denial-campaign-1",
                    "dataMode" to
                        if (version == RobustQualificationVersion.DENIAL_FULL) "FULL" else "SMALL",
                    "methodSha256" to
                        "79a06fe8b7760b8ba7b35a8aebce6f9e3fe5e952f156b0cb1b9795e51d60e468",
                    "confidenceMultiplier" to 2.5758293035489004,
                    "minimumDevelopmentDelta" to 0,
                    "activeGenerations" to 6,
                    "numericRecipe" to
                        mapOf(
                            "kind" to "robust-league-normalized-antithetic-es-2",
                            "generations" to 6,
                            "candidate" to "FINAL_MEAN_6_ONLY",
                        ),
                    "maximumTerminalExecutions" to version.maximumTerminalExecutions,
                    "banks" to
                        listOf("development", "mixed", "homogeneous").associateWith { name ->
                            if (version == RobustQualificationVersion.LEAGUE_SIX) {
                                mapOf("count" to version.pairs)
                            } else {
                                val count =
                                    if (name == "development") version.pairs / 4 else version.pairs
                                mapOf("seeds" to List(count) { it })
                            }
                        },
                ),
            )
        change("qualification.json") { certificate ->
            certificate.put("kind", "coordinated-robust-win-qualification-1")
            certificate.put("profile", robustProfile)
            certificate.put("inferenceAssetSha256", hash(asset))
            certificate.put("evaluationVersion", version.wireValue)
            certificate.set<ObjectNode>("protocol", mapper.valueToTree(subject(protocol)))
            certificate.remove("nominationSha256")
            for (name in listOf("mixed", "homogeneous")) {
                (certificate["banks"][name] as ObjectNode).put("pairs", version.pairs)
            }
        }
        write(
            "manifest.json",
            mapOf(
                "kind" to "coordinated-robust-support-preview-1",
                "schemaVersion" to 1,
                "profile" to robustProfile,
                "inferenceAsset" to subject(asset),
                "qualification" to subject(root.resolve("qualification.json")),
            ),
        )
    }

    fun changeRobustInference(transform: (ObjectNode) -> Unit) {
        change("robust-inference-asset.json", transform)
        val digest = hash(root.resolve("robust-inference-asset.json"))
        changeManifest { (it["inferenceAsset"] as ObjectNode).put("sha256", digest) }
        changeQualification { it.put("inferenceAssetSha256", digest) }
    }

    fun changeResearchProtocol(transform: (ObjectNode) -> Unit) {
        change("research-protocol.json", transform)
        changeQualification {
            (it["protocol"] as ObjectNode).put(
                "sha256",
                hash(root.resolve("research-protocol.json")),
            )
        }
    }

    fun changeInference(transform: (ObjectNode) -> Unit) {
        change("inference-asset.json", transform)
        val digest = hash(root.resolve("inference-asset.json"))
        changeManifest { (it["inferenceAsset"] as ObjectNode).put("sha256", digest) }
        changeQualification { it.put("inferenceAssetSha256", digest) }
    }

    fun changeQualification(transform: (ObjectNode) -> Unit) {
        change("qualification.json", transform)
        changeManifest {
            (it["qualification"] as ObjectNode).put(
                "sha256",
                hash(root.resolve("qualification.json")),
            )
        }
    }

    fun changeCandidate(transform: (ObjectNode) -> Unit) {
        change("candidate/bundle.json", transform)
        val digest = hash(root.resolve("candidate/bundle.json"))
        changeManifest { (it["candidateBundle"] as ObjectNode).put("sha256", digest) }
        changeQualification { it.put("candidateBundleSha256", digest) }
    }

    fun changeHead(head: String, transform: (ObjectNode) -> Unit) {
        val relative = "candidate/heads/$head/policy.json"
        change(relative, transform)
        changeCandidate {
            (it["heads"][head] as ObjectNode).put("sha256", hash(root.resolve(relative)))
        }
    }

    fun changeCalibration(transform: (ObjectNode) -> Unit) {
        change("calibration.json", transform)
        val digest = hash(root.resolve("calibration.json"))
        changeManifest { (it["calibration"] as ObjectNode).put("sha256", digest) }
        changeQualification { it.put("calibrationSha256", digest) }
    }

    fun changeReference(name: String, transform: (ObjectNode) -> Unit) {
        change("references/$name.json", transform)
        val digest = hash(root.resolve("references/$name.json"))
        changeManifest { (it["references"][name] as ObjectNode).put("sha256", digest) }
        changeQualification { (it["references"] as ObjectNode).put(name + "Sha256", digest) }
    }

    private fun change(name: String, transform: (ObjectNode) -> Unit) {
        val document = mapper.readTree(root.resolve(name).toFile()) as ObjectNode
        transform(document)
        write(name, document)
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
