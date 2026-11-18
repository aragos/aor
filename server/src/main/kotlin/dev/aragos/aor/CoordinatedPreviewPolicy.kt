package dev.aragos.aor

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotExpansionModel
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenOrderControl
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.CoordinatedPolicyModels
import dev.aragos.aor.ai.CoordinatedPolicySelector
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.JointPolicyScope
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.PublicLeaderAttackConfig
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicyModel
import dev.aragos.aor.ai.PurchasePolicyScope
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenNeighborAdmission
import dev.aragos.aor.ai.TokenNeighborQuote
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** The source-frozen study95 executable; another native build needs separate qualification. */
internal const val LOCAL_BID_MIXTURE_NATIVE_JAR_SHA256 =
    "6d7a056095ad5f886171823a29b23e466f4f89ad948bfc2f8e6999a71c4a6097"

/**
 * Immutable local asset. All portable subjects are verified and decoded once at startup. The
 * qualification owner establishes strength; this reader binds that receipt to the exact complete
 * Support1 behavior. It never imports training code or silently falls back from an invalid asset.
 */
private class CachedCoordinatedPreviewPolicy(
    override val assetSha256: String,
    override val profile: ServerBotProfile,
    private val reference: BotModel,
    private val selector: CoordinatedPolicySelector,
    override val tokenNeighborMixture: LocalTokenNeighborMixture? = null,
) : ServerBotPolicy {
    override fun choose(view: GameView, observation: BotObservation, seed: Int): String =
        chooseQuoted(view, observation, seed, null)

    override fun choose(
        view: GameView,
        observation: BotObservation,
        seed: Int,
        tokenQuote: TokenNeighborQuote,
    ): String = chooseQuoted(view, observation, seed, tokenQuote)

    private fun chooseQuoted(
        view: GameView,
        observation: BotObservation,
        seed: Int,
        tokenQuote: TokenNeighborQuote?,
    ): String {
        require(profile.supports(observation, BotDifficulty.HARD)) {
            "The coordinated asset only supports its exact qualified current-rules setup."
        }
        fun referenceCommand(): String =
            BotPolicy.choose(observation, BotDifficulty.HARD, seed, reference)
        return if (selector.controls(observation.decision)) {
            selector.choose(view, observation, seed, ::referenceCommand, tokenQuote)
        } else {
            referenceCommand()
        }
    }
}

/** Loads one complete immutable manifest; callers retain the returned inference object. */
object CoordinatedPreviewPolicy {
    fun read(path: Path): ServerBotPolicy = CoordinatedAssetReader(path).read()
}

private enum class PreviewHead {
    CARD,
    PURCHASE,
    EXPANSION,
    TOKEN,
}

/**
 * Exact prospectively approved evaluation sizes; a certificate cannot choose its own sample size.
 */
internal enum class RobustQualificationVersion(
    val wireValue: String,
    val pairs: Int,
    val maximumTerminalExecutions: Int,
) {
    LEAGUE_SIX("study89-SIX1", 2048, 36920),
    DENIAL_FULL("study90-FULL1", 2048, 16464),
    DENIAL_SMALL("study90-SMALL1", 1024, 8272),
    MAC_QUALIFICATION("study91-QUAL1", 2048, 8208),
    MAC_SMALL_QUALIFICATION("study94-QUAL1", 512, 2064),
    LEARNED_BID_SEARCH("study92-BID1", 512, 5208),
    LEADER_SEARCH("study93-LEADER1", 256, 2128),
    LEARNED_BID_SEARCH_REFINED("study92-BID2", 256, 2648),
    LEADER_SEARCH_REFINED("study93-LEADER2", 256, 2128),
    LOCAL_BID_MIXTURE("study95-LOCAL1", 256, 2648);

    val isBidSearch: Boolean
        get() = this == LEARNED_BID_SEARCH || this == LEARNED_BID_SEARCH_REFINED

    val isRefinedSearch: Boolean
        get() = this == LEARNED_BID_SEARCH_REFINED || this == LEADER_SEARCH_REFINED

    val isPolicySearch: Boolean
        get() = isBidSearch || this == LEADER_SEARCH || this == LEADER_SEARCH_REFINED

    val isFixedPolicyQualification: Boolean
        get() = this == MAC_QUALIFICATION || this == MAC_SMALL_QUALIFICATION

    val banks: List<String>
        get() =
            if (isBidSearch || this == LOCAL_BID_MIXTURE)
                listOf("mixed", "homogeneous", "reference")
            else listOf("mixed", "homogeneous")
}

private class CoordinatedAssetReader(path: Path) {
    private val declaredManifestPath = path.toAbsolutePath().normalize()
    private val directory = declaredManifestPath.parent.toRealPath()
    private val manifestPath = directory.resolve(declaredManifestPath.fileName)
    private val mapper =
        ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val documents = mutableMapOf<Path, Document>()
    private var bytesRead = 0L

    private class Document(val path: Path, val bytes: ByteArray, val sha256: String)

    fun read(): ServerBotPolicy {
        val manifest = document(manifestPath)
        val root = node(manifest)
        val robust = text(root, "kind") == "coordinated-robust-support-preview-1"
        val winOnly = text(root, "kind") == "coordinated-win-support-preview-1"
        val selectedProfile =
            when {
                robust -> ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1
                winOnly -> ServerBotProfile.COORDINATED_WIN_SUPPORT_1
                else -> ServerBotProfile.COORDINATED_83_SUPPORT_1
            }
        val robustDefinition = if (robust) robustAsset(manifest, root) else null
        val definition =
            when {
                robustDefinition != null ->
                    subject(robustDefinition, node(robustDefinition)["baseInferenceAsset"]).also {
                        validateInferenceAsset(it)
                    }
                winOnly -> inferenceAsset(manifest, root)
                else -> legacyAsset(manifest, root)
            }
        val behavior = node(definition)
        profile(
            behavior,
            if (robust) ServerBotProfile.COORDINATED_WIN_SUPPORT_1 else selectedProfile,
        )
        val current =
            if (robustDefinition != null) {
                subject(robustDefinition, node(robustDefinition)["currentBundle"])
            } else {
                subject(definition, behavior[if (winOnly) "currentBundle" else "candidateBundle"])
            }
        val original = subject(definition, behavior["originalBundle"])
        val calibrationFile = subject(definition, behavior["calibration"])
        val references = behavior["references"]
        fields(
            references,
            "qualifiedOrdinary",
            "cardM8",
            "purchaseF0",
            "expansionE",
            "tokenControlVersion",
        )
        require(text(references, "tokenControlVersion") == "FIXED_16_TO_15") {
            "The qualified reference requires the exact offered 16-to-15 token control."
        }
        val referenceFiles =
            listOf("qualifiedOrdinary", "cardM8", "purchaseF0", "expansionE").associateWith {
                subject(definition, references[it])
            }
        val certificate = subject(manifest, root["qualification"])
        if (robustDefinition != null) {
            robustQualification(certificate, robustDefinition)
        } else if (winOnly) {
            winQualification(certificate, definition)
        } else {
            qualification(certificate, current, original, calibrationFile, referenceFiles)
        }
        val currentModels = bundle(current)
        val originalModels = bundle(original)
        val scales = calibration(calibrationFile, original, originalModels)
        fun referenceFile(name: String): Document =
            requireNotNull(referenceFiles[name]) {
                "The complete manifest must contain reference $name."
            }
        val packed = ordinary(referenceFile("qualifiedOrdinary"), scoped = true)
        val card = ordinary(referenceFile("cardM8"), scoped = false)
        val purchase = ordinary(referenceFile("purchaseF0"), scoped = false)
        val expansion = ordinary(referenceFile("expansionE"), scoped = false)
        referenceEquivalence(packed, card, expansion)
        val neighborMixture = robustDefinition?.let {
            tokenNeighborMixture(node(it)["tokenNeighborMixture"])
        }
        return CachedCoordinatedPreviewPolicy(
            manifest.sha256,
            selectedProfile,
            packed,
            CoordinatedPolicySelector(
                ordinary = card,
                purchaseBaseline = purchase,
                current = currentModels,
                original = originalModels,
                expansionSelector = IncumbentResidualSelector(scales.first),
                tokenSelector = IncumbentResidualSelector(scales.second),
                support = JointSupportProfile.KNOWN_OWN_CHAOS_1,
                tokenSampler = robustDefinition?.let { tokenSampler(node(it)["tokenSelector"]) },
                expansionDenial =
                    robustDefinition?.let { expansionDenial(node(it)["expansionDenial"]) },
                tokenNeighborMixture = neighborMixture,
            ),
            neighborMixture,
        )
    }

    private fun legacyAsset(manifest: Document, root: JsonNode): Document {
        fields(
            root,
            "kind",
            "schemaVersion",
            "profile",
            "supportProfile",
            "rulesVersion",
            "dataVersion",
            "configuration",
            "candidateBundle",
            "originalBundle",
            "references",
            "calibration",
            "qualification",
        )
        require(
            text(root, "kind") == "coordinated-83-support-preview-1" &&
                integer(root, "schemaVersion") == 1
        ) {
            "A coordinated preview requires its explicit version-one asset manifest."
        }
        return manifest
    }

    private fun inferenceAsset(manifest: Document, root: JsonNode): Document {
        fields(
            root,
            "kind",
            "schemaVersion",
            "profile",
            "inferenceAsset",
            "qualification",
        )
        require(
            integer(root, "schemaVersion") == 1 &&
                text(root, "profile") == ServerBotProfile.COORDINATED_WIN_SUPPORT_1.version
        ) {
            "A win-qualified manifest requires its explicit generic profile and version."
        }
        val asset = subject(manifest, root["inferenceAsset"])
        validateInferenceAsset(asset)
        return asset
    }

    private fun validateInferenceAsset(asset: Document) {
        val value = node(asset)
        fields(
            value,
            "kind",
            "schemaVersion",
            "profile",
            "supportProfile",
            "selectorVersion",
            "rulesVersion",
            "dataVersion",
            "configuration",
            "currentBundle",
            "originalBundle",
            "references",
            "calibration",
        )
        require(
            text(value, "kind") == "coordinated-inference-asset-1" &&
                integer(value, "schemaVersion") == 1 &&
                text(value, "selectorVersion") == "incumbent-residual-margin-1"
        ) {
            "A generic inference asset requires the unchanged incumbent residual selector."
        }
    }

