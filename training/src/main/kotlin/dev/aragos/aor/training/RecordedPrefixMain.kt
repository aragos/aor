package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * One local attempt, six workers, ninety minutes; preparation never executes counterfactual tails.
 */
internal object RecordedPrefixMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Recorded prefix options require key/value pairs." }
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
                            "--output",
                            "--prepared",
                            "--prepared-sha256",
                        )
                }
        ) {
            "Recorded prefix options must be unique and recognized."
        }
        fun option(name: String): String =
            requireNotNull(options[name]) { "Recorded prefix requires $name." }
        val mode = option("--mode")
        require(mode in setOf("recorded-prefix-prepare", "recorded-prefix-run")) {
            "Unknown recorded prefix mode."
        }
        val source = Path.of(option("--source")).toAbsolutePath().normalize()
        require(PurchaseJson.hash(source) == option("--source-sha256")) {
            "Recorded source pin changed."
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5400)
        val diagnostic = RecordedPrefixDiagnostic(RecordedPrefixInput.read(source))
        val (roots, receipt) = diagnostic.prepare()
        val output = Path.of(option("--output")).toAbsolutePath().normalize()
        if (mode == "recorded-prefix-prepare") {
            createOutput(output)
            write(output.resolve("prepared.json"), receipt)
            return
        }
        val prepared = Path.of(option("--prepared"))
        require(
            PurchaseJson.hash(prepared) == option("--prepared-sha256") &&
                PurchaseJson.read(prepared) == PurchaseJson.mapper.valueToTree<JsonNode>(receipt)
        ) {
            "Every prefix, observation, action and model must match the approved preparation exactly."
        }
        createOutput(output)
        write(output.resolve("prepared.json"), receipt)
        write(
            output.resolve("run.json"),
            mapOf(
                "startedAt" to started.toString(),
                "deadline" to started.plusSeconds(5400).toString(),
                "workers" to 6,
                "preparedSha256" to PurchaseJson.hash(prepared),
                "sourceSha256" to PurchaseJson.hash(source),
                "attempt" to 1,
                "resumable" to false,
            ),
        )
        execute(diagnostic, roots, output, deadline)
    }

    private fun execute(
        diagnostic: RecordedPrefixDiagnostic,
        roots: List<PreparedRecordedRoot>,
        output: Path,
        deadline: Long,
    ) {
        val pool = Executors.newFixedThreadPool(6)
        val completion = ExecutorCompletionService<String>(pool)
        val requests = (0..31).flatMap { replicate -> roots.map { it to replicate } }
        val futures = requests.map { (root, replicate) ->
            completion.submit(
                Callable {
                    val tails = diagnostic.replicate(root, replicate, deadline)
                    val name = "root-${root.request.entry}-replicate-$replicate.json.gz"
                    val path = output.resolve(name)
                    GZIPOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))
                        .use {
                            PurchaseJson.mapper.writeValue(it, tails)
                        }
                    Files.setPosixFilePermissions(
                        path,
                        PosixFilePermissions.fromString("rw-------"),
                    )
                    name
                }
            )
        }
        try {
            repeat(requests.size) { index ->
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) {
                    "The recorded diagnostic reached its ninety-minute deadline."
                }
                val done =
                    checkNotNull(completion.poll(remaining, TimeUnit.NANOSECONDS)) {
                        "The recorded diagnostic reached its ninety-minute deadline."
                    }
                val name = done.get()
                Files.writeString(
                    output.resolve("completed.jsonl"),
                    PurchaseJson.mapper.writeValueAsString(
                        mapOf(
                            "file" to name,
                            "sha256" to PurchaseJson.hash(output.resolve(name)),
                        )
                    ) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                Files.setPosixFilePermissions(
                    output.resolve("completed.jsonl"),
                    PosixFilePermissions.fromString("rw-------"),
                )
                if ((index + 1) % 19 == 0)
                    println("Recorded prefix paired replicates: ${index + 1}/${requests.size}")
            }
            write(
                output.resolve("complete.json"),
                mapOf(
                    "completedAt" to Instant.now().toString(),
                    "replicates" to requests.size,
                    "tails" to roots.sumOf { it.commands.size * 32 },
                    "allReplaysVerified" to true,
                ),
            )
        } catch (failure: Exception) {
            write(
                output.resolve("failed.json"),
                mapOf(
                    "failedAt" to Instant.now().toString(),
                    "error" to (failure.message ?: failure.javaClass.name),
                    "resumable" to false,
                ),
            )
            throw failure
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun createOutput(path: Path) {
        Files.createDirectory(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    }

    private fun write(path: Path, value: Any) {
        Files.write(
            path,
            PurchaseJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
            StandardOpenOption.CREATE_NEW,
        )
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}
