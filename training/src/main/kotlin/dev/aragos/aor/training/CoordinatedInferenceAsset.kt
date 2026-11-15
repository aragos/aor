package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Path

/**
 * Portable inference subjects; nomination establishes provenance, never training-runtime decoding.
 */
internal class CoordinatedInferenceAsset
private constructor(
    val source: TokenPairedSubject,
    val record: JsonNode,
    val current: CoordinatedEsBundle,
    val original: CoordinatedEsBundle,
    val calibration: CoordinatedEsCalibration,
    val baseline: PurchaseBaselineInput,
    val references: Map<String, TokenPairedSubject>,
    val pins: Map<String, String>,
) {
    fun verify() {
        pins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "Frozen inference subject changed: $path."
            }
        }
    }

    companion object {
        const val VERSION = "coordinated-inference-asset-1"
        const val PROFILE = "aor-coordinated-win-support-1"
        const val SUPPORT = "known-own-chaos-support-1"
        val CONFIGURATION: Map<String, Any> =
            mapOf(
                "playerCount" to 4,
                "map" to "STANDARD",
                "initialCard" to "NO_CRUSADES",
                "deckModification" to true,
                "immediateMasterArtDiscard" to true,
                "drawThreeKeepTwo" to false,
            )
        val REFERENCE_NAMES = setOf("qualifiedOrdinary", "cardM8", "purchaseF0", "expansionE")

        /** Confined portable references are resolved before opening any parameter arrays. */
        fun subject(owner: TokenPairedSubject, node: JsonNode): TokenPairedSubject {
            PurchaseJson.fields(node, setOf("path", "sha256"))
            val path = Path.of(PurchaseJson.text(node["path"], "portable subject path"))
            require(!path.isAbsolute && path.normalize() == path && !path.startsWith("..")) {
                "Inference evidence paths must be normalized, relative and confined to their owner."
            }
            val result = TokenPairedSubject.read(node, owner.path.parent)
            require(result.path.startsWith(owner.path.parent.toRealPath())) {
                "Inference evidence cannot escape its owner through a symbolic link."
            }
            return result
        }

        fun read(
            source: TokenPairedSubject,
            configuration: TrainingConfiguration,
        ): CoordinatedInferenceAsset {
            source.verify()
            val record = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                record,
                setOf(
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
                    "calibration",
                    "references",
                ),
            )
            require(
                record["kind"]?.asText() == VERSION &&
                    PurchaseJson.integer(record["schemaVersion"], "asset schema") == 1 &&
                    record["profile"]?.asText() == PROFILE &&
                    record["supportProfile"]?.asText() == SUPPORT &&
                    record["selectorVersion"]?.asText() == "incumbent-residual-margin-1" &&
                    record["rulesVersion"]?.asText() == RULES_VERSION &&
                    record["dataVersion"]?.asText() == DATA_VERSION &&
                    record["configuration"] == ExpansionOnPolicyStore.wire(CONFIGURATION) &&
                    configuration.id == CoordinatedQualificationProtocol.CONFIGURATION
            ) {
                "Inference requires the exact supported four-player configuration and existing selector."
            }
            PurchaseJson.fields(
                record.required("references"),
                REFERENCE_NAMES + "tokenControlVersion",
            )
            require(record["references"]["tokenControlVersion"]?.asText() == "FIXED_16_TO_15") {
                "The inference reference retains the exact fixed16-to15 token rule."
            }
            val references = REFERENCE_NAMES.associateWith {
                subject(source, record["references"].required(it))
            }
            val currentSubject = subject(source, record.required("currentBundle"))
            val originalSubject = subject(source, record.required("originalBundle"))
            val calibrationSubject = subject(source, record.required("calibration"))
            val componentSubjects =
                listOf(originalSubject, currentSubject).flatMap { bundle ->
                    val node = PurchaseJson.read(bundle.path)
                    PurchaseJson.fields(node, setOf("kind", "heads"))
                    PurchaseJson.fields(
                        node.required("heads"),
                        CoordinatedEsHead.entries.map { it.name }.toSet(),
                    )
                    CoordinatedEsHead.entries.map { head ->
                        val value = node["heads"].required(head.name)
                        PurchaseJson.fields(value, setOf("path", "sha256", "dictionarySha256"))
                        val raw = PurchaseJson.mapper.createObjectNode()
                        raw.set<JsonNode>("path", value.required("path"))
                        raw.set<JsonNode>("sha256", value.required("sha256"))
                        subject(bundle, raw)
                    }
                }
            val pins =
                (listOf(source, currentSubject, originalSubject, calibrationSubject) +
                        references.values +
                        componentSubjects)
                    .associate { it.path.toString() to it.sha256 }
            val dictionaries = CoordinatedEsBundle.dictionaries(configuration)
            val original = CoordinatedEsBundle.read(originalSubject, dictionaries)
            require(
                original.models.expansion.actorWeights.all { it == 0.0 } &&
                    original.models.token.actorWeights.all { it == 0.0 }
            ) {
                "Original E/T subtraction anchors require their zero actor readouts."
            }
            val current = CoordinatedEsBundle.read(currentSubject, dictionaries)
            current.models.validateFrozenValues(original.models)
            val calibration = CoordinatedEsCalibration.read(calibrationSubject, original)
            require(calibration.version == CoordinatedEsCalibrationVersion.V2) {
                "Inference asset version one requires the exact V2 seal supported by the cached preview."
            }
            val baselineSubject =
                requireNotNull(references["purchaseF0"]) { "The F0 reference is required." }
            val baseline =
                PurchaseBaselineInput(
                    baselineSubject.path,
                    baselineSubject.sha256,
                    TrainingArtifacts.readModel(baselineSubject.path),
                )
            references(references)
            return CoordinatedInferenceAsset(
                    source,
                    record,
                    current,
                    original,
                    calibration,
                    baseline,
                    references,
                    pins,
                )
                .also { it.verify() }
        }

        /** Transport coefficients describe the existing preview reference, not a second chooser. */
        private fun references(subjects: Map<String, TokenPairedSubject>) {
            fun model(name: String) =
                TrainingArtifacts.readModel(
                    requireNotNull(subjects[name]) { "Missing $name reference." }.path
                )
            val card = model("cardM8")
            val expansion = model("expansionE")
            val packed = model("qualifiedOrdinary")
            val gate = "planning.expansionModel.enabled"
            val token = "planning.tokenOrderControl.fixed16To15"
            require(
                card.weights[gate] != 1.0 &&
                    expansion.weights[gate] != 1.0 &&
                    card.weights[token] != 1.0 &&
                    ExpansionImitationProtocol.preservesOtherWeights(card, expansion)
            ) {
                "Native references must preserve M8 outside the eight absolute E coefficients and omit packed hooks."
            }
            val expected =
                card.weights +
                    mapOf(gate to 1.0, token to 1.0) +
                    ExpansionImitationProtocol.features.associate { name ->
                        "planning.expansionModel.$name" to
                            requireNotNull(expansion.weights[name]) {
                                "The E reference requires its absolute $name coefficient."
                            }
                    }
            require(packed.weights == expected) {
                "Packed preview reference must equal the exact separate native M8/E/token composition."
            }
        }
    }
}

