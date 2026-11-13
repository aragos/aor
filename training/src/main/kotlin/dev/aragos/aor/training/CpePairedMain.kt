package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Finite source preparation, complete collection or one prescribed normal-backbone reproduction.
 */
object CpePairedMain {
    val modes = setOf("cpe-paired-prepare", "cpe-paired-collect", "cpe-paired-reproduce")

    @JvmStatic fun main(arguments: Array<String>) = run(arguments.toList())

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "C/P/E arguments must be option/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size) { "C/P/E options cannot repeat." }
        val mode = fields["--mode"]
        val required =
            setOf("--mode", "--source", "--output") +
                when (mode) {
                    "cpe-paired-prepare" -> emptySet()
                    "cpe-paired-collect" -> setOf("--workers", "--seconds")
                    "cpe-paired-reproduce" -> setOf("--original", "--index", "--seconds")
                    else -> error("Use an explicit C/P/E prepare, collect or reproduce mode.")
                }
        require(fields.keys == required) {
            "Declare exactly the options owned by this C/P/E stage."
        }
        val seconds =
            if (mode == "cpe-paired-prepare") 0
            else
                requireNotNull(fields["--seconds"]?.toIntOrNull()) {
                    "Original remaining seconds must be integral."
                }
        require(mode == "cpe-paired-prepare" || seconds in 1..64800) {
            "A C/P/E stage cannot extend the original eighteen-hour ceiling."
        }
        val input =
            CpePairedInput.read(
                Path.of(requireNotNull(fields["--source"]) { "Source is required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output is required." })
                .toAbsolutePath()
                .normalize()
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        if (mode == "cpe-paired-reproduce") {
            reproduce(input, fields, output, deadline)
            return
        }
        val store = CpePairedStore(input, output)
        store.prepare()
        if (mode == "cpe-paired-prepare") return
        val workers =
            requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
        require(workers in 1..10) { "C/P/E collection permits at most ten workers." }
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
            val collector = CpePairedCollector(input, deadline)
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
        input: CpePairedInput,
        fields: Map<String, String>,
        output: Path,
        deadline: Long,
    ) {
        val index =
            requireNotNull(fields["--index"]?.toIntOrNull()) {
                "Reproduction needs its global index."
            }
        val original =
            Path.of(requireNotNull(fields["--original"]) { "Original evidence is required." })
                .toRealPath()
        val (expected, subject) = CpePairedStore(input, original).readCompleted(index)
        val actual =
            PurchaseJson.mapper.readTree(
                PurchaseJson.mapper.writeValueAsBytes(
                    CpePairedCollector(input, deadline).reproduce(index)
                )
            )
        CpePairedEvidence(input).validate(actual, index, reproduction = true)
        TokenPairedMain.verifyReproduction(expected, actual)
        TrainingArtifacts(output)
            .write(
                "replay-$index.json",
                mapOf(
                    "kind" to "cpe-paired-reproduction-1",
                    "signature" to input.signature,
                    "bundleScopeSha256" to input.bundleScopeSha256,
                    "scopeSha256" to input.scopeSha256,
                    "scienceSha256" to input.scienceSha256,
                    "globalIndex" to index,
                    "request" to input.request(index),
                    "sourceSha256" to input.source.sha256,
                    "original" to
                        mapOf(
                            "path" to original.resolve(subject.file).toString(),
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

    private fun hash(node: JsonNode) =
        trainingSignature(PurchaseJson.mapper.writeValueAsString(node))
}