    private fun robustAsset(manifest: Document, root: JsonNode): Document {
        fields(root, "kind", "schemaVersion", "profile", "inferenceAsset", "qualification")
        require(
            integer(root, "schemaVersion") == 1 &&
                text(root, "profile") == ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1.version
        ) {
            "A robust preview requires its explicit stored profile and version."
        }
        val asset = subject(manifest, root["inferenceAsset"])
        val value = node(asset)
        val local = text(value, "kind") == "coordinated-robust-inference-asset-2"
        fields(
            value,
            setOf(
                "kind",
                "schemaVersion",
                "profile",
                "supportProfile",
                "rulesVersion",
                "dataVersion",
                "configuration",
                "baseInferenceAsset",
                "currentBundle",
                "tokenSelector",
                "expansionDenial",
            ) + if (local) setOf("tokenNeighborMixture") else emptySet(),
        )
        require(
            if (local) integer(value, "schemaVersion") == 2
            else
                text(value, "kind") == "coordinated-robust-inference-asset-1" &&
                    integer(value, "schemaVersion") == 1
        ) {
            "A robust inference descriptor must explicitly bind the complete policy."
        }
        profile(value, ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        tokenSampler(value["tokenSelector"])
        expansionDenial(value["expansionDenial"])
        if (local) {
            require(tokenNeighborMixture(value["tokenNeighborMixture"]) != null) {
                "A version-two robust descriptor requires its explicit local token mixture."
            }
        }
        return asset
    }

    private fun tokenNeighborMixture(value: JsonNode?): LocalTokenNeighborMixture? {
        if (value == null || value.isNull) return null
        fields(value, "kind", "departureProbability", "radius", "admission")
        require(text(value, "kind") == LocalTokenNeighborMixture.VERSION) {
            "Only the shared local token neighbor mixture is supported."
        }
        val admission =
            requireNotNull(
                TokenNeighborAdmission.entries.singleOrNull {
                    it.name == text(value, "admission")
                }
            ) {
                "Token neighbor admission requires an explicit supported authority mode."
            }
        return LocalTokenNeighborMixture(
            departureProbability = number(value["departureProbability"], "departure probability"),
            radius = integer(value, "radius"),
            admission = admission,
        )
    }

    private fun tokenSampler(value: JsonNode): NearOptimalTokenSampler? {
        if (value.isNull) return null
        fields(value, "kind", "temperature", "maxScoreGap", "neighborRadius")
        require(text(value, "kind") == NearOptimalTokenSampler.VERSION) {
            "Only the shared near-optimal token sampler is supported."
        }
        return NearOptimalTokenSampler(
            temperature = number(value["temperature"], "token temperature"),
            maxScoreGap = number(value["maxScoreGap"], "token score gap"),
            neighborRadius = integer(value, "neighborRadius"),
        )
    }

    private fun expansionDenial(value: JsonNode): PublicLeaderAttackConfig? {
        if (value.isNull) return null
        fields(value, "kind", "beta", "minimumWinProbability")
        require(text(value, "kind") == PublicLeaderAttackConfig.VERSION) {
            "Only the shared public-leader attack adjustment is supported."
        }
        return PublicLeaderAttackConfig(
            beta = number(value["beta"], "leader-denial beta"),
            minimumWinProbability = number(value["minimumWinProbability"], "attack probability"),
        )
    }

    private fun robustQualification(certificate: Document, asset: Document) {
        val value = node(certificate)
        val version =
            requireNotNull(
                RobustQualificationVersion.entries.singleOrNull {
                    it.wireValue == text(value, "evaluationVersion")
                }
            ) {
                "A robust certificate requires an explicitly registered evaluation version."
            }
        fields(
            value,
            setOf(
                "kind",
                "schemaVersion",
                "passed",
                "complete",
                "profile",
                "supportProfile",
                "rulesVersion",
                "dataVersion",
                "configuration",
                "inferenceAssetSha256",
                "evaluationVersion",
                "protocol",
                "banks",
            ) +
                when {
                    version.isFixedPolicyQualification -> setOf("nomineeEvidence")
                    version.isPolicySearch ||
                        version == RobustQualificationVersion.LOCAL_BID_MIXTURE ->
                        setOf("searchEvidence")
                    else -> emptySet()
                },
        )
        require(
            text(value, "kind") == "coordinated-robust-win-qualification-1" &&
                integer(value, "schemaVersion") == 1 &&
                truth(value, "passed") &&
                truth(value, "complete") &&
                hash(value, "inferenceAssetSha256") == asset.sha256
        ) {
            "Robust qualification must bind this exact complete model, sampler and denial policy."
        }
        profile(value, ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        require(
            (version == RobustQualificationVersion.LOCAL_BID_MIXTURE) ==
                (tokenNeighborMixture(node(asset)["tokenNeighborMixture"]) != null)
        ) {
            "Only the registered study95 qualification may admit a local token mixture."
        }
        val protocolDocument = subject(certificate, value["protocol"])
        val protocol = node(protocolDocument)
        require(
            integer(protocol, "maximumTerminalExecutions") == version.maximumTerminalExecutions
        ) {
            "Qualification must retain its original approved execution budget."
        }
        fields(value["banks"], version.banks.toSet())
        for (name in version.banks) {
            fields(value["banks"][name], "complete", "pairs", "win99Passed", "evidence", "report")
        }
        when (version) {
            RobustQualificationVersion.LEAGUE_SIX -> sixGenerationProtocol(protocol)
            RobustQualificationVersion.DENIAL_FULL,
            RobustQualificationVersion.DENIAL_SMALL -> {
                val full = version == RobustQualificationVersion.DENIAL_FULL
                val mode = if (full) "FULL" else "SMALL"
                require(
                    text(protocol, "kind") == "leader-denial-campaign-1" &&
                        text(protocol, "dataMode") == mode
                ) {
                    "Study90 qualification requires its original approved campaign and data mode."
                }
                require(
                    hash(protocol, "methodSha256") ==
                        "79a06fe8b7760b8ba7b35a8aebce6f9e3fe5e952f156b0cb1b9795e51d60e468" &&
                        number(protocol["confidenceMultiplier"], "confidence multiplier") ==
                            2.5758293035489004 &&
                        number(protocol["minimumDevelopmentDelta"], "development threshold") == 0.0
                ) {
                    "Study90 qualification must retain the original approved method and 99% win rule."
                }
                protocolSeedCount(protocol, "development", if (full) 512 else 256)
            }
            RobustQualificationVersion.MAC_QUALIFICATION,
            RobustQualificationVersion.MAC_SMALL_QUALIFICATION ->
                macQualification(certificate, protocol, asset, version)
            RobustQualificationVersion.LEARNED_BID_SEARCH,
            RobustQualificationVersion.LEADER_SEARCH,
            RobustQualificationVersion.LEARNED_BID_SEARCH_REFINED,
            RobustQualificationVersion.LEADER_SEARCH_REFINED ->
                policySearchQualification(certificate, protocolDocument, asset, version)
            RobustQualificationVersion.LOCAL_BID_MIXTURE ->
                localMixtureQualification(certificate, protocolDocument, asset)
        }
        for (name in version.banks) {
            val bank = value["banks"][name]
            if (
                version == RobustQualificationVersion.LEAGUE_SIX ||
                    version.isFixedPolicyQualification
            ) {
                require(integer(protocolBank(protocol, name), "count") == version.pairs) {
                    "Qualification must retain its registered independent bank counts."
                }
            } else {
                protocolSeedCount(protocol, name, version.pairs)
            }
            if (version.isFixedPolicyQualification) {
                protocolSeedCount(protocol, name, version.pairs)
                val expectedStyle = if (name == "mixed") "BALANCED" else "QUALIFIED"
                val approved = protocolBank(protocol, name)
                val replays = approved["replayIndices"]
                val expectedReplays =
                    if (version == RobustQualificationVersion.MAC_SMALL_QUALIFICATION) {
                        listOf(0, 170, 341, 511)
                    } else {
                        listOf(0, 682, 1365, 2047)
                    }
                require(
                    text(approved, "opponentStyle") == expectedStyle &&
                        replays?.isArray == true &&
                        replays.all { it.isIntegralNumber && it.canConvertToInt() } &&
                        replays.map { it.intValue() } == expectedReplays
                ) {
                    "Fixed-policy qualification requires both original ecologies and reproductions."
                }
            }
            require(
                truth(bank, "complete") &&
                    integer(bank, "pairs") == version.pairs &&
                    truth(bank, "win99Passed")
            ) {
                "Both independent complete banks must pass the registered 99% win gate and pair count."
            }
            subject(certificate, bank["evidence"])
            subject(certificate, bank["report"])
        }
    }

    private fun sixGenerationProtocol(protocol: JsonNode) {
        val recipe =
            requireNotNull(protocol["numericRecipe"]) {
                "Study89 nomination requires its original numeric recipe."
            }
        require(
            text(protocol, "kind") == "robust-league-campaign-1" &&
                integer(protocol, "activeGenerations") == 6 &&
                text(recipe, "kind") == "robust-league-normalized-antithetic-es-2" &&
                integer(recipe, "generations") == 6 &&
                text(recipe, "candidate") == "FINAL_MEAN_6_ONLY"
        ) {
            "Study89 nomination requires the prospectively fixed six-generation final mean."
        }
    }

    /**
     * Archived research paths stay unchanged; only the bound portable evidence copies are opened.
     */
    private fun macQualification(
        certificate: Document,
        protocol: JsonNode,
        asset: Document,
        version: RobustQualificationVersion,
    ) {
        val descriptor = node(asset)
        val small = version == RobustQualificationVersion.MAC_SMALL_QUALIFICATION
        val expectedKind =
            if (small) "fixed-policy-qualification-campaign-1" else "mac-qualification-campaign-1"
        val expectedMethod =
            if (small) {
                "22c172eec9af6683cfe8db382154b53440bdc7db89a77d9cf332ac886c4ed377"
            } else {
                "0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b"
            }
        val sampler =
            requireNotNull(protocol["selector"]) {
                "Fixed-policy qualification must explicitly retain its nominated token selector."
            }
        require(
            text(protocol, "kind") == expectedKind &&
                hash(protocol, "rootProspectiveMethodSha256") == expectedMethod &&
                number(protocol["confidenceMultiplier"], "confidence multiplier") ==
                    2.5758293035489004 &&
                hash(protocol, "candidateBundleSha256") ==
                    hash(descriptor["currentBundle"], "sha256") &&
                hash(protocol, "baseInferenceAssetSha256") ==
                    hash(descriptor["baseInferenceAsset"], "sha256") &&
                tokenSampler(sampler) == tokenSampler(descriptor["tokenSelector"]) &&
                expansionDenial(descriptor["expansionDenial"]) == null
        ) {
            "Fixed-policy qualification must bind its sole nominated policy and original method."
        }
        if (small) {
            require(
                text(protocol, "contract") == "final6-c05-small-512-1" &&
                    truth(protocol, "priorTrialClosureRequired") &&
                    hash(protocol, "priorTrialProtocolSha256") ==
                        "15f47b8113241a3596e2db54446c223d3443dfd39d98e45b0c53c562951a7fac"
            ) {
                "Study94 qualification requires its registered fresh small trial and prior closure."
            }
        }
        hash(protocol, "nominationSha256")
        val names =
            setOf(
                "sourceProtocol",
                "completeTrainingDevelopmentReview",
                "interruptedPhysicalClosure",
                "finalMeanReceipt",
                "completeDevelopmentReport",
            )
        val archived = protocol["nomineeEvidence"]
        val portable = node(certificate)["nomineeEvidence"]
        fields(archived, names)
        fields(portable, names)
        val evidence = names.associateWith { name ->
            subject(certificate, portable[name]).also {
                require(it.sha256 == hash(archived[name], "sha256")) {
                    "Study91 must preserve the exact original89 nomination and development subjects."
                }
            }
        }
        sixGenerationProtocol(
            node(
                requireNotNull(evidence["sourceProtocol"]) {
                    "Study91 nomination must retain its original89 source protocol."
                }
            )
        )
    }

    private fun protocolBank(protocol: JsonNode, name: String): JsonNode =
        requireNotNull(protocol["banks"]?.let { it[name] }) {
            "The approved protocol must retain each original world bank."
        }

    /** Raw research subjects stay unchanged; portable copies alone are opened by this reader. */
    private fun policySearchQualification(
        certificate: Document,
        protocolDocument: Document,
        asset: Document,
        version: RobustQualificationVersion,
    ) {
        val protocol = node(protocolDocument)
        val descriptor = node(asset)
        val bidSearch = version.isBidSearch
        val refined = version.isRefinedSearch
        val study = if (bidSearch) 92 else 93
        val prefix = if (bidSearch) "learned-bid-search" else "mac-leader-search"
        val method =
            if (bidSearch && refined) {
                "193edfd32bad8c1143350ed51831a6b229f731cc2cfeb25ec13c791994d822f9"
            } else if (refined) {
                "0ee2ed67c272bc5e9035d7ad77ba8204b6a53d81a3eb9cefc78fcebe09c4aef2"
            } else if (bidSearch) {
                "82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36"
            } else {
                "20a3a84a7c03a601fffffe20fe08c691ff22e691b87b992b22a1d5fb17ee79fa"
            }
        require(
            text(protocol, "kind") == "$prefix-1" &&
                integer(protocol, "study") == study &&
                hash(protocol, "methodSha256") == method &&
                number(protocol["confidenceMultiplier"], "confidence multiplier") ==
                    2.5758293035489004 &&
                integer(protocol, "chunkSize") == 256 &&
                hash(protocol, "nativeJarSha256") ==
                    "14245cba2d1dbd4e62780371efd84d4416bd91592ceca876449d7f417756463f" &&
                hash(protocol, "inferenceAssetSha256") ==
                    hash(descriptor["baseInferenceAsset"], "sha256")
        ) {
            "Policy search qualification requires its exact original method and inference foundation."
        }
        val cases = policySearchCases(protocol, bidSearch)
        policySearchBanks(protocol, version)
        if (!bidSearch) {
            fields(protocol["anchor"], "origin", "bundleSha256", "selector", "qualificationSha256")
        }
        val evidenceNames =
            setOf(
                "profile",
                "sourceReadiness",
                "selection",
                "developmentReport",
                "completionAudit",
                "physicalClosure",
            ) +
                if (bidSearch && refined) {
                    setOf("previousTerminal", "previousClosure")
                } else if (bidSearch) {
                    emptySet()
                } else {
                    setOf(
                        "anchorFreeze",
                        "previousTerminal",
                        "previousClosure",
                        "study91Closure",
                        "anchorQualification",
                    ) +
                        (if (text(protocol["anchor"], "origin") == "87") emptySet()
                        else setOf("anchorSourceProtocol")) +
                        if (refined && text(protocol["anchor"], "origin") != "92")
                            setOf("study94Closure")
                        else emptySet()
                }
        val portable = node(certificate)["searchEvidence"]
        fields(portable, evidenceNames)
        val evidence = evidenceNames.associateWith { subject(certificate, portable[it]) }
        fun retained(name: String): Document =
            requireNotNull(evidence[name]) {
                "Search qualification requires portable $name evidence."
            }
        val profileDocument = retained("profile")
        val profile = node(profileDocument)
        val sourceDocument = retained("sourceReadiness")
        val source = node(sourceDocument)
        val selectionDocument = retained("selection")
        val selection = node(selectionDocument)
        val developmentDocument = retained("developmentReport")
        val development = node(developmentDocument)
        val auditDocument = retained("completionAudit")
        val audit = node(auditDocument)
        val closed = node(retained("physicalClosure"))
        for (name in listOf("candidateBundle", "inferenceAsset", "nomination", "nativeJar")) {
            fields(profile[name], "path", "sha256")
        }
        if (bidSearch && !refined) fields(source["method"], "path", "sha256")
        require(
            text(profile, "kind") == "mac-policy-search-host-1" &&
                integer(profile, "study") == study &&
                hash(profile["candidateBundle"], "sha256") ==
                    hash(descriptor["currentBundle"], "sha256") &&
                hash(profile["inferenceAsset"], "sha256") ==
                    hash(descriptor["baseInferenceAsset"], "sha256") &&
                hash(profile["nomination"], "sha256") == hash(protocol, "nominationSha256") &&
                hash(profile["nativeJar"], "sha256") == hash(protocol, "nativeJarSha256") &&
                hash(source, "nativeJarSha256") == hash(protocol, "nativeJarSha256") &&
                text(source, "kind") ==
                    (if (refined) "refined-mac-policy-search-source-readiness-1"
                    else "$prefix-runner-source-readiness-1") &&
                truth(source, "passed") &&
                truth(source, "sourceOnly") &&
                (!refined || integer(source, "study") == study) &&
                (if (bidSearch && !refined) hash(source["method"], "sha256")
                else hash(source, "methodSha256")) == method
        ) {
            "Search qualification must retain its actual model, original profile and source closure."
        }
        archivedPin(profile, "pins", protocolDocument)
        archivedPin(profile, "pins", sourceDocument)
        for (document in
            listOf(profileDocument, protocolDocument, selectionDocument, developmentDocument)) {
            archivedPin(audit, "artifactPins", document)
        }
        require(
            text(audit, "kind") == "$prefix-original-audit-1" &&
                truth(audit, "passed") &&
                truth(audit, "complete") &&
                truth(audit, "qualified") &&
                hash(audit, "protocolSha256") == protocolDocument.sha256 &&
                hash(audit, "profileSha256") == profileDocument.sha256 &&
                truth(audit, "originalReproductionsVerified") &&
                truth(audit, "allWin99StatisticsRecomputed") &&
                truth(audit, "allObservedConcentrationStatisticsRecomputed") &&
                integer(audit, "terminalExecutions") == version.maximumTerminalExecutions &&
                text(closed, "kind") == "$prefix-physical-closure-1" &&
                truth(closed, "passed") &&
                truth(closed, "scienceComplete") &&
                truth(closed, "qualified") &&
                truth(closed, "allOwnedProcessesExited") &&
                !truth(closed, "originalClocksRenewed") &&
                integer(closed, "auditReruns") == 0 &&
                integer(closed, "actualCompletedLowerBound") == version.maximumTerminalExecutions &&
                integer(closed, "actualAttemptedUpperBound") == version.maximumTerminalExecutions &&
                hash(closed, "protocolSha256") == protocolDocument.sha256 &&
                hash(closed, "clockSha256") == hash(audit, "clockSha256")
        ) {
            "Only the complete sealed original search audit and physical closure may qualify."
        }
        archivedPin(closed, "artifactPins", auditDocument)
        if (bidSearch && refined) {
            val protected = audit["protectedReservation"]
            fields(
                protected,
                "fullReservedWorlds",
                "activeReservedWorlds",
                "unusedReservedWorlds",
                "fullScheduleSha256",
            )
            require(
                integer(protected, "fullReservedWorlds") == 1664 &&
                    integer(protected, "activeReservedWorlds") == 832 &&
                    integer(protected, "unusedReservedWorlds") == 832 &&
                    hash(protected, "fullScheduleSha256") == hash(protocol, "scheduleSha256")
            ) {
                "The original BID2 audit must retain every active and protected unused world."
            }
        }
        require(
            truth(selection, "complete") &&
                hash(selection, "protocolSha256") == protocolDocument.sha256 &&
                truth(development, "complete") &&
                text(development, "bank") == "development" &&
                hash(development, "protocolSha256") == protocolDocument.sha256
        ) {
            "Search nomination requires its original complete development and selection subjects."
        }
        fields(development["cases"], cases.map { text(it, "id") }.toSet())
        val eligible = cases.filter { case ->
            val report = development["cases"][text(case, "id")]
            searchWin(report, if (bidSearch && !refined) 128 else 64)
            val concentrationPassed = !bidSearch || observedConcentration(report)
            number(report["wins"]["mean"], "development win mean") > 0.0 && concentrationPassed
        }
        val selected =
            requireNotNull(
                eligible.maxByOrNull { case ->
                    number(development["cases"][text(case, "id")]["wins"]["mean"], "win mean")
                }
            ) {
                "A qualified search requires one positive eligible complete-development nominee."
            }
        require(selection["case"] == selected && audit["selectedCase"] == selected) {
            "Search nomination must reproduce its fixed win-mean rule and stable case-order ties."
        }
        if (bidSearch) {
            val base = node(subject(asset, descriptor["baseInferenceAsset"]))
            require(
                tokenSampler(descriptor["tokenSelector"]) == tokenSampler(selected["selector"]) &&
                    expansionDenial(descriptor["expansionDenial"]) == null &&
                    hash(protocol, "candidateBundleSha256") ==
                        hash(descriptor["currentBundle"], "sha256") &&
                    hash(protocol, "learnedControlBundleSha256") ==
                        hash(protocol, "candidateBundleSha256") &&
                    tokenSampler(searchNode(protocol, "learnedControlSelector")) ==
                        NearOptimalTokenSampler(.35, 3.0, 4) &&
                    tokenSampler(searchNode(protocol, "oldControlSelector")) == null &&
                    hash(protocol, "oldControlBundleSha256") ==
                        hash(base["currentBundle"], "sha256") &&
                    number(protocol["positiveBidConcentrationMaximum"], "concentration maximum") ==
                        .8
            ) {
                "Study92 qualification covers only its selected sampler and unchanged four-head policy."
            }
            if (refined) refinedSearchPrerequisite(protocol, evidence)
        } else {
            leaderSearchAnchor(protocol, asset, selected, evidence, refined)
        }
        for (name in version.banks) {
            val bank = node(certificate)["banks"][name]
            require(subject(certificate, bank["evidence"]).sha256 == auditDocument.sha256) {
                "Every search bank must retain its sealed original complete audit."
            }
            val reportDocument = subject(certificate, bank["report"])
            archivedPin(audit, "artifactPins", reportDocument)
            val report = node(reportDocument)
            require(
                truth(report, "complete") &&
                    text(report, "bank") == name &&
                    hash(report, "protocolSha256") == protocolDocument.sha256
            ) {
                "Each search bank requires its complete original report and protocol identity."
            }
            fields(report["cases"], text(selected, "id"))
            val statistics = report["cases"][text(selected, "id")]
            require(searchWin(statistics, version.pairs) > 0.0) {
                "Every search confirmation bank must pass its original strict 99% win gate."
            }
            if (bidSearch && name != "reference") {
                require(observedConcentration(statistics)) {
                    "Both learned-base banks must pass study92's observed positive-bid concentration gate."
                }
            }
        }
    }

    /** Study95 adds a bounded departure kernel to the unchanged qualified94 policy. */
    private fun localMixtureQualification(
        certificate: Document,
        protocolDocument: Document,
        asset: Document,
    ) {
        val protocol = node(protocolDocument)
        val descriptor = node(asset)
        val foundation = node(subject(asset, descriptor["baseInferenceAsset"]))
        require(
            text(protocol, "kind") == "local-bid-mixture-1" &&
                integer(protocol, "study") == 95 &&
                hash(protocol, "nativeJarSha256") == LOCAL_BID_MIXTURE_NATIVE_JAR_SHA256 &&
                hash(protocol, "methodSha256") ==
                    "4c757424a2700de67d2f2dc6ba6b2e7e9bef8c76a96dc714eec158578698179f" &&
                number(protocol["confidenceMultiplier"], "confidence multiplier") ==
                    2.5758293035489004 &&
                integer(protocol, "fullReservedWorlds") == 832 &&
                integer(protocol, "activeReservedWorlds") == 832 &&
                integer(protocol, "unusedReservedWorlds") == 0 &&
                tokenSampler(descriptor["tokenSelector"]) == NearOptimalTokenSampler(.35, 3.0, 4) &&
                tokenSampler(searchNode(protocol, "baseTokenSelector")) ==
                    tokenSampler(descriptor["tokenSelector"]) &&
                expansionDenial(searchNode(protocol, "baseExpansionDenial")) == null &&
                expansionDenial(descriptor["expansionDenial"]) == null &&
                hash(protocol, "controlAndRivalsBundleSha256") ==
                    hash(protocol, "candidateBundleSha256") &&
                hash(protocol, "reference87BundleSha256") ==
                    hash(foundation["currentBundle"], "sha256") &&
                number(protocol["positiveBidConcentrationMaximum"], "concentration maximum") ==
                    .8 &&
                hash(protocol, "candidateBundleSha256") ==
                    hash(descriptor["currentBundle"], "sha256") &&
                hash(protocol, "inferenceAssetSha256") ==
                    hash(descriptor["baseInferenceAsset"], "sha256")
        ) {
            "Study95 requires its fixed local-mixture method, original832 worlds and qualified94 base."
        }
        val names =
            setOf(
                "profile",
                "sourceReadiness",
                "selection",
                "developmentReport",
                "completionAudit",
                "physicalClosure",
                "previousTerminal",
                "previousClosure",
                "nativeSourceReadiness",
                "qualifiedBaseline94Protocol",
                "qualifiedBaseline94Terminal",
                "qualifiedBaseline94Closure",
                "qualifiedBaseline94CompletionAudit",
            )
        val portable = node(certificate)["searchEvidence"]
        fields(portable, names)
        val evidence = names.associateWith { subject(certificate, portable[it]) }
        fun retained(name: String): Document =
            requireNotNull(evidence[name]) {
                "Study95 requires portable $name original evidence."
            }
        val profileDocument = retained("profile")
        val profile = node(profileDocument)
        val source = node(retained("sourceReadiness"))
        val nativeSource = node(retained("nativeSourceReadiness"))
        val selectionDocument = retained("selection")
        val selection = node(selectionDocument)
        val developmentDocument = retained("developmentReport")
        val development = node(developmentDocument)
        val auditDocument = retained("completionAudit")
        val audit = node(auditDocument)
        val closed = node(retained("physicalClosure"))
        require(
            text(profile, "kind") == "mac-policy-search-host-1" &&
                integer(profile, "study") == 95 &&
                hash(profile["candidateBundle"], "sha256") ==
                    hash(protocol, "candidateBundleSha256") &&
                hash(profile["inferenceAsset"], "sha256") ==
                    hash(protocol, "inferenceAssetSha256") &&
                hash(profile["nativeJar"], "sha256") == hash(protocol, "nativeJarSha256") &&
                text(source, "kind") == "local-bid-mixture-source-readiness-1" &&
                truth(source, "passed") &&
                truth(source, "sourceOnly") &&
                hash(source, "methodSha256") == hash(protocol, "methodSha256") &&
                hash(source, "nativeJarSha256") == hash(protocol, "nativeJarSha256") &&
                text(nativeSource, "kind") ==
                    "local-token-mixture-native-source-final-readiness-1" &&
                truth(nativeSource, "passed") &&
                truth(nativeSource, "sourceOnly") &&
                hash(nativeSource["nativeJar"], "sha256") == hash(protocol, "nativeJarSha256") &&
                retained("nativeSourceReadiness").sha256 ==
                    hash(protocol["nativeSourceReadiness"], "sha256")
        ) {
            "Study95 must retain the exact original native source, immutable model and host profile."
        }
        archivedPin(profile, "pins", protocolDocument)
        archivedPin(profile, "pins", retained("sourceReadiness"))
        for (document in
            listOf(profileDocument, protocolDocument, selectionDocument, developmentDocument)) {
            archivedPin(audit, "artifactPins", document)
        }
        require(
            text(audit, "kind") == "local-bid-mixture-original-audit-1" &&
                truth(audit, "passed") &&
                truth(audit, "complete") &&
                truth(audit, "qualified") &&
                hash(audit, "protocolSha256") == protocolDocument.sha256 &&
                hash(audit, "profileSha256") == profileDocument.sha256 &&
                truth(audit, "originalReproductionsVerified") &&
                truth(audit, "allWin99StatisticsRecomputed") &&
                truth(audit, "allObservedConcentrationStatisticsRecomputed") &&
                integer(audit, "terminalExecutions") == 2648 &&
                text(closed, "kind") == "local-bid-mixture-physical-closure-1" &&
                truth(closed, "passed") &&
                truth(closed, "scienceComplete") &&
                truth(closed, "qualified") &&
                truth(closed, "allOwnedProcessesExited") &&
                !truth(closed, "originalClocksRenewed") &&
                integer(closed, "auditReruns") == 0 &&
                integer(closed, "actualCompletedLowerBound") == 2648 &&
                integer(closed, "actualAttemptedUpperBound") == 2648 &&
                hash(closed, "protocolSha256") == protocolDocument.sha256 &&
                hash(closed, "clockSha256") == hash(audit, "clockSha256")
        ) {
            "Study95 qualification requires its sole complete original audit and physical closure."
        }
        archivedPin(closed, "artifactPins", auditDocument)
        localMixtureBaseline(protocol, descriptor, evidence)
        localMixtureBanks(protocol)
        val cases = protocol["caseConfigs"]
        require(cases?.isArray == true && cases.size() == 8) {
            "Study95 requires all eight original ordered local-mixture configurations."
        }
        cases.forEachIndexed { index, case ->
            fields(case, "id", "tokenNeighborMixture")
            require(
                text(case, "id") == "n" + index.toString().padStart(2, '0') &&
                    tokenNeighborMixture(case["tokenNeighborMixture"]) ==
                        LocalTokenNeighborMixture(
                            departureProbability = if (index % 2 == 0) .25 else .5,
                            radius = if (index % 4 < 2) 1 else 2,
                            admission =
                                if (index < 4) TokenNeighborAdmission.OFFERED_ONLY
                                else TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS,
                        )
            ) {
                "Study95 may nominate only one of its eight frozen local-mixture configurations."
            }
        }
        require(
            truth(selection, "complete") &&
                hash(selection, "protocolSha256") == protocolDocument.sha256 &&
                truth(development, "complete") &&
                text(development, "bank") == "development" &&
                hash(development, "protocolSha256") == protocolDocument.sha256
        ) {
            "Study95 nomination requires its complete original development and selection records."
        }
        fields(development["cases"], cases.map { text(it, "id") }.toSet())
        val eligible = cases.filter { case ->
            val report = development["cases"][text(case, "id")]
            searchWin(report, 64)
            number(report["wins"]["mean"], "development win mean") > 0.0 &&
                observedConcentration(report)
        }
        val selected =
            requireNotNull(
                eligible.maxByOrNull { case ->
                    number(
                        development["cases"][text(case, "id")]["wins"]["mean"],
                        "development win mean",
                    )
                }
            ) {
                "Study95 requires one positive eligible complete-development nominee."
            }
        require(
            selection["case"] == selected &&
                audit["selectedCase"] == selected &&
                tokenNeighborMixture(descriptor["tokenNeighborMixture"]) ==
                    tokenNeighborMixture(selected["tokenNeighborMixture"])
        ) {
            "Study95 must retain its sole highest eligible win-mean nominee and exact mixture."
        }
        for (name in listOf("mixed", "homogeneous", "reference")) {
            val bank = node(certificate)["banks"][name]
            require(subject(certificate, bank["evidence"]).sha256 == auditDocument.sha256) {
                "Every study95 confirmation bank requires its same sealed original audit."
            }
            val reportDocument = subject(certificate, bank["report"])
            archivedPin(audit, "artifactPins", reportDocument)
            val report = node(reportDocument)
            require(
                truth(report, "complete") &&
                    text(report, "bank") == name &&
                    hash(report, "protocolSha256") == protocolDocument.sha256
            ) {
                "Study95 confirmation reports must retain their original protocol and bank."
            }
            fields(report["cases"], text(selected, "id"))
            val statistics = report["cases"][text(selected, "id")]
            require(
                searchWin(statistics, 256) > 0.0 &&
                    (name == "reference" || observedConcentration(statistics))
            ) {
                "Study95 requires all three win gates and both learned-base concentration gates."
            }
        }
    }

    private fun localMixtureBanks(protocol: JsonNode) {
        val counts =
            mapOf("development" to 64, "mixed" to 256, "homogeneous" to 256, "reference" to 256)
        val reserved = protocol["reservedSchedule"]
        require(text(reserved, "kind") == "local-bid-mixture-schedule-1") {
            "Study95 must retain its full original reserved schedule."
        }
        fields(protocol["banks"], counts.keys)
        fields(reserved["banks"], counts.keys)
        val allSeeds = counts.flatMap { (name, count) ->
            val bank = protocolBank(protocol, name)
            fields(bank, "name", "worlds", "seeds", "opponentStyle", "replayIndices", "control")
            val original = reserved["banks"][name]
            fields(original, "nativeOffset", "count", "seeds")
            protocolSeedCount(protocol, name, count)
            val indices =
                if (name == "development") listOf(0, 21, 42, 63) else listOf(0, 85, 170, 255)
            val replays = bank["replayIndices"]
            require(
                text(bank, "name") == name &&
                    integer(bank, "worlds") == count &&
                    integer(original, "count") == count &&
                    original["seeds"] == bank["seeds"] &&
                    text(bank, "opponentStyle") ==
                        if (name in setOf("development", "mixed")) "BALANCED" else "QUALIFIED"
            ) {
                "Study95 must retain its exact original832 worlds and ecologies."
            }
            require(
                text(bank, "control") == if (name == "reference") "QUALIFIED87" else "QUALIFIED94"
            ) {
                "Study95 must retain the qualified94 banks and separate qualified87 regression control."
            }
            require(
                replays?.isArray == true &&
                    replays.all { it.isIntegralNumber && it.canConvertToInt() } &&
                    replays.map(JsonNode::intValue) == indices &&
                    bank["seeds"].all { it.isIntegralNumber && it.canConvertToInt() }
            ) {
                "Study95 requires the fixed original integer worlds and reproduction indices."
            }
            bank["seeds"].map(JsonNode::intValue)
        }
        require(allSeeds.distinct().size == 832) {
            "Study95 world banks must be mutually disjoint."
        }
    }

    private fun localMixtureBaseline(
        protocol: JsonNode,
        descriptor: JsonNode,
        evidence: Map<String, Document>,
    ) {
        fun retained(name: String): Document =
            requireNotNull(evidence[name]) {
                "Study95 must retain its original qualified94 and preceding93 evidence."
            }
        fields(
            protocol["qualifiedBaseline94"],
            "protocol",
            "terminal",
            "closure",
            "completionAudit",
        )
        for ((key, name) in
            mapOf(
                "protocol" to "Protocol",
                "terminal" to "Terminal",
                "closure" to "Closure",
                "completionAudit" to "CompletionAudit",
            )) {
            require(
                retained("qualifiedBaseline94$name").sha256 ==
                    hash(protocol["qualifiedBaseline94"][key], "sha256")
            ) {
                "Portable study94 evidence must match its prospectively frozen original subject."
            }
        }
        val original = node(retained("qualifiedBaseline94Protocol"))
        val terminal = node(retained("qualifiedBaseline94Terminal"))
        val auditDocument = retained("qualifiedBaseline94CompletionAudit")
        val audit = node(auditDocument)
        val closed = node(retained("qualifiedBaseline94Closure"))
        require(
            text(original, "kind") == "fixed-policy-qualification-campaign-1" &&
                text(original, "contract") == "final6-c05-small-512-1" &&
                integer(original, "maximumTerminalExecutions") == 2064 &&
                hash(original, "rootProspectiveMethodSha256") ==
                    "22c172eec9af6683cfe8db382154b53440bdc7db89a77d9cf332ac886c4ed377" &&
                hash(original, "candidateBundleSha256") ==
                    hash(descriptor["currentBundle"], "sha256") &&
                tokenSampler(original["selector"]) == tokenSampler(descriptor["tokenSelector"]) &&
                text(terminal, "kind") == "fixed-policy-qualification-terminal-1" &&
                text(terminal, "status") == "completed" &&
                truth(terminal, "qualified") &&
                text(audit, "kind") == "fixed-policy-qualification-original-audit-1" &&
                truth(audit, "passed") &&
                truth(audit, "complete") &&
                truth(audit, "qualified") &&
                integer(audit, "terminalExecutions") == 2064 &&
                hash(audit, "protocolSha256") == retained("qualifiedBaseline94Protocol").sha256 &&
                hash(terminal, "protocolSha256") == hash(audit, "protocolSha256") &&
                text(closed, "kind") == "mac-qualification-physical-closure-1" &&
                truth(closed, "passed") &&
                truth(closed, "qualified") &&
                truth(closed, "allOwnedProcessesExited") &&
                !truth(closed, "originalClocksRenewed") &&
                integer(closed, "auditReruns") == 0 &&
                hash(closed, "protocolSha256") == hash(audit, "protocolSha256")
        ) {
            "Study95 must retain the complete independently qualified and physically closed94 baseline."
        }
        archivedPin(closed, "artifactPins", auditDocument)
        val priorTerminalDocument = retained("previousTerminal")
        val priorClosedDocument = retained("previousClosure")
        val priorTerminal = node(priorTerminalDocument)
        val priorClosed = node(priorClosedDocument)
        require(
            priorTerminalDocument.sha256 == hash(protocol["parentTerminal"], "sha256") &&
                priorClosedDocument.sha256 == hash(protocol["parentClosure"], "sha256") &&
                text(priorTerminal, "kind") == "mac-leader-search-terminal-1" &&
                integer(priorTerminal, "study") == 93 &&
                integer(priorClosed, "study") == 93 &&
                text(priorTerminal, "status") in setOf("completed", "failed", "interrupted") &&
                text(priorClosed, "kind") == "mac-leader-search-physical-closure-1" &&
                truth(priorClosed, "passed") &&
                truth(priorClosed, "allOwnedProcessesExited") &&
                !truth(priorClosed, "originalClocksRenewed") &&
                integer(priorClosed, "auditReruns") == 0 &&
                hash(priorClosed, "protocolSha256") == hash(priorTerminal, "protocolSha256")
        ) {
            "Study95 must start only after its exact original93 predecessor is physically closed."
        }
        archivedPin(priorClosed, "artifactPins", priorTerminalDocument)
    }

    private fun archivedPin(owner: JsonNode, key: String, document: Document) {
        val pins = owner[key]
        require(
            pins?.isObject == true &&
                pins.properties().any {
                    it.value.isTextual && it.value.textValue() == document.sha256
                }
        ) {
            "Portable search evidence must retain the exact original archived subject SHA-256."
        }
    }

    private fun refinedSearchPrerequisite(protocol: JsonNode, evidence: Map<String, Document>) {
        fun retained(name: String): Document =
            requireNotNull(evidence[name]) { "Refined search requires portable $name evidence." }
        val terminalDocument = retained("previousTerminal")
        val closedDocument = retained("previousClosure")
        val terminal = node(terminalDocument)
        val closed = node(closedDocument)
        fields(protocol["parentTerminal"], "path", "sha256")
        fields(protocol["parentClosure"], "path", "sha256")
        require(
            terminalDocument.sha256 == hash(protocol["parentTerminal"], "sha256") &&
                closedDocument.sha256 == hash(protocol["parentClosure"], "sha256") &&
                text(terminal, "kind") == "fixed-policy-qualification-terminal-1" &&
                text(terminal, "status") in setOf("completed", "failed", "interrupted") &&
                text(closed, "kind") == "mac-qualification-physical-closure-1" &&
                truth(closed, "passed") &&
                truth(closed, "allOwnedProcessesExited") &&
                !truth(closed, "originalClocksRenewed") &&
                integer(closed, "auditReruns") == 0 &&
                hash(protocol, "parentTrialProtocolSha256") ==
                    "140427b0db6635b7842b1b51213a5db174382e8088a0d43fc2917aa7a1c37e5e" &&
                hash(terminal, "protocolSha256") == hash(protocol, "parentTrialProtocolSha256") &&
                hash(closed, "protocolSha256") == hash(terminal, "protocolSha256")
        ) {
            "Study92 BID2 must follow the exact physically closed94 trial, never reused91 metrics."
        }
        archivedPin(closed, "artifactPins", terminalDocument)
    }

    private fun searchWin(report: JsonNode, expected: Int): Double {
        val wins = searchNode(report, "wins")
        require(integer(wins, "count") == expected) {
            "Search reports must retain the registered complete paired-world count."
        }
        return number(wins["lower99"], "original win99 lower bound")
    }

    private fun searchNode(owner: JsonNode, name: String): JsonNode =
        requireNotNull(owner[name]) { "Search evidence must retain its original $name field." }

    private fun observedConcentration(report: JsonNode): Boolean {
        val summary = report["observedBids"]?.let { it["CANDIDATE"] }
        require(summary?.isObject == true && summary["counts"]?.isObject == true) {
            "Study92 requires the original controlled positive-bid histogram."
        }
        val counts =
            summary["counts"]
                .properties()
                .map { entry ->
                    val amount = entry.key.toIntOrNull()
                    require(
                        amount != null &&
                            amount.toString() == entry.key &&
                            entry.value.isIntegralNumber &&
                            entry.value.canConvertToInt() &&
                            entry.value.intValue() > 0
                    ) {
                        "Recorded bid amounts and counts must retain their canonical integral values."
                    }
                    amount to entry.value.intValue().toLong()
                }
                .filter { it.first > 0 }
                .map { it.second }
        val total = counts.sum()
        val maximum = counts.maxOrNull() ?: 0L
        require(
            total > 0 &&
                total <= 12_000L * integer(report["wins"], "count") &&
                integer(summary, "positiveCount").toLong() == total &&
                integer(summary, "maximumSinglePositiveAmountCount").toLong() == maximum &&
                number(summary["maximumSinglePositiveAmountShare"], "positive bid concentration") ==
                    maximum.toDouble() / total
        ) {
            "Observed bid concentration must reproduce the complete original positive-bid counts."
        }
        val passed = maximum * 5 <= total * 4
        require(truth(summary, "observedConcentrationPassed") == passed) {
            "The observed concentration flag must match the fixed80% threshold."
        }
        return passed
    }

    private fun policySearchCases(protocol: JsonNode, bidSearch: Boolean): List<JsonNode> {
        val cases = protocol["caseConfigs"]
        require(cases?.isArray == true && cases.size() == 8) {
            "A registered policy search requires all eight original ordered configurations."
        }
        return cases.mapIndexed { index, case ->
            val id = (if (bidSearch) "s" else "l") + index.toString().padStart(2, '0')
            require(text(case, "id") == id) {
                "Policy search configurations must retain their fixed order."
            }
            if (bidSearch) {
                fields(case, "id", "selector")
                val wide = index >= 4
                require(
                    tokenSampler(case["selector"]) ==
                        NearOptimalTokenSampler(
                            temperature = listOf(.7, 1.5, 3.0, 8.0)[index % 4],
                            maxScoreGap = if (wide) 16.0 else 6.0,
                            neighborRadius = if (wide) 8 else 4,
                        )
                ) {
                    "Study92 may select only one of its eight frozen token samplers."
                }
            } else {
                fields(case, "id", "expansionDenial")
                require(
                    expansionDenial(case["expansionDenial"]) ==
                        PublicLeaderAttackConfig(
                            beta = listOf(.25, .5, 1.0, 2.0)[index / 2],
                            minimumWinProbability = if (index % 2 == 0) .5 else .8,
                        )
                ) {
                    "Study93 may select only one of its eight frozen public-leader bonuses."
                }
            }
            case
        }
    }

    private fun policySearchBanks(protocol: JsonNode, version: RobustQualificationVersion) {
        val bidSearch = version.isBidSearch
        val prefix = bidSearch && version.isRefinedSearch
        val names = setOf("development") + version.banks
        fields(protocol["banks"], names)
        val reserved = protocol["reservedSchedule"]
        require(
            text(reserved, "kind") ==
                if (bidSearch) "learned-bid-search-schedule-1" else "mac-leader-search-schedule-1"
        ) {
            "Policy search must retain the original complete reserved schedule."
        }
        fields(reserved["banks"], names)
        val seeds = names.flatMap { name ->
            val count =
                if (name == "development") {
                    if (bidSearch && !prefix) 128 else 64
                } else version.pairs
            val bank = protocolBank(protocol, name)
            fields(
                bank,
                setOf("name", "worlds", "seeds", "opponentStyle", "replayIndices") +
                    if (bidSearch) setOf("control") else emptySet(),
            )
            protocolSeedCount(protocol, name, count)
            val values = bank["seeds"]
            val replays = bank["replayIndices"]
            val expectedReplays =
                when {
                    name == "development" && bidSearch && !prefix -> listOf(0, 42, 85, 127)
                    name == "development" -> listOf(0, 21, 42, 63)
                    bidSearch && !prefix -> listOf(0, 170, 341, 511)
                    else -> listOf(0, 85, 170, 255)
                }
            val style = if (name in setOf("development", "mixed")) "BALANCED" else "QUALIFIED"
            require(
                text(bank, "name") == name &&
                    integer(bank, "worlds") == count &&
                    text(bank, "opponentStyle") == style &&
                    values.all { it.isIntegralNumber && it.canConvertToInt() } &&
                    replays?.isArray == true &&
                    replays.all { it.isIntegralNumber && it.canConvertToInt() } &&
                    replays.map(JsonNode::intValue) == expectedReplays &&
                    reserved["banks"][name]["seeds"]?.isArray == true &&
                    reserved["banks"][name]["seeds"].take(count) == values.toList() &&
                    integer(reserved["banks"][name], "count") ==
                        (if (prefix) {
                            if (name == "development") 128 else 512
                        } else count) &&
                    reserved["banks"][name]["seeds"].size() ==
                        integer(reserved["banks"][name], "count")
            ) {
                "Policy search must retain its exact complete worlds, ecologies and original reproductions."
            }
            if (bidSearch) {
                require(text(bank, "control") == if (name == "reference") "OLD87" else "LEARNED") {
                    "Study92 must preserve the learned-base banks and separate qualified87 regression bank."
                }
            }
            values.map(JsonNode::intValue)
        }
        require(seeds.distinct().size == seeds.size) {
            "All original policy-search banks must remain disjoint."
        }
        if (prefix) {
            val full = names.flatMap { name ->
                reserved["banks"][name]["seeds"].map { seed ->
                    require(seed.isIntegralNumber && seed.canConvertToInt()) {
                        "Protected seed suffixes require signed integers."
                    }
                    seed.intValue()
                }
            }
            require(
                integer(protocol, "fullReservedWorlds") == 1664 &&
                    integer(protocol, "activeReservedWorlds") == 832 &&
                    integer(protocol, "unusedReservedWorlds") == 832 &&
                    full.size == 1664 &&
                    full.distinct().size == 1664 &&
                    seeds.size == 832
            ) {
                "Study92 BID2 must retain all1664 original worlds and protect the832 unused suffixes."
            }
        }
    }

    private fun leaderSearchAnchor(
        protocol: JsonNode,
        asset: Document,
        selected: JsonNode,
        evidence: Map<String, Document>,
        refined: Boolean,
    ) {
        fun retained(name: String): Document =
            requireNotNull(evidence[name]) {
                "Leader qualification requires portable $name lineage."
            }
        val descriptor = node(asset)
        val freezeDocument = retained("anchorFreeze")
        val frozen = node(freezeDocument)
        val anchor = protocol["anchor"]
        fields(anchor, "origin", "bundleSha256", "selector", "qualificationSha256")
        for (name in listOf("anchorFreeze", "parentTerminal", "parentClosure")) {
            fields(protocol[name], "path", "sha256")
        }
        fields(frozen["study91Closure"], "path", "sha256")
        val previousTerminalDocument = retained("previousTerminal")
        val previousClosureDocument = retained("previousClosure")
        val previousTerminal = node(previousTerminalDocument)
        val previousClosure = node(previousClosureDocument)
        val prior91Document = retained("study91Closure")
        val prior91 = node(prior91Document)
        val prior94Document =
            if (refined && !truth(previousClosure, "qualified")) retained("study94Closure")
            else null
        val prior94 = prior94Document?.let(::node)
        val qualificationDocument = retained("anchorQualification")
        require(
            freezeDocument.sha256 == hash(protocol["anchorFreeze"], "sha256") &&
                previousTerminalDocument.sha256 == hash(protocol["parentTerminal"], "sha256") &&
                previousClosureDocument.sha256 == hash(protocol["parentClosure"], "sha256") &&
                prior91Document.sha256 == hash(frozen["study91Closure"], "sha256") &&
                text(frozen, "kind") == "mac-leader-search-anchor-freeze-1" &&
                truth(frozen, "passed") &&
                truth(frozen, "complete") &&
                truth(frozen, "qualified") &&
                hash(frozen, "methodSha256") == hash(protocol, "methodSha256") &&
                listOf("origin", "bundleSha256", "selector", "qualificationSha256").all {
                    frozen[it] == anchor[it]
                } &&
                qualificationDocument.sha256 == hash(anchor, "qualificationSha256") &&
                hash(anchor, "bundleSha256") == hash(descriptor["currentBundle"], "sha256") &&
                tokenSampler(anchor["selector"]) == tokenSampler(descriptor["tokenSelector"]) &&
                expansionDenial(selected["expansionDenial"]) ==
                    expansionDenial(descriptor["expansionDenial"])
        ) {
            "Study93 must bind the full policy to its exact frozen qualified anchor and selected leader bonus."
        }
        require(
            text(previousClosure, "kind") == "learned-bid-search-physical-closure-1" &&
                truth(previousClosure, "passed") &&
                truth(previousClosure, "allOwnedProcessesExited") &&
                hash(previousClosure, "protocolSha256") ==
                    hash(previousTerminal, "protocolSha256") &&
                text(prior91, "kind") == "mac-qualification-physical-closure-1" &&
                truth(prior91, "passed") &&
                truth(prior91, "allOwnedProcessesExited")
        ) {
            "Leader qualification requires both original prior-study physical closure subjects."
        }
        archivedPin(previousClosure, "artifactPins", previousTerminalDocument)
        if (prior94Document != null && prior94 != null) {
            fields(frozen["study94Closure"], "path", "sha256")
            require(
                prior94Document.sha256 == hash(frozen["study94Closure"], "sha256") &&
                    text(prior94, "kind") == "mac-qualification-physical-closure-1" &&
                    truth(prior94, "passed") &&
                    truth(prior94, "allOwnedProcessesExited")
            ) {
                "Refined leader priority requires the exact original94 physical closure subject."
            }
            hash(prior94, "protocolSha256")
        }
        val origin =
            if (truth(previousClosure, "qualified")) "92"
            else if (prior94 != null && truth(prior94, "qualified")) "94"
            else if (truth(prior91, "qualified")) "91" else "87"
        require(text(anchor, "origin") == origin) {
            "Qualified anchor priority must remain92, then91, then87."
        }
        if (origin == "87") {
            val lineage = frozen["old87Lineage"]
            fields(lineage, "qualified", "bundleSha256", "inferenceAssetSha256", "nominationSha256")
            fields(frozen["old87Certificate"], "path", "sha256")
            val base = node(subject(asset, descriptor["baseInferenceAsset"]))
            require(
                tokenSampler(descriptor["tokenSelector"]) == null &&
                    truth(lineage, "qualified") &&
                    hash(lineage, "bundleSha256") == hash(descriptor["currentBundle"], "sha256") &&
                    hash(base["currentBundle"], "sha256") == hash(lineage, "bundleSha256") &&
                    hash(lineage, "inferenceAssetSha256") ==
                        hash(descriptor["baseInferenceAsset"], "sha256") &&
                    hash(lineage, "nominationSha256") == hash(protocol, "nominationSha256") &&
                    hash(frozen["old87Certificate"], "sha256") == qualificationDocument.sha256
            ) {
                "The qualified87 fallback must retain its exact sealed certificate and original asset lineage."
            }
            return
        }
        val ancestor = node(qualificationDocument)
        val ancestorProtocolDocument = retained("anchorSourceProtocol")
        val ancestorProtocol = node(ancestorProtocolDocument)
        val count =
            when (origin) {
                "92" -> if (refined) 2648 else 5208
                "94" -> 2064
                else -> 8208
            }
        require(
            truth(ancestor, "passed") &&
                truth(ancestor, "complete") &&
                truth(ancestor, "qualified") &&
                integer(ancestor, "terminalExecutions") == count &&
                hash(ancestor, "protocolSha256") == ancestorProtocolDocument.sha256 &&
                hash(ancestorProtocol, "candidateBundleSha256") == hash(anchor, "bundleSha256")
        ) {
            "A learned leader anchor requires its complete original qualification and source policy."
        }
        if (origin == "92") {
            fields(previousTerminal["selectedCase"], "id", "selector")
            fields(ancestor["selectedCase"], "id", "selector")
            require(
                text(ancestor, "kind") == "learned-bid-search-original-audit-1" &&
                    text(ancestorProtocol, "kind") == "learned-bid-search-1" &&
                    hash(ancestorProtocol, "methodSha256") ==
                        (if (refined)
                            "193edfd32bad8c1143350ed51831a6b229f731cc2cfeb25ec13c791994d822f9"
                        else "82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36") &&
                    truth(previousClosure, "scienceComplete") &&
                    integer(previousClosure, "actualCompletedLowerBound") == count &&
                    truth(previousTerminal, "complete") &&
                    hash(previousClosure, "protocolSha256") == ancestorProtocolDocument.sha256 &&
                    hash(previousTerminal, "completionAuditSha256") ==
                        qualificationDocument.sha256 &&
                    tokenSampler(previousTerminal["selectedCase"]["selector"]) ==
                        tokenSampler(anchor["selector"]) &&
                    tokenSampler(ancestor["selectedCase"]["selector"]) ==
                        tokenSampler(anchor["selector"])
            ) {
                "The92 anchor must retain all three fresh banks and its exact winning token sampler."
            }
            archivedPin(previousClosure, "artifactPins", qualificationDocument)
        } else if (origin == "94") {
            val closed94 = requireNotNull(prior94) { "A94 anchor requires its original closure." }
            require(
                text(ancestor, "kind") == "fixed-policy-qualification-original-audit-1" &&
                    text(ancestorProtocol, "kind") == "fixed-policy-qualification-campaign-1" &&
                    text(ancestorProtocol, "contract") == "final6-c05-small-512-1" &&
                    hash(ancestorProtocol, "rootProspectiveMethodSha256") ==
                        "22c172eec9af6683cfe8db382154b53440bdc7db89a77d9cf332ac886c4ed377" &&
                    truth(closed94, "scienceComplete") &&
                    integer(closed94, "actualCompletedLowerBound") == 2064 &&
                    hash(closed94, "protocolSha256") == ancestorProtocolDocument.sha256 &&
                    hash(ancestorProtocol, "baseInferenceAssetSha256") ==
                        hash(descriptor["baseInferenceAsset"], "sha256") &&
                    tokenSampler(anchor["selector"]) == NearOptimalTokenSampler(.35, 3.0, 4) &&
                    tokenSampler(searchNode(ancestorProtocol, "selector")) ==
                        tokenSampler(anchor["selector"])
            ) {
                "The94 anchor must retain its complete fresh512-world qualification and nominated sampler."
            }
            for (bank in listOf("mixed", "homogeneous")) {
                protocolSeedCount(ancestorProtocol, bank, 512)
                require(integer(protocolBank(ancestorProtocol, bank), "count") == 512) {
                    "A94 anchor cannot weaken either original complete512-world bank."
                }
            }
            archivedPin(closed94, "artifactPins", qualificationDocument)
        } else {
            require(
                text(ancestor, "kind") == "mac-qualification-original-audit-1" &&
                    text(ancestorProtocol, "kind") == "mac-qualification-campaign-1" &&
                    hash(ancestorProtocol, "rootProspectiveMethodSha256") ==
                        "0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b" &&
                    truth(prior91, "scienceComplete") &&
                    integer(prior91, "actualCompletedLowerBound") == 8208 &&
                    hash(prior91, "protocolSha256") == ancestorProtocolDocument.sha256 &&
                    tokenSampler(searchNode(ancestorProtocol, "selector")) ==
                        tokenSampler(anchor["selector"])
            ) {
                "The91 anchor must retain its complete fresh qualification and nominated sampler."
            }
            archivedPin(prior91, "artifactPins", qualificationDocument)
        }
    }

    private fun protocolSeedCount(protocol: JsonNode, name: String, expected: Int) {
        val seeds = protocolBank(protocol, name)["seeds"]
        require(seeds != null && seeds.isArray && seeds.size() == expected) {
            "Qualification must retain the registered $name world prefix."
        }
    }

    private fun winQualification(document: Document, asset: Document) {
        val value = node(document)
        fields(
            value,
            "kind",
            "schemaVersion",
            "passed",
            "complete",
            "profile",
            "supportProfile",
            "rulesVersion",
            "dataVersion",
            "configuration",
            "inferenceAssetSha256",
            "nominationSha256",
            "banks",
        )
        require(
            text(value, "kind") == "coordinated-win-qualification-1" &&
                integer(value, "schemaVersion") == 1 &&
                truth(value, "passed") &&
                truth(value, "complete") &&
                hash(value, "inferenceAssetSha256") == asset.sha256
        ) {
            "Win qualification must complete successfully for this exact inference asset."
        }
        profile(value, ServerBotProfile.COORDINATED_WIN_SUPPORT_1)
        hash(value, "nominationSha256")
        fields(value["banks"], "mixed", "homogeneous")
        for (name in listOf("mixed", "homogeneous")) {
            val bank = value["banks"][name]
            fields(bank, "complete", "pairs", "win99Passed", "evidence", "report")
            require(
                truth(bank, "complete") &&
                    integer(bank, "pairs") == 4096 &&
                    truth(bank, "win99Passed")
            ) {
                "Both independent complete 4096-pair banks must pass their fixed 99% win gate."
            }
            subject(document, bank["evidence"])
            subject(document, bank["report"])
        }
    }

    private fun qualification(
        document: Document,
        current: Document,
        original: Document,
        calibration: Document,
        references: Map<String, Document>,
    ) {
        val value = node(document)
        fields(
            value,
            "kind",
            "schemaVersion",
            "passed",
            "complete",
            "profile",
            "supportProfile",
            "candidateBundleSha256",
            "originalBundleSha256",
            "calibrationSha256",
            "rulesVersion",
            "dataVersion",
            "configuration",
            "references",
            "banks",
        )
        require(
            text(value, "kind") == "coordinated-support-qualification-1" &&
                integer(value, "schemaVersion") == 1 &&
                truth(value, "passed") &&
                truth(value, "complete")
        ) {
            "Only a completed successful Support1 qualification can admit this preview."
        }
        profile(value)
        require(
            hash(value, "candidateBundleSha256") == current.sha256 &&
                hash(value, "originalBundleSha256") == original.sha256 &&
                hash(value, "calibrationSha256") == calibration.sha256
        ) {
            "Qualification must bind the exact current, original and calibrated subjects."
        }
        val binding = value["references"]
        fields(
            binding,
            "qualifiedOrdinarySha256",
            "cardM8Sha256",
            "purchaseF0Sha256",
            "expansionESha256",
            "tokenControlVersion",
        )
        require(
            references.all { (name, subject) ->
                hash(binding, name + "Sha256") == subject.sha256
            } && text(binding, "tokenControlVersion") == "FIXED_16_TO_15"
        ) {
            "Qualification must bind every reference component and the exact token control."
        }
        fields(value["banks"], "mixed", "homogeneous")
        for (name in listOf("mixed", "homogeneous")) {
            val bank = value["banks"][name]
            fields(bank, "complete", "pairs", "win99Passed", "reward99Passed", "evidence", "report")
            require(
                truth(bank, "complete") &&
                    integer(bank, "pairs") == 4096 &&
                    truth(bank, "win99Passed") &&
                    truth(bank, "reward99Passed")
            ) {
                "Both complete 4096-pair qualification banks must pass their fixed 99% gates."
            }
            subject(document, bank["evidence"])
            subject(document, bank["report"])
        }
    }

    private fun profile(
        value: JsonNode,
        expected: ServerBotProfile = ServerBotProfile.COORDINATED_83_SUPPORT_1,
    ) {
        require(
            text(value, "profile") == expected.version &&
                text(value, "supportProfile") == "known-own-chaos-support-1" &&
                text(value, "rulesVersion") == JointPolicyScope.RULES_VERSION &&
                text(value, "dataVersion") == JointPolicyScope.DATA_VERSION
        ) {
            "The asset requires the exact current-rules coordinated Support1 profile."
        }
        configuration(value["configuration"])
    }

    private fun configuration(value: JsonNode) {
        fields(
            value,
            "playerCount",
            "map",
            "initialCard",
            "deckModification",
            "immediateMasterArtDiscard",
            "drawThreeKeepTwo",
        )
        val expected = JointPolicyScope.configuration
        require(
            integer(value, "playerCount") == expected.playerCount &&
                text(value, "map") == expected.map.name &&
                text(value, "initialCard") == expected.initialCard.name &&
                truth(value, "deckModification") == expected.deckModification &&
                truth(value, "immediateMasterArtDiscard") == expected.immediateMasterArtDiscard &&
                truth(value, "drawThreeKeepTwo") == expected.drawThreeKeepTwo
        ) {
            "The asset configuration must exactly match the four-player qualified setup."
        }
    }

    private fun bundle(document: Document): CoordinatedPolicyModels {
        val value = node(document)
        fields(value, "kind", "heads")
        require(text(value, "kind") == "coordinated-es-bundle-1") {
            "A preview requires an atomic complete four-head bundle."
        }
        fields(value["heads"], *PreviewHead.entries.map { it.name }.toTypedArray())
        val parsed =
            PreviewHead.entries.associateWith { head ->
                val entry = value["heads"][head.name]
                fields(entry, "path", "sha256", "dictionarySha256")
                val model = subject(document, entry, dictionary = true)
                val parameters = parameters(node(model))
                val vocabulary = dictionary(head)
                require(
                    parameters.encoderVersion == vocabulary.first &&
                        parameters.stateFeatureNames == vocabulary.second &&
                        parameters.actionFeatureNames == vocabulary.third
                ) {
                    "Every head must retain its complete authoritative public dictionary."
                }
                val wire =
                    sortedMapOf(
                        "encoderVersion" to vocabulary.first,
                        "stateFeatureNames" to vocabulary.second,
                        "actionFeatureNames" to vocabulary.third,
                    )
                require(hash(entry, "dictionarySha256") == sha256(mapper.writeValueAsBytes(wire))) {
                    "Each component must bind its complete canonical dictionary digest."
                }
                parameters
            }
        fun head(name: PreviewHead): PolicyParameters =
            requireNotNull(parsed[name]) {
                "The complete bundle requires parsed $name parameters."
            }
        return CoordinatedPolicyModels(
            card = head(PreviewHead.CARD).card(),
            purchase = head(PreviewHead.PURCHASE).purchase(),
            expansion = head(PreviewHead.EXPANSION).territory(),
            token = head(PreviewHead.TOKEN).territory(),
        )
    }

    private fun dictionary(head: PreviewHead): Triple<String, List<String>, List<String>> {
        val config = JointPolicyScope.configuration
        val catalog =
            PublicCatalog.forPlayers(
                config.playerCount,
                RuleOptions(
                    deckModification = config.deckModification,
                    initialCard = config.initialCard,
                    immediateMasterArtDiscard = config.immediateMasterArtDiscard,
                    map = config.map,
                    drawThreeKeepTwo = config.drawThreeKeepTwo,
                ),
            )
        return when (head) {
            PreviewHead.CARD ->
                CardObservationEncoder.dictionary(catalog).let {
                    Triple(
                        CardObservationEncoder.ENCODER_VERSION,
                        it.stateFeatureNames,
                        it.actionFeatureNames,
                    )
                }
            PreviewHead.PURCHASE ->
                PurchaseObservationEncoder.dictionary(catalog).let {
                    Triple(
                        PurchaseObservationEncoder.ENCODER_VERSION,
                        it.stateFeatureNames,
                        it.actionFeatureNames,
                    )
                }
            PreviewHead.EXPANSION ->
                ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2).let {
                    Triple(
                        TerritoryEncoder.EXPANSION_2.version,
                        it.stateFeatureNames,
                        it.actionFeatureNames,
                    )
                }
            PreviewHead.TOKEN ->
                TokenObservationEncoder.dictionary(catalog, TerritoryEncoder.TOKEN_2).let {
                    Triple(
                        TerritoryEncoder.TOKEN_2.version,
                        it.stateFeatureNames,
                        it.actionFeatureNames,
                    )
                }
        }
    }

    private fun calibration(
        document: Document,
        original: Document,
        models: CoordinatedPolicyModels,
    ): Pair<Double, Double> {
        val value = node(document)
        fields(
            value,
            "kind",
            "passed",
            "complete",
            "protocolSha256",
            "originalBundleSha256",
            "projectionSha256",
            "panelSha256",
            "probePopulationSha256",
            "directionsSha256",
            "prescribedMeanSha256",
            "scales",
            "heads",
            "semanticFitnessDecoded",
            "calibrationAttempts",
        )
        require(
            text(value, "kind") == "coordinated-es-calibration-2" &&
                truth(value, "passed") &&
                truth(value, "complete") &&
                !truth(value, "semanticFitnessDecoded") &&
                integer(value, "calibrationAttempts") == 1 &&
                hash(value, "originalBundleSha256") == original.sha256
        ) {
            "Only the original one-attempt outcome-free V2 calibration can select preview scales."
        }
        for (key in
            listOf(
                "protocolSha256",
                "projectionSha256",
                "panelSha256",
                "probePopulationSha256",
                "directionsSha256",
                "prescribedMeanSha256",
            )) {
            hash(value, key)
        }
        fields(value["scales"], "EXPANSION", "TOKEN")
        fields(value["heads"], "EXPANSION", "TOKEN")
        val originalHeads = node(original)["heads"]
        val scales =
            listOf("EXPANSION", "TOKEN").map { name ->
                val scale = number(value["scales"][name], "calibrated scale")
                val head = value["heads"][name]
                require(
                    scale in 1.0 / 32.0..32.0 &&
                        text(head, "kind") == "coordinated-es-feasible-gap-calibration-2" &&
                        text(head, "head") == name &&
                        truth(head, "complete") &&
                        number(head["scale"], "head scale") == scale
                ) {
                    "Each E/T scale must match its complete finite V2 head receipt."
                }
                val subjects = head["subjects"]
                require(
                    hash(subjects, "originalModelSha256") == hash(originalHeads[name], "sha256") &&
                        listOf(
                                "protocolSha256",
                                "projectionSha256",
                                "directionsSha256",
                                "prescribedMeanSha256",
                            )
                            .all { hash(subjects, it) == hash(value, it) } &&
                        hash(subjects, "publicPanelSha256") == hash(value, "panelSha256")
                ) {
                    "Calibrated scales must retain their original head, projection and panel identities."
                }
                scale
            }
        require(
            models.expansion.actorWeights.all { it == 0.0 } &&
                models.token.actorWeights.all { it == 0.0 }
        ) {
            "The fixed original E/T parameter containers require their untrained zero readouts."
        }
        return scales[0] to scales[1]
    }

    private fun ordinary(document: Document, scoped: Boolean): BotModel {
        val value = node(document)
        fields(
            value,
            setOf("schemaVersion", "weights"),
            setOf("rulesVersion", "dataVersion", "configuration"),
        )
        if (scoped) {
            require(
                text(value, "rulesVersion") == JointPolicyScope.RULES_VERSION &&
                    text(value, "dataVersion") == JointPolicyScope.DATA_VERSION
            ) {
                "The packed reference must declare its exact current rules and catalog."
            }
            configuration(value["configuration"])
        } else {
            value["rulesVersion"]?.let {
                require(it.isTextual && it.textValue() in PurchasePolicyScope.rulesVersions) {
                    "A raw reference requires an explicitly compatible rules version."
                }
            }
            value["dataVersion"]?.let {
                require(it.isTextual && it.textValue() == JointPolicyScope.DATA_VERSION) {
                    "A raw reference must retain the qualified public catalog."
                }
            }
            value["configuration"]?.let(::configuration)
        }
        require(value["weights"]?.isObject == true) {
            "Ordinary models require named numerical weights."
        }
        return BotModels.fromSerializedWeights(
            schemaVersion = integer(value, "schemaVersion"),
            weights =
                value["weights"].properties().associate {
                    it.key to number(it.value, "ordinary weight")
                },
        )
    }

    private fun referenceEquivalence(packed: BotModel, card: BotModel, expansion: BotModel) {
        val coefficients = BotExpansionModel.Coefficient.entries
        val changed = coefficients.map { it.feature }.toSet()
        require(
            card.weights[BotExpansionModel.GATE] != 1.0 &&
                expansion.weights[BotExpansionModel.GATE] != 1.0 &&
                card.weights[BotTokenOrderControl.FIXED_16_TO_15] != 1.0
        ) {
            "The separate native M8/E references must not already activate packed E/T hooks."
        }
        require(
            (card.weights.keys + expansion.weights.keys).all {
                it in changed || (card.weights[it] ?: 0.0) == (expansion.weights[it] ?: 0.0)
            }
        ) {
            "The separate E reference may change only its eight absolute M8 coefficients."
        }
        val expected =
            card.weights +
                mapOf(
                    BotExpansionModel.GATE to 1.0,
                    BotTokenOrderControl.FIXED_16_TO_15 to 1.0,
                ) +
                coefficients.associate { coefficient ->
                    coefficient.key to
                        requireNotNull(expansion.weights[coefficient.feature]) {
                            "The E reference must explicitly supply all eight absolute coefficients."
                        }
                }
        require(packed.weights == expected) {
            "The packed G3 reference must equal exact M8 plus the declared E and fixed16-to15 hooks."
        }
    }

    private fun subject(owner: Document, value: JsonNode, dictionary: Boolean = false): Document {
        fields(
            value,
            if (dictionary) setOf("path", "sha256", "dictionarySha256")
            else setOf("path", "sha256"),
        )
        val relative = Path.of(text(value, "path"))
        require(
            !relative.isAbsolute &&
                relative.normalize() == relative &&
                relative.none { it.toString() == ".." } &&
                relative.toString().isNotEmpty()
        ) {
            "Asset subjects must have normalized relative paths without parent traversal."
        }
        return document(owner.path.parent.resolve(relative)).also {
            require(it.sha256 == hash(value, "sha256")) {
                "Asset subject bytes do not match their declared SHA-256."
            }
        }
    }

    private fun document(path: Path): Document {
        val absolute = path.toAbsolutePath().normalize()
        require(
            absolute.startsWith(directory) &&
                Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) &&
                absolute.toRealPath().startsWith(directory)
        ) {
            "Every asset subject must be a regular file confined to its immutable directory."
        }
        return documents[absolute]
            ?: run {
                require(Files.size(absolute) in 1..64_000_000) {
                    "An asset subject must fit the 64 MB input bound."
                }
                val bytes = Files.readAllBytes(absolute)
                bytesRead += bytes.size
                require(bytes.size in 1..64_000_000 && bytesRead <= 256_000_000) {
                    "The complete cached asset must fit the 256 MB transport bound."
                }
                Document(absolute, bytes, sha256(bytes)).also { documents[absolute] = it }
            }
    }