/**
 * A root-pinned eligibility certificate; evidence bodies remain opaque immutable audit subjects.
 */
internal object CoordinatedPolicyNomination {
    fun read(source: TokenPairedSubject, asset: TokenPairedSubject): Map<String, String> {
        source.verify()
        val node = PurchaseJson.read(source.path)
        PurchaseJson.fields(
            node,
            setOf(
                "kind",
                "schemaVersion",
                "passed",
                "complete",
                "assetSha256",
                "profile",
                "completionAudit",
                "developmentReport",
                "learningWin95Passed",
                "controlWin95Passed",
                "nomination",
                "candidateAlternatives",
            ),
        )
        require(
            node["kind"]?.asText() == "coordinated-policy-nomination-1" &&
                PurchaseJson.integer(node["schemaVersion"], "nomination schema") == 1 &&
                listOf("passed", "complete", "learningWin95Passed", "controlWin95Passed").all {
                    node[it]?.booleanValue() == true
                } &&
                node["assetSha256"]?.asText() == asset.sha256 &&
                node["profile"]?.asText() == CoordinatedInferenceAsset.PROFILE &&
                node["nomination"]?.asText() == "SOLE_FINAL_MEAN" &&
                PurchaseJson.integer(node["candidateAlternatives"], "candidate alternatives") == 0
        ) {
            "Only the root-pinned sole final-mean eligibility certificate may admit this exact asset."
        }
        return (listOf(source, asset) +
                listOf("completionAudit", "developmentReport").map {
                    CoordinatedInferenceAsset.subject(source, node.required(it))
                })
            .associate { it.path.toString() to it.sha256 }
    }
}
