package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Path

/**
 * Explicit experimental initialization; source qualification never transfers to the target rules.
 */
data class ExperimentalRulesTransfer(
    val sourceRulesVersion: String,
    val targetRulesVersion: String,
) {
    init {
        require(
            sourceRulesVersion == "ah1996-three-epoch-4" &&
                targetRulesVersion == "ah1996-three-epoch-5" &&
                targetRulesVersion == RULES_VERSION
        ) {
            "Experimental model initialization supports only rules4 to the current rules5."
        }
    }

    internal fun metadata(): Map<String, Any> =
        mapOf(
            "experimentalRulesTransfer" to
                mapOf(
                    "sourceRulesVersion" to sourceRulesVersion,
                    "targetRulesVersion" to targetRulesVersion,
                    "initializationOnly" to true,
                    "qualificationTransferred" to false,
                )
        )

    companion object {
        internal fun read(node: JsonNode?): ExperimentalRulesTransfer? {
            if (node == null) return null
            PurchaseJson.fields(node, setOf("sourceRulesVersion", "targetRulesVersion"))
            return ExperimentalRulesTransfer(
                PurchaseJson.text(node.required("sourceRulesVersion"), "sourceRulesVersion"),
                PurchaseJson.text(node.required("targetRulesVersion"), "targetRulesVersion"),
            )
        }
    }
}

/** Original file bytes, kept separate from a model's decoded weights and target runtime version. */
data class ModelInitializationSource(val path: Path, val sha256: String) {
    init {
        require(path.isAbsolute && sha256.matches(Regex("[0-9a-f]{64}"))) {
            "A model initialization source requires an absolute path and exact SHA-256 hash."
        }
    }

    internal fun metadata(snapshot: String): Map<String, String> =
        mapOf("path" to path.toString(), "sha256" to sha256, "snapshot" to snapshot)
}
