package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Small streaming executor for explicit frozen-policy league experiments, independent of a UI. */
object RobustTokenMain {
    val modes = setOf("robust-token-prepare", "robust-token-screen-chunk")

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Robust arguments must be option/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        val mode = requireNotNull(fields["--mode"]) { "An explicit robust mode is required." }
        val required =
            setOf("--mode", "--source", "--output") +
                if (mode == "robust-token-screen-chunk") setOf("--seconds", "--workers")
                else emptySet()
        require(mode in modes && fields.size * 2 == arguments.size && fields.keys == required) {
            "Supply exactly the declared robust mode's nonrepeating options."
        }
        val input =
            RobustTokenInput.read(
                Path.of(requireNotNull(fields["--source"]) { "Source required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output required." })
                .toAbsolutePath()
                .normalize()
        require(!input.source.path.startsWith(output)) {
            "Output cannot contain its immutable source."
        }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val artifacts = TrainingArtifacts(output)
        artifacts.write("prepared.json", input.prepared())
        if (mode == "robust-token-prepare") return
        val seconds =
            requireNotNull(fields["--seconds"]?.toLongOrNull()) {
                "Remaining science seconds must be integral."
            }
        require(seconds in 1..32400) {
            "A robust native stage cannot exceed the original nine-hour allowance."
        }
        val workers =
            requireNotNull(fields["--workers"]?.toIntOrNull()) {
                "An explicit integral worker count is required."
            }
        require(workers in 1..32) { "Robust native workers must be bounded between 1 and 32." }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val attempts = CoordinatedEsAttempts(deadline)
        val executor = Executors.newFixedThreadPool(workers)
        val completion = ExecutorCompletionService<RobustWorld>(executor)
        try {
            input.indices.forEach { index ->
                completion.submit(Callable { world(input, index, attempts, deadline) })
            }
            Files.newBufferedWriter(
                    output.resolve("worlds.jsonl"),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                .use { worlds ->
                    val reproductionPath = output.resolve("reproductions.jsonl")
                    val reproductions =
                        if (input.ownedReplays.isNotEmpty()) {
                            Files.newBufferedWriter(
                                reproductionPath,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                            )
                        } else null
                    reproductions.use { replay ->
                        repeat(input.count) {
                            val remaining = deadline - System.nanoTime()
                            check(remaining > 0) { "The original robust deadline elapsed." }
                            val future =
                                checkNotNull(completion.poll(remaining, TimeUnit.NANOSECONDS)) {
                                    "The original robust deadline elapsed while waiting for a world."
                                }
                            val result = future.get()
                            worlds.write(PurchaseJson.mapper.writeValueAsString(result.record))
                            worlds.newLine()
                            worlds.flush()
                            result.reproductions.forEach { row ->
                                checkNotNull(replay) {
                                        "Prescribed reproduction output is required."
                                    }
                                    .apply {
                                        write(PurchaseJson.mapper.writeValueAsString(row))
                                        newLine()
                                        flush()
                                    }
                            }
                        }
                    }
                }
            input.verify()
            val total = input.count * 2 + input.ownedReplays.size * 2
            check(attempts.attemptedGames == total && attempts.completedGames == total) {
                "Every prescribed original game and reproduction must complete exactly once."
            }
            val names =
                listOf("prepared.json", "worlds.jsonl") +
                    if (input.ownedReplays.isNotEmpty()) listOf("reproductions.jsonl")
                    else emptyList()
            artifacts.write(
                "finished.json",
                mapOf(
                    "kind" to "robust-token-finished-1",
                    "complete" to true,
                    "scienceSha256" to input.scienceSha256,
                    "sourceSha256" to input.source.sha256,
                    "globalStart" to input.start,
                    "count" to input.count,
                    "worlds" to input.count,
                    "games" to input.count * 2,
                    "reproductions" to input.ownedReplays.size * 2,
                    "scientificTerminalExecutions" to total,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                    "newGames" to total,
                    "newFits" to 0,
                    "qualificationClaimed" to false,
                    "files" to names.associateWith { PurchaseJson.hash(output.resolve(it)) },
                ),
            )
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf(
                    "kind" to "robust-token-failed-1",
                    "complete" to false,
                    "scienceSha256" to input.scienceSha256,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                    "message" to (failure.message ?: failure.javaClass.simpleName),
                ),
            )
            throw failure
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "Robust workers did not exit after bounded cancellation."
            }
        }
    }

    private data class RobustWorld(
        val record: Map<String, Any?>,
        val reproductions: List<Map<String, Any?>>,
    )

    private fun world(
        input: RobustTokenInput,
        index: Int,
        attempts: CoordinatedEsAttempts,
        deadline: Long,
    ): RobustWorld {
        val roles = listOf("CANDIDATE", "CONTROL")
        fun play(role: String) =
            attempts.execute(deadline) { gameDeadline ->
                RobustTokenGame.play(input, index, role, gameDeadline)
            }
        val results = roles.associateWith(::play)
        val reproductions =
            if (index in input.ownedReplays) {
                roles.map { role ->
                    val original =
                        requireNotNull(results[role]) { "Original role result required." }
                    val repeated = play(role)
                    check(original == repeated && original.canonicalSha256 != null) {
                        "A prescribed robust reproduction must match every terminal, behavior and canonical byte."
                    }
                    mapOf(
                        "index" to index,
                        "role" to role,
                        "matched" to true,
                        "terminal" to repeated.terminal,
                        "diagnostics" to repeated.diagnostics,
                        "canonicalSha256" to repeated.canonicalSha256,
                    )
                }
            } else emptyList()
        return RobustWorld(
            mapOf(
                "kind" to "robust-token-world-1",
                "scienceSha256" to input.scienceSha256,
                "index" to index,
                "seed" to input.seeds[index],
                "seat" to index % 4,
                "opponentStyle" to input.opponentStyle.at(index, index % 4).name,
                "roles" to results.mapValues { it.value.terminal },
                "diagnostics" to results.mapValues { it.value.diagnostics },
                "canonicalSha256" to results.mapValues { it.value.canonicalSha256 },
            ),
            reproductions,
        )
    }
}
