package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PlannedAction
import dev.aragos.aor.ai.AfterstateContext
import java.nio.file.Path

/** Explicit, immutable two-stage research source; no implicit seed allocation or model defaults. */
internal class PurchasePlanInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val study: PurchasePlanStudy,
    val predictorDescriptor: Path,
) {
    private val descriptor = PurchaseJson.read(predictorDescriptor)
    val files =
        (listOf(source, predictorDescriptor) +
                listOf("checkpointPath", "dictionaryPath", "inferenceContractPath").map {
                    Path.of(PurchaseJson.text(descriptor.required(it), it)).toRealPath()
                } +
                study.collection.files())
            .distinct()
    val pins = files.associateWith(PurchaseJson::hash)
    val signature = trainingSignature("${PurchasePlanProtocol.VERSION}\n$pins")

    init {
        require(descriptor.required("kind").asText() == "purchase-plan-predictor-1") {
            "Plan ranking requires its honest versioned67 predictor descriptor."
        }
        for ((pathKey, hashKey) in
            listOf(
                "checkpointPath" to "checkpointSha256",
                "dictionaryPath" to "dictionarySha256",
                "inferenceContractPath" to "inferenceContractSha256",
            )) {
            require(
                PurchaseJson.hash(Path.of(descriptor.required(pathKey).asText())) ==
                    descriptor.required(hashKey).asText()
            ) {
                "Every frozen predictor subject must match its explicit descriptor."
            }
        }
    }

    fun unchanged() {
        require(pins.all { (path, hash) -> PurchaseJson.hash(path) == hash }) {
            "The frozen plan source, roles or predictor descriptor changed."
        }
    }

    fun metadata(): Map<String, Any> =
        mapOf(
            "kind" to PurchasePlanProtocol.VERSION,
            "source" to source.toString(),
            "sourceSha256" to sourceSha256,
            "signature" to signature,
            "collection" to
                JointCollectionStore.manifest(study.collection, study.collection.signature()),
            "predictorDescriptorSha256" to
                requireNotNull(pins[predictorDescriptor]) { "Predictor descriptor pin required." },
            "origins" to 256,
            "maximumPlans" to 8,
            "exploratoryChoices" to 4,
            "maximumOwnerChoices" to 64,
            "streamsPerPlan" to 4,
            "maximumTerminalGames" to 8448,
            "historicalPolicyReproductions" to 0,
            "canonicalTraversalIsAdditionalGame" to false,
            "seatSchedule" to "index%4",
            "epochSchedule" to "1+(index/4)%3",
            "inputs" to
                pins.entries.mapIndexed { index, (path, hash) ->
                    mapOf(
                        "path" to path.toString(),
                        "sha256" to hash,
                        "snapshot" to "input-$index.json",
                    )
                },
        )

    companion object {
        fun read(path: Path): PurchasePlanInput {
            val source = path.toRealPath()
            val sourceHash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "collection", "predictorDescriptor", "streams"))
            require(node.required("kind").asText() == PurchasePlanProtocol.VERSION) {
                "Unsupported plan-ranking source version."
            }
            fun resolve(name: String): Path {
                val value = node.required(name)
                PurchaseJson.fields(value, setOf("path", "sha256"))
                val file =
                    source.parent
                        .resolve(PurchaseJson.text(value.required("path"), name))
                        .normalize()
                        .toRealPath()
                require(PurchaseJson.hash(file) == value.required("sha256").asText()) {
                    "The exact $name bytes changed."
                }
                return file
            }
            val collection = JointCollectionInput.read(resolve("collection"))
            val predictor = resolve("predictorDescriptor")
            val streams =
                PurchaseJson.array(node.required("streams"), "origin streams").map { item ->
                    PurchaseJson.fields(item, setOf("proposals", "futures"))
                    fun longs(name: String): List<Long> =
                        PurchaseJson.array(item.required(name), name).map { number ->
                            require(number.isIntegralNumber && number.canConvertToLong()) {
                                "Plan streams require signed64-bit integers."
                            }
                            number.longValue()
                        }
                    PurchasePlanStreams(longs("proposals"), longs("futures"))
                }
            return PurchasePlanInput(
                    source,
                    sourceHash,
                    PurchasePlanStudy(collection, streams),
                    predictor,
                )
                .also {
                    require(PurchaseJson.hash(source) == sourceHash) {
                        "The source changed while parsing."
                    }
                    it.unchanged()
                }
        }
    }
}

/** Entity-owned wire boundary used only after completed public evidence is hash-verified. */
internal object PurchasePlanWire {
    fun origin(node: JsonNode): PublicPurchasePlanOrigin {
        PurchaseJson.fields(node, setOf("version", "originIndex", "status", "plans"))
        require(node.required("version").asText() == PurchasePlanProtocol.VERSION) {
            "Unknown public plan version."
        }
        val result =
            PublicPurchasePlanOrigin(
                originIndex = PurchaseJson.integer(node.required("originIndex"), "originIndex"),
                status = PurchasePlanOriginStatus.valueOf(node.required("status").asText()),
                plans = PurchaseJson.array(node.required("plans"), "plans").map(::plan),
            )
        require(wire(result) == node) {
            "Every public plan field must survive exact typed decoding."
        }
        return result
    }

    fun wire(value: Any?): JsonNode =
        PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

    private fun plan(node: JsonNode): PublicPurchasePlan {
        PurchaseJson.fields(
            node,
            setOf("index", "kind", "actions", "status", "exclusion", "leaf", "aliasOf"),
        )
        val leaf =
            node.required("leaf").takeUnless(JsonNode::isNull)?.let { value ->
                PurchaseJson.fields(
                    value,
                    setOf("context", "stateFeatures", "exactValue", "exactReason"),
                )
                require(value.required("context").asText() == AfterstateContext.OWNER_EXIT.name) {
                    "Plan inference supports only the actual complete owner-exit boundary."
                }
                val features = value.required("stateFeatures")
                require(features.isObject) { "Plan features require an explicit sparse map." }
                val state =
                    features.properties().associate { (name, number) ->
                        name to PurchaseJson.number(number, "state feature")
                    }
                require(
                    state["purchase.decision.purchase-owner-exit"] == 1.0 &&
                        state.values.all { it in -16.0..16.0 }
                ) {
                    "Plan leaves must preserve the original state encoder context and units."
                }
                PurchasePlanLeaf(
                    AfterstateContext.OWNER_EXIT,
                    state,
                    value.required("exactValue").takeUnless(JsonNode::isNull)?.let {
                        PurchaseJson.number(it, "exactValue")
                    },
                    value.required("exactReason").takeUnless(JsonNode::isNull)?.asText(),
                )
            }
        return PublicPurchasePlan(
            PurchaseJson.integer(node.required("index"), "plan index"),
            PurchasePlanKind.valueOf(node.required("kind").asText()),
            PurchaseJson.array(node.required("actions"), "actions").map { action ->
                PurchaseJson.fields(action, setOf("decision", "command"))
                PlannedAction(
                    action.required("decision").asText(),
                    action.required("command").asText(),
                )
            },
            PurchasePlanStatus.valueOf(node.required("status").asText()),
            node.required("exclusion").takeUnless(JsonNode::isNull)?.let {
                PurchasePlanExclusion.valueOf(it.asText())
            },
            leaf,
            node.required("aliasOf").takeUnless(JsonNode::isNull)?.let {
                PurchaseJson.integer(it, "aliasOf")
            },
        )
    }
}
