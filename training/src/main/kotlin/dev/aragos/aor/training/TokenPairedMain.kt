package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** New output only; preparation parses and snapshots without choosing a policy action. */
object TokenPairedMain {
    val modes =
        setOf("prepare", "collect", "reproduce")
            .flatMap {
                listOf("token-paired-$it", "token-anchored-paired-$it")
            }
            .toSet()

    @JvmStatic fun main(arguments: Array<String>) = run(arguments.toList())

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Token paired arguments must be option/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size) { "Token paired options cannot repeat." }
        val declaredMode = fields["--mode"]
        val anchoredMode = declaredMode?.startsWith("token-anchored-paired-") == true
        val mode = declaredMode?.replace("token-anchored-paired-", "token-paired-")
        val required =
            setOf("--mode", "--source", "--output") +
                when (mode) {
                    "token-paired-prepare" -> emptySet()
                    "token-paired-collect" -> setOf("--workers", "--seconds")
                    "token-paired-reproduce" -> setOf("--original", "--index", "--seconds")
                    else ->
                        error("Use an explicit token-paired prepare, collect or reproduce mode.")
                }
        require(fields.keys == required) {
            "Declare exactly the options owned by this token stage."
        }
        val input =
            TokenPairedInput.read(
                Path.of(requireNotNull(fields["--source"]) { "Source is required." })
            )
        require(anchoredMode == (input.anchor != null)) {
            "Legacy and anchored sources require their own explicit collection mode."
        }
        val seconds =
            if (mode == "token-paired-prepare") 0
            else
                requireNotNull(fields["--seconds"]?.toIntOrNull()) {
                    "The remaining original seconds must be integral."
                }
        require(mode == "token-paired-prepare" || seconds in 1..21600) {
            "A token stage cannot exceed the original six-hour scientific ceiling."
        }
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output is required." })
                .toAbsolutePath()
                .normalize()
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        if (mode == "token-paired-reproduce") {
            reproduce(input, fields, output, deadline)
            return
        }
        val store = TokenPairedStore(input, output)
        store.prepare()
        if (mode == "token-paired-prepare") return
        val workers =
            requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
        require(workers in 1..(if (anchoredMode) 16 else 10)) {
            "The selected token family must respect its bounded worker limit."
        }
        val artifacts = TrainingArtifacts(output)
        val started = Instant.now()
        artifacts.write(
            "started.json",
            mapOf(
                "signature" to input.signature,
                "workers" to workers,
                "startedAt" to started.toString(),
                "deadlineAt" to started.plusSeconds(seconds.toLong()).toString(),
            ),
        )
        try {
            val collector = TokenPairedCollector(input, deadline)
            val receipts =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        input.range.indices.map { index ->
                            { store.save(collector.collect(index)) }
                        }
                    )
                }
            store.finish(receipts)
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf("signature" to input.signature, "failure" to failure.toString()),
            )
            throw failure
        }
    }

    private fun reproduce(
        input: TokenPairedInput,
        fields: Map<String, String>,
        output: Path,
        deadline: Long,
    ) {
        val index =
            requireNotNull(fields["--index"]?.toIntOrNull()) {
                "Reproduction requires its global index."
            }
        val originalDirectory =
            Path.of(requireNotNull(fields["--original"]) { "Original evidence is required." })
                .toRealPath()
        val (expected, subject) = TokenPairedStore(input, originalDirectory).readCompleted(index)
        val actual = input.originWire(TokenPairedCollector(input, deadline).reproduce(index))
        verifyReproduction(expected, actual)
        TrainingArtifacts(output)
            .write(
                "replay-$index.json",
                mapOf(
                    "kind" to input.kind("reproduction"),
                    "signature" to input.signature,
                    "scopeSha256" to input.scopeSha256,
                    "scienceSha256" to input.scienceSha256,
                    "globalIndex" to index,
                    "request" to input.request(index),
                    "sourceSha256" to input.source.sha256,
                    "original" to
                        mapOf(
                            "path" to originalDirectory.resolve(subject.file).toString(),
                            "sha256" to subject.sha256,
                        ),
                    "backbone" to actual["backbone"],
                    "backboneTerminalSha256" to hash(actual["backboneTerminal"]),
                    "publicRootSha256" to hash(actual["publicRoot"]),
                    "anchorsSha256" to hash(actual["anchors"]),
                    "fullBackboneEqual" to true,
                    "publicRecordsEqual" to true,
                    "terminalEqual" to true,
                    "scientificTerminalGames" to 1,
                    "tailGames" to 0,
                ),
            )
    }

    internal fun verifyReproduction(expected: JsonNode, actual: JsonNode) {
        require(actual["arms"].isEmpty) { "A reproduction cannot execute paired tails." }
        val original = expected.deepCopy<ObjectNode>().also { it.remove("arms") }
        val replayed = actual.deepCopy<ObjectNode>().also { it.remove("arms") }
        require(original == replayed) {
            "The complete normal backbone, public inputs, proposals, anchors and terminal must reproduce exactly."
        }
    }

    private fun hash(node: JsonNode) =
        trainingSignature(PurchaseJson.mapper.writeValueAsString(node))
}
