package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Standalone augmentation CLI; preparation validates metadata without reconstructing any world. */
fun main(arguments: Array<String>) {
    require(arguments.size in 6..7) {
        "Use SOURCE SHA256 OUTPUT MODE WORKERS HOURS [COMPLETED_AUGMENTATION for replay]."
    }
    val source = Path.of(arguments[0]).toRealPath()
    val input = TokenOrderNeighborInput.read(source, arguments[1])
    val output = Path.of(arguments[2]).toAbsolutePath().normalize()
    val mode = arguments[3]
    val workers = arguments[4].toInt()
    val hours = arguments[5].toDouble()
    require(
        mode in setOf("prepare", "augment", "replay") &&
            workers in 1..10 &&
            hours.isFinite() &&
            hours > 0 &&
            hours <= 6 &&
            (arguments.size == 7) == (mode == "replay")
    ) {
        "Augmentation has explicit bounded modes, at most ten workers and six hours."
    }
    require(
        !Files.exists(output) &&
            !output.startsWith(input.parentDirectory) &&
            !input.parentDirectory.startsWith(output)
    ) {
        "Augmentation requires a fresh output directory separate from its immutable parent."
    }
    val started = Instant.now()
    val duration = (hours * 3_600_000_000_000).toLong()
    val deadline = System.nanoTime() + duration
    val artifacts = TrainingArtifacts(output)
    val operation = TokenOrderNeighborAugmentation(input, deadline)
    artifacts.write("source.json", PurchaseJson.read(source))
    artifacts.write(
        "preparation.json",
        operation.metadata() +
            mapOf(
                "mode" to mode,
                "prefixesReconstructed" to 0,
                "tailsGenerated" to 0,
            ),
    )
    if (mode == "prepare") return
    artifacts.write(
        "attempt.json",
        mapOf(
            "attempt" to 1,
            "startedAt" to started.toString(),
            "deadlineAt" to started.plusNanos(duration).toString(),
            "workers" to workers,
            "retries" to 0,
        ),
    )
    val replayDirectory = if (mode == "replay") Path.of(arguments[6]).toRealPath() else null
    val expected = replayDirectory?.let { directory ->
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        require(
            finished.required("sourceSha256").asText() == input.sourceSha256 &&
                finished.required("mode").asText() == "augment"
        ) {
            "Replay requires this exact completed augmentation."
        }
        finished.required("originSha256").map(JsonNode::asText).also { hashes ->
            require(
                hashes.size == 640 &&
                    finished.required("originIndices").map(JsonNode::asInt) ==
                        (0 until 640).toList()
            ) {
                "A complete augmentation binds every historical origin in its original order."
            }
        }
    }
    val indices = if (mode == "replay") input.replayOrigins else (0 until 640).toList()
    var completed = 0
    Tournaments(workers, deadline).use { pool ->
        pool.run(
            indices.map { index ->
                {
                    val receipt = operation.origin(index)
                    if (replayDirectory != null) {
                        val path = replayDirectory.resolve("origin-$index.json")
                        require(PurchaseJson.hash(path) == expected?.get(index)) {
                            "An augmented replay source changed."
                        }
                        val original = PurchaseJson.read(path)
                        val reproduced =
                            PurchaseJson.mapper.readTree(
                                PurchaseJson.mapper.writeValueAsString(receipt)
                            )
                        require(original == reproduced) {
                            "A predeclared new branch did not reproduce its exact metadata and eight outcomes."
                        }
                    }
                    artifacts.write("origin-$index.json", receipt)
                }
            }
        ) {
            completed++
            if (completed % 8 == 0 || completed == indices.size) {
                println("Reference-neighbor $mode receipts $completed/${indices.size}")
            }
        }
    }
    require(
        input.parent.files.all { (name, hash) ->
            PurchaseJson.hash(input.parentDirectory.resolve(name)) == hash
        } && PurchaseJson.hash(source) == input.sourceSha256
    ) {
        "Historical parent or source changed during augmentation."
    }
    checkTokenValueDeadline(deadline)
    artifacts.write(
        "finished.json",
        operation.metadata() +
            mapOf(
                "mode" to mode,
                "completedAt" to Instant.now().toString(),
                "originIndices" to indices,
                "originSha256" to
                    indices.map { PurchaseJson.hash(output.resolve("origin-$it.json")) },
                "prefixesReconstructed" to indices.count { input.missing(it).isNotEmpty() },
                "tailsGenerated" to indices.sumOf { input.missing(it).size * 8 },
                "allParentFilesUnchanged" to true,
                "allReplayedNewBranchesExact" to (mode == "replay"),
            ),
    )
}
