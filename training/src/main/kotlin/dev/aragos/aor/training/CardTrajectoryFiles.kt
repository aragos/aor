package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Card-only compressed snapshots; completion manifests hash the exact compressed bytes. */
internal object CardTrajectoryFiles {
    const val ENCODING = "gzip-json-v1"
    private const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024

    fun readIfPresent(path: Path): JsonNode? {
        if (!Files.exists(path)) return null
        require(Files.size(path) in 1..MAX_DOCUMENT_BYTES.toLong()) {
            "A compressed card trajectory must contain between one byte and 64 MiB: $path."
        }
        val bytes =
            GZIPInputStream(Files.newInputStream(path)).use {
                it.readNBytes(MAX_DOCUMENT_BYTES + 1)
            }
        require(bytes.size in 1..MAX_DOCUMENT_BYTES) {
            "An expanded card trajectory must contain between one byte and 64 MiB: $path."
        }
        return requireNotNull(PurchaseJson.mapper.readTree(bytes)) {
            "An expanded card trajectory requires one complete JSON document: $path."
        }
    }

    fun save(path: Path, document: JsonNode) {
        val existing = readIfPresent(path)
        require(existing == null || existing == document) {
            "Existing card trajectory $path differs and will not be overwritten."
        }
        if (existing != null) return
        val bytes = PurchaseJson.mapper.writeValueAsBytes(document)
        require(bytes.size <= MAX_DOCUMENT_BYTES) {
            "A card trajectory exceeds its 64 MiB expanded-document budget."
        }
        val temporary = Files.createTempFile(path.parent, ".card-game-", ".tmp")
        try {
            GZIPOutputStream(Files.newOutputStream(temporary)).use { it.write(bytes) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
