package dev.aragos.aor.training

import dev.aragos.aor.ai.TerritoryPolicyModel
import java.nio.file.Path

/** Strict transport decoding; all numerical inference and model invariants remain in shared AI. */
internal object TerritoryPolicyInput {
    fun read(path: Path): TerritoryPolicyModel {
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
        return TerritoryPolicyModel(
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
