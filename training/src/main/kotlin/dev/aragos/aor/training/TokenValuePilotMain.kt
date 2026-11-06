package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Frozen local collector/fitter; candidate output never changes the production asset. */
object TokenValuePilotMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for the token-value pilot." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                }
        ) {
            "Token-value options may not repeat and must be mode, source, output, workers or hours."
        }
        require(fields["--mode"] == "token-value-pilot") { "Token-value mode must be explicit." }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) {
                        "The token-value pilot requires --source PATH."
                    }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) {
                        "The token-value pilot requires --output PATH."
                    }
                )
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) { "Token-value output must not contain its source." }
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val workerCount =
            fields["--workers"]?.toInt() ?: ((cpus * 0.8).toInt() - 2).coerceAtLeast(1)
        val hours = fields["--hours"]?.toDouble() ?: 3.0
        require(hours.isFinite() && hours > 0.0 && hours <= 72.0) {
            "Token-value hours must be positive and no greater than 72."
        }
        val specification = TokenValueSpecification.read(source)
        val signature =
            trainingSignature(
                "token-value-pilot-2:$source\n${Files.readString(source)}\n${specification.identity()}"
            )
        val artifacts = TrainingArtifacts(output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == "token-value-pilot" &&
                    previous["signature"]?.asText() == signature
        ) {
            "Token-value source, code, feature schema, models or settings changed; use a new output directory."
        }
        if (previous == null) {
            Files.list(output).use { paths ->
                require(
                    paths.allMatch {
                        it.fileName.toString() in
                            setOf("supervisor.json", ".supervisor.lock", "trainer.log")
                    }
                ) {
                    "Token-value output already contains artifacts from another run."
                }
            }
        }
        val identity =
            mapOf(
                "kind" to "token-value-pilot",
                "signature" to signature,
                "originSchemaVersion" to TOKEN_VALUE_ORIGIN_SCHEMA_VERSION,
                "selectionProtocol" to TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
                "meanDeltaZeroTolerance" to TOKEN_VALUE_MEAN_ZERO_TOLERANCE,
            )
        artifacts.write("state.json", identity + mapOf("phase" to "initialized"))
        artifacts.model(specification.arena.candidate, "initial.json")
        artifacts.model(specification.arena.incumbent, "incumbent.json")
        specification.arena.opponents.forEachIndexed { index, model ->
            artifacts.model(model, "opponent-$index.json")
        }
        val startPath = output.resolve("started-at.txt")
        val started =
            if (Files.exists(startPath)) Instant.parse(Files.readString(startPath).trim())
            else Instant.now().also { Files.writeString(startPath, it.toString()) }
        val remaining =
            minOf(
                Duration.between(Instant.now(), started.plus(Duration.ofHours(72))).toNanos(),
                (hours * 3_600_000_000_000).toLong(),
            )
        require(remaining > 0L) { "The token-value pilot exhausted its three-day allowance." }
        val deadline = System.nanoTime() + remaining
        val metadata =
            identity +
                mapOf(
                    "source" to source.toString(),
                    "configuration" to specification.arena.configurations.single().id,
                    "workers" to workerCount,
                    "opponentModels" to specification.arena.opponents.size,
                    "difficulty" to "HARD",
                    "startedAt" to started.toString(),
                )
        artifacts.write("run.json", metadata)
        Tournaments(workerCount, deadline).use { workers ->
            TokenValuePilotRunner(specification, artifacts, workers, deadline, metadata).run()
        }
        artifacts.write("state.json", metadata + mapOf("phase" to "complete"))
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println("Token-value pilot complete; candidate.json needs independent arena confirmation.")
    }
}
