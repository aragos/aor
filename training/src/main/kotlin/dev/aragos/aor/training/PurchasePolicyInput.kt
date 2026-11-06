package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.PurchasePolicyModel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Strict transport decoding; all numerical inference and model invariants remain in shared AI. */
internal object PurchasePolicyInput {
    fun read(path: Path): PurchasePolicyModel {
        val node = PurchaseJson.read(path)
        PurchaseJson.fields(
            node,
            setOf(
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
            ),
        )
        return PurchasePolicyModel(
            schemaVersion = PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion"),
            encoderVersion = PurchaseJson.text(node.required("encoderVersion"), "encoderVersion"),
            hiddenSize = PurchaseJson.integer(node.required("hiddenSize"), "hiddenSize"),
            baselineProbability =
                PurchaseJson.number(node.required("baselineProbability"), "baselineProbability"),
            stateFeatureNames =
                PurchaseJson.strings(node.required("stateFeatureNames"), "stateFeatureNames"),
            actionFeatureNames =
                PurchaseJson.strings(node.required("actionFeatureNames"), "actionFeatureNames"),
            stateWeights = PurchaseJson.numbers(node.required("stateWeights"), "stateWeights"),
            stateBias = PurchaseJson.numbers(node.required("stateBias"), "stateBias"),
            actionWeights = PurchaseJson.numbers(node.required("actionWeights"), "actionWeights"),
            actionBias = PurchaseJson.numbers(node.required("actionBias"), "actionBias"),
            actorWeights = PurchaseJson.numbers(node.required("actorWeights"), "actorWeights"),
            valueWeights = PurchaseJson.numbers(node.required("valueWeights"), "valueWeights"),
            valueBias = PurchaseJson.number(node.required("valueBias"), "valueBias"),
        )
    }
}

internal object PurchaseJson {
    val mapper: ObjectMapper =
        ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun read(path: Path): JsonNode {
        require(Files.size(path) in 1..64_000_000) {
            "A purchase JSON input must contain between one byte and 64 MB: $path."
        }
        return requireNotNull(mapper.readTree(path.toFile())) {
            "A purchase JSON input must contain a document: $path."
        }
    }

    fun fields(node: JsonNode, required: Set<String>, optional: Set<String> = emptySet()) {
        require(
            node.isObject &&
                node
                    .properties()
                    .map { it.key }
                    .toSet()
                    .let { names ->
                        names.containsAll(required) &&
                            names.all { it in required || it in optional }
                    }
        ) {
            "Purchase JSON fields must contain exactly the declared required and optional fields."
        }
    }

    fun integer(node: JsonNode, name: String): Int {
        require(node.isIntegralNumber && node.canConvertToInt()) {
            "Purchase $name must be a JSON integer in the signed 32-bit range."
        }
        return node.intValue()
    }

    fun text(node: JsonNode, name: String): String {
        require(node.isTextual && node.textValue().isNotBlank()) {
            "Purchase $name must be nonempty text."
        }
        return node.textValue()
    }

    fun number(node: JsonNode, name: String): Double {
        require(node.isNumber && node.doubleValue().isFinite()) {
            "Purchase $name must be a finite JSON number."
        }
        return node.doubleValue()
    }

    fun array(node: JsonNode, name: String): List<JsonNode> {
        require(node.isArray) { "Purchase $name must be an array." }
        return node.toList()
    }

    fun strings(node: JsonNode, name: String): List<String> =
        array(node, name).map { text(it, name) }

    fun numbers(node: JsonNode, name: String): List<Double> =
        array(node, name).map { number(it, name) }

    fun hash(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
            "%02x".format(it)
        }
}
