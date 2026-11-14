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
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicyModel
import dev.aragos.aor.ai.PurchasePolicyScope
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Immutable local asset. All portable subjects are verified and decoded once at startup. The
 * qualification owner establishes strength; this reader binds that receipt to the exact complete
 * Support1 behavior. It never imports training code or silently falls back from an invalid asset.
 */
private class CachedCoordinatedPreviewPolicy(
    override val assetSha256: String,
    private val reference: BotModel,
    private val selector: CoordinatedPolicySelector,
) : ServerBotPolicy {
    override val profile = ServerBotProfile.COORDINATED_83_SUPPORT_1

    override fun choose(view: GameView, observation: BotObservation, seed: Int): String {
        require(profile.supports(observation, BotDifficulty.HARD)) {
            "The coordinated asset only supports its exact qualified current-rules setup."
        }
        fun referenceCommand(): String =
            BotPolicy.choose(observation, BotDifficulty.HARD, seed, reference)
        return if (selector.controls(observation.decision)) {
            selector.choose(view, observation, seed, ::referenceCommand)
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
        profile(root)
        val current = subject(manifest, root["candidateBundle"])
        val original = subject(manifest, root["originalBundle"])
        val calibrationFile = subject(manifest, root["calibration"])
        val references = root["references"]
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
                subject(manifest, references[it])
            }
        qualification(
            subject(manifest, root["qualification"]),
            current,
            original,
            calibrationFile,
            referenceFiles,
        )
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
        return CachedCoordinatedPreviewPolicy(
            manifest.sha256,
            packed,
            CoordinatedPolicySelector(
                ordinary = card,
                purchaseBaseline = purchase,
                current = currentModels,
                original = originalModels,
                expansionSelector = IncumbentResidualSelector(scales.first),
                tokenSelector = IncumbentResidualSelector(scales.second),
                support = JointSupportProfile.KNOWN_OWN_CHAOS_1,
            ),
        )
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

    private fun profile(value: JsonNode) {
        require(
            text(value, "profile") == ServerBotProfile.COORDINATED_83_SUPPORT_1.version &&
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
