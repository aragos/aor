package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest

/** Plain cross-language SHA-256, distinct from runtime-bound [trainingSignature]. */
internal object CpePairedDigest {
    fun bytes(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    /** Dictionaries and request tables contain only strings, booleans and exact integer streams. */
    fun json(value: Any): String =
        bytes(
            PurchaseJson.mapper.writeValueAsString(sorted(PurchaseJson.mapper.valueToTree(value)))
        )

    private fun sorted(value: JsonNode): JsonNode =
        when {
            value.isObject ->
                PurchaseJson.mapper.createObjectNode().also { output ->
                    value
                        .properties()
                        .sortedBy { it.key }
                        .forEach { output.set<JsonNode>(it.key, sorted(it.value)) }
                }
            value.isArray ->
                PurchaseJson.mapper.createArrayNode().also { output ->
                    value.forEach { output.add(sorted(it)) }
                }
            else -> value
        }
}
