package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Fixed-profile, resumable experiment. It never publishes a model to the application. */
object PilotMain {
    fun run(arguments: List<String>) {
        val options = PilotOptions.parse(arguments)
        val artifacts = TrainingArtifacts(options.output)
        val previous = artifacts.read("state.json")
        require(previous == null || previous["kind"]?.asText() == "automata-pilot") {
            "Output contains another trainer's checkpoint; choose a new pilot directory."
        }
        val baselinePath = options.output.resolve("baseline.json")
        val baseline =
            if (previous != null) {
                TrainingArtifacts.readModel(baselinePath).also { frozen ->
                    if (options.model != null) {
                        require(TrainingArtifacts.readModel(options.model) == frozen) {
                            "Resume must retain the frozen baseline; use a new output for another model."
                        }
                    }
                }
            } else {
                require(options.model != null) {
                    "A new pilot requires --model PATH to a compatible baseline."
                }
                TrainingArtifacts.readModel(options.model)
            }
        val signature =
            trainingSignature("pilot-2:${options.identity()}:${baseline.weights.toSortedMap()}")
        if (previous != null) {
            require(
                previous["kind"]?.asText() == "automata-pilot" &&
                    previous["signature"]?.asText() == signature
            ) {
                "Pilot code, model or experiment settings changed; use a new output directory."
            }
        } else {
            require(artifacts.read("run.json") == null) {
                "Output already contains another run; choose a new directory."
            }
            artifacts.model(baseline, "baseline.json")
            artifacts.write(
                "state.json",
                mapOf(
                    "kind" to "automata-pilot",
                    "signature" to signature,
                    "phase" to "initialized",
                ),
            )
        }
        val startedPath = options.output.resolve("started-at.txt")
        val started =
            if (Files.exists(startedPath)) {
                Instant.parse(Files.readString(startedPath).trim())
            } else {
                Instant.now().also { Files.writeString(startedPath, it.toString()) }
            }
        val remaining =
            minOf(
                Duration.between(Instant.now(), started.plus(Duration.ofHours(72))).toNanos(),
                (options.hours * 3_600_000_000_000).toLong(),
            )
        require(remaining > 0) { "The pilot has exhausted its three-day allowance." }
        val deadline = System.nanoTime() + remaining
        artifacts.write(
            "run.json",
            mapOf(
                "kind" to "automata-pilot",
                "signature" to signature,
                "configuration" to CommodityExperiment.configuration.id,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "curriculumVersion" to CommodityExperiment.VERSION,
                "startedAt" to started.toString(),
                "workers" to options.workers,
                "settings" to options.identity(),
                "javaVersion" to System.getProperty("java.version"),
                "os" to System.getProperty("os.name"),
                "architecture" to System.getProperty("os.arch"),
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
                "baseline" to "baseline.json",
                "baselineWeights" to baseline.weights.size,
                "scope" to
                    "Synthetic commodity curriculum; complete-game evaluation on one rules configuration",
                "acceptanceTarget" to
                    "Student replacement-seat win-share gain >= 0.10 and paired 95% lower bound > 0",
                "validationGate" to
                    "At least 32 unseen scenarios, two fresh paired samples per scenario, positive 95% lower reward bound",
            ),
        )
        fun phase(name: String) {
            artifacts.write(
                "state.json",
                mapOf("kind" to "automata-pilot", "signature" to signature, "phase" to name),
            )
            println("Pilot: $name (${CommodityExperiment.configuration.id})")
        }
        phase("benchmark")
        Tournaments(options.workers, deadline).use { workers ->
            val runner = PilotRunner(options, artifacts, baseline, deadline, workers)
            runner.benchmark()
            phase("teacher")
            val examples = runner.teach()
            phase("fit")
            val student = runner.fit(examples)
            phase("validate")
            val validation = runner.validate(student, examples)
            if (validation.passed) {
                phase("evaluate")
                runner.evaluate(student, validation)
            } else {
                runner.skipEvaluation(validation)
                println("Tournament skipped: ${validation.reason}")
            }
        }
        phase("complete")
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println(
            "Pilot complete. Inspect ${options.output.resolve("evaluation.json")}; no model was published."
        )
    }
}

/** Counts identify the experiment; only worker count and remaining time may change on resume. */
data class PilotOptions(
    val output: Path,
    val model: Path?,
    val workers: Int,
    val hours: Double,
    val seed: Int,
    val benchmarkGames: Int,
    val positions: Int,
    val samples: Int,
    val pairs: Int,
) {
    fun identity(): String = "$seed:$benchmarkGames:$positions:$samples:$pairs"

    companion object {
        fun parse(arguments: List<String>): PilotOptions {
            require(arguments.size % 2 == 0) { "Use --option value pairs for pilot arguments." }
            val fields = arguments.chunked(2).associate { it[0] to it[1] }
            require(fields.size == arguments.size / 2) { "Pilot options may not repeat." }
            val allowed =
                setOf(
                    "--mode",
                    "--output",
                    "--model",
                    "--workers",
                    "--hours",
                    "--seed",
                    "--benchmark-games",
                    "--positions",
                    "--samples",
                    "--pairs",
                )
            require(fields.keys.all { it in allowed }) { "Unknown pilot option." }
            require(fields["--mode"] == "pilot") { "Pilot mode must be explicit." }
            fun count(name: String, default: Int, maximum: Int): Int =
                (fields[name]?.toInt() ?: default).also {
                    require(it in 1..maximum) { "$name must be between 1 and $maximum." }
                }
            val cpus =
                System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                    ?: Runtime.getRuntime().availableProcessors()
            val seed = fields["--seed"]?.toInt() ?: 19790531
            require(seed in 0..100_000_000) { "The initial seed must be 0–100000000." }
            val hours = fields["--hours"]?.toDouble() ?: 12.0
            require(hours.isFinite() && hours > 0 && hours <= 72) {
                "Pilot hours must be positive and at most 72."
            }
            return PilotOptions(
                output = Path.of(fields["--output"] ?: ".aor-training/pilot").toAbsolutePath(),
                model = fields["--model"]?.let(Path::of),
                workers =
                    count(
                        "--workers",
                        ((cpus * 0.8).toInt() - 2).coerceAtLeast(1),
                        ((cpus * 0.8).toInt() - 2).coerceAtLeast(1),
                    ),
                hours = hours,
                seed = seed,
                benchmarkGames = count("--benchmark-games", 8, 10000),
                positions =
                    count("--positions", 256, 10000).also {
                        require(it >= 2) {
                            "--positions requires at least two scenarios for training and validation."
                        }
                    },
                samples = count("--samples", 16, 256),
                pairs = count("--pairs", 128, 100000),
            )
        }
    }
}

internal fun JsonNode.required(name: String): JsonNode =
    requireNotNull(this[name]) { "Pilot artifact requires $name." }
