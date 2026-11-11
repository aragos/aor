package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameState
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Narrow compatibility receipt for augmenting completed fixed-T labels. Original runtime bytes
 * recreate the parent signature. Core bytes and every unlisted simulation/policy class remain
 * exact; permitted changes are the reviewed proposal/selector and capture adapters only.
 */
internal class TokenOrderParentRuntime private constructor(private val paths: List<Path>) {
    fun signature(specification: TokenOrderSpecification, sourceHash: String): String =
        trainingSignature(
            "${TokenOrderCollectionMain.KIND}-1:$sourceHash:${specification.identity()}",
            paths,
        )

    companion object {
        private val trainingChanges =
            listOf(
                    "ArenaTokenOrderPolicy",
                    "ArenaTokenRole",
                    "ArenaTokenSelector",
                    "ArenaTokenOpponentSelector",
                    "TokenOrderController",
                    "TokenMinusTwoController",
                    "TokenOrderCollector",
                    // Shared-source debug tables changed; original instructions were independently
                    // compared with javap -c -p before allowing these unchanged owner methods.
                    "TokenOrderSpecification",
                    "TrainingCheckpoint\$Companion",
                    "TokenOrderCapture",
                    "TokenOrderPending",
                    "TokenOrderReproduction",
                    "TokenOrderParentRuntime",
                    "TokenOrderParentSource",
                    "TokenOrderNeighborInput",
                    "TokenOrderNeighborMainKt",
                    "TokenOrderNeighborAugmentation",
                    "TrainingCheckpointKt",
                )
                .map { "dev/aragos/aor/training/$it" }

        fun read(source: Path, node: JsonNode): TokenOrderParentRuntime {
            PurchaseJson.fields(node, setOf("ai", "core", "training"))
            val names = listOf("ai", "core", "training")
            val paths = names.map { name ->
                val entry = node.required(name)
                PurchaseJson.fields(entry, setOf("path", "sha256"))
                val path = source.parent.resolve(entry.required("path").asText()).toRealPath()
                require(
                    Files.isRegularFile(path) &&
                        PurchaseJson.hash(path) == entry.required("sha256").asText()
                ) {
                    "The original token $name runtime artifact changed."
                }
                path
            }
            val current =
                listOf(BotPolicy::class.java, GameState::class.java, TrainingCheckpoint::class.java)
                    .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }
            require(current.all(Files::isRegularFile)) {
                "Historical token augmentation requires the packaged runtime, not mutable class directories."
            }
            require(PurchaseJson.hash(paths[1]) == PurchaseJson.hash(current[1])) {
                "Retained token labels require byte-identical simulation core artifacts."
            }
            verifyClasses(
                classes(paths[0]),
                classes(current[0]),
                listOf("dev/aragos/aor/ai/TokenOrderPolicy"),
            )
            verifyClasses(classes(paths[2]), classes(current[2]), trainingChanges)
            return TokenOrderParentRuntime(paths)
        }

        private fun classes(path: Path): Map<String, ByteArray> =
            ZipFile(path.toFile()).use { zip ->
                zip.entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .associate { entry ->
                        entry.name to zip.getInputStream(entry).use { it.readBytes() }
                    }
            }

        internal fun verifyClasses(
            original: Map<String, ByteArray>,
            current: Map<String, ByteArray>,
            allowedOwners: List<String>,
        ) {
            val changed =
                (original.keys + current.keys).filter { name ->
                    val before = original[name]
                    val after = current[name]
                    before == null || after == null || !before.contentEquals(after)
                }
            require(
                changed.all { name ->
                    allowedOwners.any { name == "$it.class" || name.startsWith("$it$") }
                }
            ) {
                "Unreviewed policy or simulation classes changed: ${changed.filter { name -> allowedOwners.none { name == "$it.class" || name.startsWith("$it$") } }}."
            }
        }
    }
}
