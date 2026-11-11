package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.TerritoryValueModel
import java.nio.file.Path

/** Strict transport decoding; all numerical inference and model invariants remain in shared AI. */
internal object TerritoryValuePolicyInput {
    fun read(path: Path): TerritoryValueModel = read(PurchaseJson.read(path))

    fun read(node: JsonNode): TerritoryValueModel {
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "architecture",
                "encoderVersion",
                "hiddenSize",
                "stateFeatureNames",
                "actionFeatureNames",
                "stateWeights",
                "stateBias",
                "actionWeights",
                "actionBias",
                "outputWeights",
                "outputBias",
            ),
        )
        return TerritoryValueModel(
            schemaVersion = PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion"),
            architecture = PurchaseJson.text(node.required("architecture"), "architecture"),
            encoderVersion = PurchaseJson.text(node.required("encoderVersion"), "encoderVersion"),
            hiddenSize = PurchaseJson.integer(node.required("hiddenSize"), "hiddenSize"),
            stateFeatureNames =
                PurchaseJson.strings(node.required("stateFeatureNames"), "stateFeatureNames"),
            actionFeatureNames =
                PurchaseJson.strings(node.required("actionFeatureNames"), "actionFeatureNames"),
            stateWeights = PurchaseJson.numbers(node.required("stateWeights"), "stateWeights"),
            stateBias = PurchaseJson.numbers(node.required("stateBias"), "stateBias"),
            actionWeights = PurchaseJson.numbers(node.required("actionWeights"), "actionWeights"),
            actionBias = PurchaseJson.numbers(node.required("actionBias"), "actionBias"),
            outputWeights = PurchaseJson.numbers(node.required("outputWeights"), "outputWeights"),
            outputBias = PurchaseJson.number(node.required("outputBias"), "outputBias"),
        )
    }
}
