package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Standalone installed-classpath entry point; preparation never starts a simulation. */
object CancellationMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.contentEquals(arrayOf("--help"))) {
            println(
                "--mode prepare|collect --source FILE --output NEW_DIR [--workers N --seconds N]"
            )
            return
        }
        require(arguments.size % 2 == 0) {
            "Use --mode prepare|collect --source FILE --output NEW_DIR [--workers N --seconds N]."
        }
        val fields = arguments.toList().chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size) { "Cancellation arguments must not repeat." }
        val collect = fields["--mode"] == "collect"
        val keys =
            setOf("--mode", "--source", "--output") +
                if (collect) setOf("--workers", "--seconds") else emptySet()
        require(fields.keys == keys && fields["--mode"] in setOf("prepare", "collect")) {
            "Cancellation mode requires exactly its declared source/output and collection limits."
        }
        val input =
            CancellationInput.read(
                Path.of(requireNotNull(fields["--source"]) { "Source is required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output is required." }).toAbsolutePath()
        val workers =
            if (collect) {
                requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be an integer." }
            } else 0
        val seconds =
            if (collect) {
                requireNotNull(fields["--seconds"]?.toIntOrNull()) { "Seconds must be an integer." }
            } else 0
        require(!collect || workers in 1..6 && seconds in 1..7200) {
            "Collection permits at most six workers and one original two-hour native allowance."
        }
        Files.createDirectories(output.parent)
        // One attempt; no resume, overwrite, refill or automatic retry.
        Files.createDirectory(output)
        val started = Instant.now()
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val artifacts = TrainingArtifacts(output)
        input.snapshot(artifacts)
        val metadata =
            mapOf(
                "kind" to "cancellation-data-1",
                "signature" to input.signature,
                "sourceSha256" to input.sourceSha256,
                "arenaSha256" to input.arenaSha256,
                "configuration" to input.configuration.id,
                "policyBindings" to input.arena.overlayMetadata(),
                "requests" to (0 until 256).map(::CancellationRequest),
                "origins" to input.origins,
                "maximumOriginPrefixes" to 256,
                "maximumTailGames" to 1536,
                "maximumTerminalGames" to 1792,
                "statisticalUnit" to "origin world; all action tails are dependent",
                "actorInputContract" to "existing owner GameView and BotObservation only",
                "authorityContract" to
                    "canonical evidence stays outside actor inputs; fork retains hands and deck",
                "prefixIntervention" to
                    "none; exact normal homogeneous controllers before and after root",
                "maximumMissingTerminalGames" to 256,
                "targetContract" to
                    "all paired first-action returns under frozen continuation; no winner labels",
            )
        artifacts.write("prepared.json", metadata)
        if (!collect) return
        artifacts.write(
            "started.json",
            mapOf(
                "signature" to input.signature,
                "startedAt" to started.toString(),
                "deadlineAt" to started.plusSeconds(seconds.toLong()).toString(),
                "workers" to workers,
            ),
        )
        try {
            val collector = CancellationCollector(input, deadline)
            var completed = 0
            val receipts =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        (0 until 256).map { index ->
                            {
                                val name = "origin-$index.json"
                                val result = collector.collect(index)
                                artifacts.write(name, result)
                                val origin = PurchaseJson.read(output.resolve(name))
                                mapOf(
                                    "index" to index,
                                    "file" to name,
                                    "sha256" to PurchaseJson.hash(output.resolve(name)),
                                    "captured" to (origin["status"]?.asText() == "COLLECTED"),
                                    "prefixTerminal" to origin["prefix"]["terminal"].booleanValue(),
                                    "tailGames" to origin["arms"].sumOf { it["outcomes"].size() },
                                    "prefixPlayerDecisions" to
                                        origin["prefix"]["playerDecisions"].intValue(),
                                )
                            }
                        }
                    ) {
                        completed++
                        if (completed % 8 == 0) println("Cancellation origins $completed/256")
                    }
                }
            input.verifySources()
            input.snapshot(artifacts)
            artifacts.write(
                "finished.json",
                mapOf(
                    "signature" to input.signature,
                    "complete" to true,
                    "origins" to receipts.sortedBy { it["index"] as Int },
                    "originPrefixes" to receipts.size,
                    "capturedPrefixes" to receipts.count { it["captured"] == true },
                    "missingTerminalGames" to receipts.count { it["prefixTerminal"] == true },
                    "prefixPlayerDecisions" to
                        receipts.sumOf { it["prefixPlayerDecisions"] as Int },
                    "tailGames" to receipts.sumOf { it["tailGames"] as Int },
                    "scientificTerminalGames" to
                        receipts.sumOf {
                            (if (it["prefixTerminal"] == true) 1 else 0) + (it["tailGames"] as Int)
                        },
                    "at" to Instant.now().toString(),
                ),
            )
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf(
                    "signature" to input.signature,
                    "failure" to failure.toString(),
                    "at" to Instant.now().toString(),
                ),
            )
            throw failure
        }
    }
}
