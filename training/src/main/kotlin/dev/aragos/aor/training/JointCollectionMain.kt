package dev.aragos.aor.training

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

/** Bounded, resumable experimental collection; this entry point never changes a deployed policy. */
object JointCollectionMain {
    fun run(arguments: List<String>) {
        val options = JointCollectionOptions.parse(arguments)
        val input = JointCollectionInput.read(options.source)
        val inputs = input.files()
        Files.createDirectories(options.output)
        val output = options.output.toRealPath()
        require(inputs.none { it.startsWith(output) }) {
            "Joint output must not contain any of its frozen inputs."
        }
        FileChannel.open(
                output.resolve(".joint.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { channel ->
                val lock =
                    requireNotNull(channel.tryLock()) {
                        "Another joint collector already owns this output directory."
                    }
                lock.use {
                    val signature = input.signature()
                    val store = JointCollectionStore(input, output, signature)
                    val remaining =
                        minOf(
                            Duration.between(
                                    Instant.now(),
                                    store.startedAt.plus(Duration.ofHours(72)),
                                )
                                .toNanos(),
                            (options.hours * 3_600_000_000_000).toLong(),
                        )
                    require(remaining > 0L) {
                        "This joint collection exhausted its three-day allowance."
                    }
                    val deadline = System.nanoTime() + remaining
                    val completed = store.completedIndices()
                    var count = completed.size
                    store.progress("collecting", count)
                    Tournaments(options.workers, deadline).use { workers ->
                        val collector = JointCollector(input, signature, deadline)
                        workers.run(
                            input.seeds.indices
                                .filterNot { it in completed }
                                .map { index ->
                                    {
                                        store.save(collector.collect(index))
                                        index
                                    }
                                }
                        ) { index ->
                            count++
                            store.progress("collecting", count)
                            println(
                                "Joint collection $count/${input.seeds.size}: game=$index seed=${input.seeds[index]}."
                            )
                        }
                    }
                    store.finish()
                    println("Joint collection complete: ${input.seeds.size} games; mode=on-policy.")
                }
            }
    }
}

internal data class JointCollectionOptions(
    val source: Path,
    val output: Path,
    val workers: Int,
    val hours: Double,
) {
    companion object {
        fun parse(arguments: List<String>): JointCollectionOptions {
            require(arguments.size % 2 == 0) { "Use --option value pairs for joint collection." }
            val fields = arguments.chunked(2).associate { it[0] to it[1] }
            require(
                fields.size * 2 == arguments.size &&
                    fields.keys.all {
                        it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                    }
            ) {
                "Joint options may not repeat and must be mode, source, output, workers or hours."
            }
            require(fields["--mode"] == "joint-collect") {
                "Joint collection mode must be explicit."
            }
            val source =
                Path.of(
                        requireNotNull(fields["--source"]) {
                            "Joint collection requires --source PATH."
                        }
                    )
                    .toRealPath()
            val output =
                Path.of(
                        requireNotNull(fields["--output"]) {
                            "Joint collection requires --output PATH."
                        }
                    )
                    .toAbsolutePath()
                    .normalize()
            val cpus =
                System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                    ?: Runtime.getRuntime().availableProcessors()
            val maxWorkers = ((cpus * .8).toInt() - 2).coerceAtLeast(1)
            val workers = fields["--workers"]?.toInt() ?: maxWorkers
            require(workers in 1..maxWorkers) {
                "Use 1–$maxWorkers joint workers to stay within the machine CPU allowance."
            }
            val hours = fields["--hours"]?.toDouble() ?: 3.0
            require(hours.isFinite() && hours > 0 && hours <= 72) {
                "Joint hours must be finite, positive and no greater than 72."
            }
            return JointCollectionOptions(source, output, workers, hours)
        }
    }
}
