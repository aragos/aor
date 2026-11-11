package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import java.nio.file.Path

/**
 * Explicit relocation of pinned parent model paths. Original JSON stays unchanged; no global
 * filesystem aliases or permissive metadata comparisons are used. The relocated source may differ
 * only at declared path strings, and its initialization identity retains the original source hash.
 */
internal data class TokenOrderParentSource(
    val originalPath: Path,
    val originalSha256: String,
    val specification: TokenOrderSpecification,
    private val inverseAliases: Map<String, String>,
) {
    fun originalMetadata(node: JsonNode): JsonNode = substitute(node, inverseAliases)

    companion object {
        fun read(protocol: Path, directory: Path, node: JsonNode): TokenOrderParentSource {
            PurchaseJson.fields(node, setOf("relocatedSource", "aliases"))
            val manifest = PurchaseJson.read(directory.resolve("dataset.json"))
            val original = PurchaseJson.read(directory.resolve("source.json"))
            val originalHash = PurchaseJson.hash(directory.resolve("source.json"))
            require(originalHash == manifest.required("sourceSha256").asText()) {
                "Historical token source snapshot changed."
            }
            fun pinned(entry: JsonNode): Path {
                PurchaseJson.fields(entry, setOf("path", "sha256"))
                val path = protocol.parent.resolve(entry.required("path").asText()).toRealPath()
                require(PurchaseJson.hash(path) == entry.required("sha256").asText()) {
                    "A relocated historical input changed: $path."
                }
                return path
            }
            val hashes =
                buildMap<String, String> {
                    fun collect(value: JsonNode) {
                        if (value.isObject && value.has("path") && value.has("sha256")) {
                            val path = value.required("path").asText()
                            val hash = value.required("sha256").asText()
                            require(this[path] == null || this[path] == hash) {
                                "Historical metadata cannot give one input conflicting hashes."
                            }
                            put(path, hash)
                        }
                        for (child in value) {
                            collect(child)
                        }
                    }
                    collect(manifest)
                }
            val aliases =
                node.required("aliases").properties().associate { (old, entry) ->
                    require(hashes[old] == entry.required("sha256").asText()) {
                        "Relocated model aliases must retain their exact original manifest hashes."
                    }
                    old to pinned(entry).toString()
                }
            val sourcePaths =
                buildSet<String> {
                    fun collect(value: JsonNode) {
                        if (value.isTextual && value.asText().startsWith("/")) {
                            add(value.asText())
                        }
                        for (child in value) {
                            collect(child)
                        }
                    }
                    collect(original)
                }
            require(aliases.keys == sourcePaths && aliases.values.distinct().size == aliases.size) {
                "Every original absolute model path needs one unambiguous explicit relocation."
            }
            val relocated = pinned(node.required("relocatedSource"))
            require(PurchaseJson.read(relocated) == substitute(original, aliases)) {
                "Relocated token source changed more than its declared model paths."
            }
            val originalPath = Path.of(manifest.required("source").asText())
            val parsed = TokenOrderSpecification.read(relocated)
            val specification =
                parsed.copy(
                    arena =
                        parsed.arena.copy(
                            initializationSource =
                                ModelInitializationSource(originalPath, originalHash)
                        )
                )
            return TokenOrderParentSource(
                originalPath,
                originalHash,
                specification,
                aliases.entries.associate { it.value to it.key },
            )
        }

        internal fun substitute(node: JsonNode, aliases: Map<String, String>): JsonNode =
            when {
                node.isTextual -> TextNode(aliases[node.asText()] ?: node.asText())
                node.isObject ->
                    PurchaseJson.mapper.createObjectNode().apply {
                        for ((name, value) in node.properties()) {
                            set<JsonNode>(name, substitute(value, aliases))
                        }
                    }
                node.isArray ->
                    PurchaseJson.mapper.createArrayNode().apply {
                        for (value in node) {
                            add(substitute(value, aliases))
                        }
                    }
                else -> node.deepCopy()
            }
    }
}
