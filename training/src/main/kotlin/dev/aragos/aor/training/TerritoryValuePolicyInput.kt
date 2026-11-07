package dev.aragos.aor.training

import dev.aragos.aor.ai.TerritoryValueModel
import java.nio.file.Path

/** Strict transport decoding; all numerical inference and model invariants remain in shared AI. */
internal object TerritoryValuePolicyInput {
    fun read(path: Path): TerritoryValueModel {
        val node = PurchaseJson.read(path)
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
