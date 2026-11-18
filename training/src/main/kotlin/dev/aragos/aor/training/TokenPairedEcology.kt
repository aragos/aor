package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path

/**
 * Cross-host behavior identity. Raw arena/bundle JSON may contain relocated paths, so those wrapper
 * hashes stay in source/snapshot provenance. Every actual model, prior, dictionary, selector, rules
 * version and explicit role remains in this identity.
 */
internal object TokenPairedEcology {
    fun ordinaryHash(arenaSource: Path): String {
        val node = PurchaseJson.read(arenaSource)
        val hashes =
            (listOf(node.required("candidate"), node.required("incumbent")) +
                    PurchaseJson.array(node["opponents"], "opponents"))
                .map { value ->
                    PurchaseJson.hash(
                        arenaSource.parent
                            .resolve(PurchaseJson.text(value, "ordinary model"))
                            .toRealPath()
                    )
                }
        require(hashes.distinct().size == 1) {
            "Every ordinary role requires identical raw M8 bytes."
        }
        return hashes.first()
    }

    fun identity(arena: ArenaSpecification, ordinarySha256: String): Map<String, Any> =
        mapOf(
            "configuration" to arena.configurations.single().id,
            "ordinarySha256" to ordinarySha256,
            "policyBindings" to
                portableBindings(PurchaseJson.mapper.valueToTree(arena.overlayMetadata())),
        )

    fun hash(identity: Map<String, Any>): String =
        trainingSignature(PurchaseJson.mapper.writeValueAsString(identity))

    internal fun portableBindings(metadata: JsonNode): Any {
        val copy = metadata.deepCopy<ObjectNode>()
        val joint = copy.required("jointPolicy") as ObjectNode
        joint.remove("source")
        for (role in listOf("candidateBundle", "incumbentBundle", "sharedBundle")) {
            val bundle = joint.required(role) as ObjectNode
            require(bundle["kind"]?.asText() == JointActorBundle.VERSION) {
                "Portable ecology requires typed joint bundles with exact component identities."
            }
            bundle.remove("sha256")
        }
        return requireNotNull(portable(copy)) { "Portable role identity cannot be null." }
    }

    private fun portable(value: JsonNode): Any? =
        when {
            value.isObject ->
                value
                    .properties()
                    .asSequence()
                    .filter { it.key !in setOf("path", "originalPath", "snapshot") }
                    .associate { it.key to portable(it.value) }
                    .toSortedMap()
            value.isArray -> value.map(::portable)
            value.isNull -> null
            else -> value
        }
}