    private fun node(document: Document): JsonNode =
        requireNotNull(mapper.readTree(document.bytes)) {
            "An asset subject must contain one JSON document."
        }

    private fun fields(value: JsonNode?, vararg names: String) = fields(value, names.toSet())

    private fun fields(
        value: JsonNode?,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        require(value?.isObject == true) { "An asset record must be a JSON object." }
        val names = value.properties().map { it.key }.toSet()
        require(names.containsAll(required) && names.all { it in required || it in optional }) {
            "An asset record has missing or unexpected fields."
        }
    }

    private fun text(value: JsonNode, name: String): String {
        val item = value[name]
        require(item?.isTextual == true && item.textValue().isNotBlank()) {
            "Asset $name must be nonempty text."
        }
        return item.textValue()
    }

    private fun integer(value: JsonNode, name: String): Int {
        val item = value[name]
        require(item?.isIntegralNumber == true && item.canConvertToInt()) {
            "Asset $name must be a signed JSON integer."
        }
        return item.intValue()
    }

    private fun truth(value: JsonNode, name: String): Boolean {
        val item = value[name]
        require(item?.isBoolean == true) { "Asset $name must be a JSON boolean." }
        return item.booleanValue()
    }

    private fun hash(value: JsonNode, name: String): String =
        text(value, name).also {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Asset $name must be a lowercase SHA-256."
            }
        }

    private fun number(value: JsonNode?, name: String): Double {
        require(value?.isNumber == true && value.doubleValue().isFinite()) {
            "Asset $name must be finite numerical data."
        }
        return value.doubleValue()
    }

    private fun numbers(value: JsonNode, name: String): List<Double> {
        require(value[name]?.isArray == true) { "Asset $name must be an array." }
        return value[name].map { number(it, name) }
    }

    private fun names(value: JsonNode, name: String): List<String> {
        require(value[name]?.isArray == true && value[name].all { it.isTextual }) {
            "Asset $name must be a text array."
        }
        return value[name].map(JsonNode::textValue)
    }

    private fun parameters(value: JsonNode): PolicyParameters {
        fields(
            value,
            "schemaVersion",
            "encoderVersion",
            "hiddenSize",
            "baselineProbability",
            "stateFeatureNames",
            "actionFeatureNames",
            "stateWeights",
            "stateBias",
            "actionWeights",
            "actionBias",
            "actorWeights",
            "valueWeights",
            "valueBias",
        )
        return PolicyParameters(
            integer(value, "schemaVersion"),
            text(value, "encoderVersion"),
            integer(value, "hiddenSize"),
            number(value["baselineProbability"], "baseline probability"),
            names(value, "stateFeatureNames"),
            names(value, "actionFeatureNames"),
            numbers(value, "stateWeights"),
            numbers(value, "stateBias"),
            numbers(value, "actionWeights"),
            numbers(value, "actionBias"),
            numbers(value, "actorWeights"),
            numbers(value, "valueWeights"),
            number(value["valueBias"], "value bias"),
        )
    }
}

