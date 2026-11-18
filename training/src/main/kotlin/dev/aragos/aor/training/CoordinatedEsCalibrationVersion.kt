package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Explicit receipt/protocol identity; V2 never reinterprets a V1 sealed scale. */
internal enum class CoordinatedEsCalibrationVersion(
    val receiptKind: String,
    val recipeKind: String,
) {
    V1("coordinated-es-calibration-1", "coordinated-es-signed-gap-calibration-1"),
    V2("coordinated-es-calibration-2", "coordinated-es-feasible-gap-calibration-2");

    fun validateProtocol(protocol: JsonNode) {
        require(protocol["calibration"]?.let { it["kind"] }?.asText() == recipeKind) {
            "The protocol calibration recipe must match the exact sealed receipt version."
        }
    }

    companion object {
        fun read(node: JsonNode): CoordinatedEsCalibrationVersion =
            requireNotNull(entries.singleOrNull { it.receiptKind == node["kind"]?.asText() }) {
                "An explicit supported calibration receipt version is required."
            }
    }
}
