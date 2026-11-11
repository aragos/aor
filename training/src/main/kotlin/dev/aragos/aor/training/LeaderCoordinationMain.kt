package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Separate bounded profile; prepare validates/fixes protocol without generating any worlds. */
internal object LeaderCoordinationMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Coordination options require key/value pairs." }
        val pairs = arguments.chunked(2)
        val options = pairs.associate { it[0] to it[1] }
        require(
            options.size == pairs.size &&
                options.keys.all {
                    it in
                        setOf(
                            "--mode",
                            "--source",
                            "--source-sha256",
                            "--prepared",
                            "--prepared-sha256",
                            "--output",
                        )
                }
        ) {
            "Coordination options must be unique and recognized."
        }
        fun option(name: String): String =
            requireNotNull(options[name]) { "Coordination requires $name." }
        val mode = option("--mode")
        require(mode in setOf("leader-coordination-prepare", "leader-coordination-run")) {
            "Unknown coordination mode."
        }
        val source = Path.of(option("--source")).toAbsolutePath().normalize()
        require(PurchaseJson.hash(source) == option("--source-sha256")) {
            "Coordination source pin changed."
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3600)
        val input = LeaderCoordinationInput.read(source)
        val receipt = input.receipt()
        val output = Path.of(option("--output")).toAbsolutePath().normalize()
        if (mode == "leader-coordination-prepare") {
            RecordedPrefixMain.createOutput(output)
            RecordedPrefixMain.write(output.resolve("prepared.json"), receipt)
            return
        }
        val prepared = Path.of(option("--prepared"))
        require(
            PurchaseJson.hash(prepared) == option("--prepared-sha256") &&
                PurchaseJson.read(prepared) == PurchaseJson.mapper.valueToTree<JsonNode>(receipt)
        ) {
            "Coordination protocol, models and all streams must match frozen preparation."
        }
        RecordedPrefixMain.createOutput(output)
        RecordedPrefixMain.write(output.resolve("prepared.json"), receipt)
        RecordedPrefixMain.write(
            output.resolve("run.json"),
            mapOf(
                "startedAt" to started.toString(),
                "deadline" to started.plusSeconds(3600).toString(),
                "workers" to 3,
                "attempt" to 1,
                "resumable" to false,
                "selectionPerformed" to false,
            ),
        )
        try {
            execute(input, output, deadline)
        } catch (failure: Exception) {
            RecordedPrefixMain.write(
                output.resolve("failed.json"),
                mapOf("error" to (failure.message ?: failure.javaClass.name), "resumable" to false),
            )
            throw failure
        }
    }

    internal fun retained(worlds: List<LeaderCoordinationWorld>): List<LeaderCoordinationRoot> =
        worlds
            .sortedBy(LeaderCoordinationWorld::world)
            .mapNotNull(LeaderCoordinationWorld::root)
            .take(32)

    private fun execute(input: LeaderCoordinationInput, output: Path, deadline: Long) {
        val collection = LeaderCoordinationCollection(input)
        val worlds =
            parallel(
                128,
                deadline,
                { world ->
                    collection.collect(world, deadline).also {
                        RecordedPrefixMain.write(output.resolve("origin-$world.json"), it.receipt)
                    }
                },
            ) { world, _ ->
                completed(output, "origins-completed.jsonl", "origin-$world.json")
                println("Coordination origins: ${world + 1}/128")
            }
        val roots = retained(worlds)
        RecordedPrefixMain.write(
            output.resolve("roots.json"),
            mapOf(
                "roots" to
                    roots.mapIndexed { index, root -> root.receipt + mapOf("rootIndex" to index) },
                "eligibleWorlds" to worlds.filter { it.root != null }.map { it.world },
                "unretainedEligibleWorlds" to
                    worlds.filter { it.root != null && it.root !in roots }.map { it.world },
                "selectionPerformed" to false,
            ),
        )
        if (roots.size < 16) {
            RecordedPrefixMain.write(
                output.resolve("coverage-stop.json"),
                mapOf(
                    "roots" to roots.size,
                    "minimum" to 16,
                    "scannedWorlds" to 128,
                    "tails" to 0,
                    "reason" to "insufficient eligible roots; no extra origins or tails",
                ),
            )
            return
        }
        parallel(
            roots.size * 16,
            deadline,
            { index ->
                val rootIndex = index / 16
                val replicate = index % 16
                val tails = collection.replicate(roots[rootIndex], rootIndex, replicate, deadline)
                val name = "root-$rootIndex-replicate-$replicate.json.gz"
                GZIPOutputStream(
                        Files.newOutputStream(output.resolve(name), StandardOpenOption.CREATE_NEW)
                    )
                    .use {
                        PurchaseJson.mapper.writeValue(it, tails)
                    }
                name
            },
        ) { index, name ->
            completed(output, "completed.jsonl", name)
            if ((index + 1) % 16 == 0) {
                println("Coordination roots evaluated: ${(index + 1) / 16}/${roots.size}")
            }
        }
        RecordedPrefixMain.write(
            output.resolve("complete.json"),
            mapOf(
                "completedAt" to Instant.now().toString(),
                "origins" to 128,
                "roots" to roots.size,
                "replicates" to roots.size * 16,
                "tails" to roots.size * 32,
                "allReplaysVerified" to true,
                "selectionPerformed" to false,
            ),
        )
    }

    private fun completed(output: Path, manifest: String, name: String) {
        Files.writeString(
            output.resolve(manifest),
            PurchaseJson.mapper.writeValueAsString(
                mapOf("file" to name, "sha256" to PurchaseJson.hash(output.resolve(name)))
            ) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * Results are consumed in input order; completion timing cannot change retained root identity.
     */
    private fun <T> parallel(
        count: Int,
        deadline: Long,
        work: (Int) -> T,
        completed: (Int, T) -> Unit,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(3)
        val futures = (0 until count).map { index -> pool.submit(Callable { work(index) }) }
        try {
            return futures.mapIndexed { index, future ->
                checkTokenValueDeadline(deadline)
                future
                    .get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    .also { completed(index, it) }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }
}
