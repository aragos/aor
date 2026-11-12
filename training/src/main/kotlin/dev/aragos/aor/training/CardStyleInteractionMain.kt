package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Installed-classpath data-only entry point. Preparation never invokes a game or policy. */
object CardStyleInteractionMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size % 2 == 0) {
            "Use paired --mode prepare|collect --source FILE --output NEW_DIR options."
        }
        val fields = arguments.toList().chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size) { "Continuation arguments must not repeat." }
        val collect = fields["--mode"] == "collect"
        val expected =
            setOf("--mode", "--source", "--output") +
                if (collect) setOf("--workers", "--seconds") else emptySet()
        require(fields.keys == expected && fields["--mode"] in setOf("prepare", "collect")) {
            "Declare exactly the source/output and, for collection, the original worker/time limits."
        }
        val input =
            CardStyleInteractionInput.read(
                Path.of(requireNotNull(fields["--source"]) { "Source is required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output is required." }).toAbsolutePath()
        val workers =
            if (collect)
                requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
            else 0
        val seconds =
            if (collect)
                requireNotNull(fields["--seconds"]?.toIntOrNull()) { "Seconds must be integral." }
            else 0
        require(!collect || workers in 1..10 && seconds in 1..7200) {
            "Collection permits at most ten workers and one original two-hour native allowance."
        }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val artifacts = TrainingArtifacts(output)
        val store = CardStyleInteractionStore(input, output)
        store.prepare()
        if (!collect) return
        val started = Instant.now()
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
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
            val collector = CardStyleInteractionCollector(input, deadline)
            var completed = 0
            val receipts =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        (0 until 1024).map { index -> { store.save(collector.collect(index)) } }
                    ) {
                        completed++
                        if (completed % 16 == 0) println("Continuation origins $completed/1024")
                    }
                }
            store.finish(receipts)
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