private data class PolicyParameters(
    val schemaVersion: Int,
    val encoderVersion: String,
    val hiddenSize: Int,
    val baselineProbability: Double,
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
    val stateWeights: List<Double>,
    val stateBias: List<Double>,
    val actionWeights: List<Double>,
    val actionBias: List<Double>,
    val actorWeights: List<Double>,
    val valueWeights: List<Double>,
    val valueBias: Double,
) {
    fun card() =
        CardPolicyModel(
            schemaVersion,
            encoderVersion,
            hiddenSize,
            baselineProbability,
            stateFeatureNames,
            actionFeatureNames,
            stateWeights,
            stateBias,
            actionWeights,
            actionBias,
            actorWeights,
            valueWeights,
            valueBias,
        )

    fun purchase() =
        PurchasePolicyModel(
            schemaVersion,
            encoderVersion,
            hiddenSize,
            baselineProbability,
            stateFeatureNames,
            actionFeatureNames,
            stateWeights,
            stateBias,
            actionWeights,
            actionBias,
            actorWeights,
            valueWeights,
            valueBias,
        )

    fun territory() =
        TerritoryPolicyModel(
            schemaVersion,
            encoderVersion,
            hiddenSize,
            baselineProbability,
            stateFeatureNames,
            actionFeatureNames,
            stateWeights,
            stateBias,
            actionWeights,
            actionBias,
            actorWeights,
            valueWeights,
            valueBias,
        )
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
