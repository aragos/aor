package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** New-output-only dispatch under the supplied remainder of the original shared24-hour clock. */
object CoordinatedEsMain {
    val modes =
        setOf(
            "coordinated-es-scope",
            "coordinated-es-prepare",
            "coordinated-es-controls",
            "coordinated-es-population",
            "coordinated-es-reproduce",
            "coordinated-es-historical",
        )

    @JvmStatic fun main(arguments: Array<String>) = run(arguments.toList())

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Coordinated arguments must be option/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size) { "Coordinated options cannot repeat." }
        val mode = requireNotNull(fields["--mode"]) { "An explicit mode is required." }
        require(mode in modes) { "Use one of the explicit coordinated stage modes." }
        val required =
            setOf("--mode", "--source", "--output") +
                when (mode) {
                    "coordinated-es-scope",
                    "coordinated-es-prepare" -> emptySet()
                    "coordinated-es-reproduce" ->
                        setOf("--seconds", "--original", "--index", "--role")
                    "coordinated-es-historical" -> setOf("--seconds")
                    else -> setOf("--seconds", "--workers")
                }
        require(fields.keys == required) {
            "Declare exactly the options owned by this coordinated stage."
        }
        val seconds =
            fields["--seconds"]?.let { value ->
                requireNotNull(value.toIntOrNull()) {
                        "Remaining original seconds must be integral."
                    }
                    .also {
                        require(it in 1..86400) {
                            "A stage cannot exceed the original24-hour scientific ceiling."
                        }
                    }
            } ?: 0
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val source =
            CoordinatedEsWire.source(
                Path.of(requireNotNull(fields["--source"]) { "Source required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output required." })
                .toAbsolutePath()
                .normalize()
        require(!source.path.startsWith(output)) { "Output cannot contain its source." }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val artifacts = TrainingArtifacts(output)
        if (mode == "coordinated-es-scope") {
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(node, setOf("kind", "arena", "originalBundle"))
            require(node["kind"]?.asText() == "coordinated-es-scope-input-1") {
                "Explicit zero-work scope source required."
            }
            val scope =
                CoordinatedEsScope.read(
                    TokenPairedSubject.read(node["arena"], source.path.parent),
                    TokenPairedSubject.read(node["originalBundle"], source.path.parent),
                )
            artifacts.write("scope.json", scope.metadata())
            return
        }
        if (mode == "coordinated-es-historical") {
            CoordinatedEsHistorical.run(source, output, deadline)
            return
        }
        val input = CoordinatedEsInput.read(source.path)
        execute(input, mode, fields, output, seconds, deadline)
    }

    internal fun execute(
        input: CoordinatedGenerationInput,
        mode: String,
        fields: Map<String, String>,
        output: Path,
        seconds: Int,
        deadline: Long,
    ) {
        val artifacts = TrainingArtifacts(output)
        val store = CoordinatedEsGames(input, output)
        if (mode == "${input.prefix}-reproduce") {
            reproduce(input, store, fields, output, deadline)
            return
        }
        require(
            mode == "${input.prefix}-prepare" ||
                mode ==
                    if (input.stage == CoordinatedEsStage.CONTROLS) {
                        "${input.prefix}-controls"
                    } else {
                        "${input.prefix}-population"
                    }
        ) {
            "The command mode must match the exact frozen source stage."
        }
        store.prepare()
        if (mode == "${input.prefix}-prepare") return
        val workers =
            requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
        require(workers in 1..10) { "The original host profile permits at most ten workers." }
        val attempts = CoordinatedEsAttempts(deadline)
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
            val receipts =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        input.indices.flatMap { index ->
                            input.roles.map { role ->
                                {
                                    attempts.execute { gameDeadline ->
                                        store.play(index, role, gameDeadline)
                                    }
                                }
                            }
                        }
                    )
                }
            store.finish(receipts, attempts)
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf(
                    "signature" to input.signature,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                    "failure" to failure.toString(),
                ),
            )
            throw failure
        }
    }

    private fun reproduce(
        input: CoordinatedGenerationInput,
        store: CoordinatedEsGames,
        fields: Map<String, String>,
        output: Path,
        deadline: Long,
    ) {
        val index =
            requireNotNull(fields["--index"]?.toIntOrNull()) { "Global replay index required." }
        val role = requireNotNull(fields["--role"]) { "Original replay role required." }
        val original =
            Path.of(requireNotNull(fields["--original"]) { "Original completed shard required." })
                .toRealPath()
        val (expected, subject) = CoordinatedEsGames(input, original).readCompleted(index, role)
        val attempts = CoordinatedEsAttempts(deadline)
        val receipt = attempts.execute { store.play(index, role, it, reproduce = true) }
        val file = CoordinatedEsGames.gameName(role, index)
        val actual = PurchaseJson.read(output.resolve(file))
        CoordinatedEsGames.verifyReproduction(expected, actual)
        TrainingArtifacts(output)
            .write(
                "replay-$role-$index.json",
                mapOf(
                    "kind" to "${input.prefix}-reproduction-1",
                    "signature" to input.signature,
                    "scienceSha256" to input.scienceSha256,
                    "scopeSha256" to input.scopeSha256,
                    "sourceSha256" to input.source.sha256,
                    "generation" to input.generation,
                    "role" to role,
                    "globalIndex" to index,
                    "original" to CoordinatedEsWire.subject(subject),
                    "actual" to mapOf("file" to file, "sha256" to receipt.files[file]),
                    "terminalEqual" to true,
                    "canonicalEqual" to true,
                    "replayVerified" to true,
                    "scientificTerminalExecutions" to 1,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                ) + input.additionalIdentity,
            )
    }
}
