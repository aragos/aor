package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Finite preparation, historical controls, two fixed chunks, then one completed-bank report. */
internal object ExpansionBaselineMain {
    val modes =
        setOf(
            "expansion-baseline-screen-prepare",
            "expansion-baseline-screen-historical",
            "expansion-baseline-screen-chunk",
            "expansion-baseline-screen-report",
        )

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) {
            "Baseline comparison screen options must be key/value pairs."
        }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        val mode = fields["--mode"]
        val extra =
            when (mode) {
                "expansion-baseline-screen-prepare" -> emptySet()
                "expansion-baseline-screen-historical" -> setOf("--seconds")
                "expansion-baseline-screen-chunk" ->
                    setOf("--seconds", "--workers", "--chunk", "--historical")
                "expansion-baseline-screen-report" -> setOf("--first", "--second", "--historical")
                else -> error("Declare one explicit baseline comparison screen mode.")
            }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys == setOf("--mode", "--source", "--output") + extra
        ) {
            "Only the mode's exact source, output, stage and limit arguments are permitted."
        }
        fun path(name: String) =
            Path.of(requireNotNull(fields[name]) { "$name is required." })
                .toAbsolutePath()
                .normalize()
        val input = ExpansionBaselineInput.read(path("--source"))
        val output = path("--output")
        require(!Files.exists(output)) {
            "Use a fresh output directory; no retries, overwrites or resumes."
        }
        val seconds =
            if ("--seconds" in fields)
                PurchaseJson.integer(PurchaseJson.mapper.readTree(fields["--seconds"]), "seconds")
            else 0
        require(seconds == 0 || seconds in 1..7200) {
            "The native allowance must fit the original two-hour campaign ceiling."
        }
        require("--seconds" !in fields || seconds > 0) {
            "A scientific stage requires positive remaining seconds."
        }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        if ("--historical" in fields) verifyFinished(input, path("--historical"), "historical")
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val artifacts = TrainingArtifacts(output)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        artifacts.write(
            "started.json",
            mapOf(
                "signature" to input.signature,
                "mode" to mode,
                "startedAt" to Instant.now().toString(),
                "maximumSeconds" to seconds,
            ),
        )
        when (mode) {
            "expansion-baseline-screen-prepare" -> finish(input, output, "prepare", 0, 0)
            "expansion-baseline-screen-historical" -> {
                input.historical.indices.forEach { ordinal ->
                    artifacts.write(
                        "historical-$ordinal.json",
                        ExpansionBaselineScreen.historical(input, ordinal, deadline),
                    )
                }
                finish(input, output, "historical", 0, 2)
            }
            "expansion-baseline-screen-chunk" -> {
                val chunk =
                    requireNotNull(fields["--chunk"]?.toIntOrNull()) { "Chunk must be integral." }
                require(chunk in 0..1) { "Only the two prospective chunks are allowed." }
                val workers =
                    requireNotNull(fields["--workers"]?.toIntOrNull()) {
                        "Workers must be integral."
                    }
                require(workers in 1..10) {
                    "Declare at most ten workers within the unchanged host allowance."
                }
                artifacts.write(
                    "historical-admission.json",
                    mapOf(
                        "path" to path("--historical").toString(),
                        "sha256" to
                            PurchaseJson.hash(path("--historical").resolve("finished.json")),
                    ),
                )
                val indices = chunk * 512 until (chunk + 1) * 512
                val completed =
                    Tournaments(workers, deadline).use { pool ->
                        pool.run(
                            indices.map { index ->
                                {
                                    val world =
                                        ExpansionBaselineScreen.play(input, index, deadline) {
                                            role,
                                            canonical ->
                                            artifacts.write(canonicalName(index, role), canonical)
                                        }
                                    CardTrajectoryFiles.save(
                                        output.resolve(worldName(index)),
                                        ExpansionOnPolicyStore.wire(world),
                                    )
                                    index
                                }
                            }
                        )
                    }
                require(completed.sorted() == indices.toList()) {
                    "Every original chunk world must complete exactly once."
                }
                for (index in ExpansionBaselineInput.REPLAYS.filter { it in indices }) {
                    val world =
                        requireNotNull(
                            CardTrajectoryFiles.readIfPresent(output.resolve(worldName(index)))
                        ) {
                            "Original world is required."
                        }
                    for (role in ExpansionBaselineRole.entries) {
                        val canonical =
                            PurchaseJson.read(output.resolve(canonicalName(index, role)))
                        val replay =
                            ExpansionBaselineScreen.reproduce(
                                input,
                                index,
                                role,
                                world,
                                canonical,
                                deadline,
                            )
                        artifacts.write("replay-$index-${role.name}.json", replay)
                    }
                }
                finish(input, output, "chunk-$chunk", 512, 6)
            }
            "expansion-baseline-screen-report" -> {
                val chunks = listOf(path("--first"), path("--second"))
                chunks.forEachIndexed { chunk, directory ->
                    verifyFinished(input, directory, "chunk-$chunk")
                }
                val worlds =
                    (0 until 1024).map { index ->
                        requireNotNull(
                                CardTrajectoryFiles.readIfPresent(
                                    chunks[index / 512].resolve(worldName(index))
                                )
                            ) {
                                "A full completed bank must retain all three-role worlds."
                            }
                            .also { ExpansionBaselineScreen.validateWorld(input, index, it) }
                    }
                artifacts.write("report.json", ExpansionBaselineScreen.report(worlds))
                artifacts.write(
                    "inputs.json",
                    mapOf(
                        "chunks" to
                            chunks.map {
                                mapOf(
                                    "path" to it.toString(),
                                    "finishedSha256" to
                                        PurchaseJson.hash(it.resolve("finished.json")),
                                )
                            },
                        "historicalSha256" to
                            PurchaseJson.hash(path("--historical").resolve("finished.json")),
                    ),
                )
                finish(input, output, "report", 1024, 14)
            }
        }
    }

    internal fun worldName(index: Int) = "world-$index.json.gz"

    private fun canonicalName(index: Int, role: ExpansionBaselineRole) =
        "canonical-$index-${role.name}.json"

    internal fun inventory(directory: Path): Map<String, String> =
        Files.walk(directory).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) &&
                        it.fileName.toString() !in setOf("finished.json", ".DS_Store")
                }
                .sorted()
                .toList()
                .associate { directory.relativize(it).toString() to PurchaseJson.hash(it) }
        }

    private fun finish(
        input: ExpansionBaselineInput,
        directory: Path,
        stage: String,
        worlds: Int,
        reproductions: Int,
    ) {
        input.verify()
        TrainingArtifacts(directory)
            .write(
                "finished.json",
                mapOf(
                    "kind" to ExpansionBaselineInput.VERSION,
                    "signature" to input.signature,
                    "stage" to stage,
                    "complete" to true,
                    "worlds" to worlds,
                    "reproductions" to reproductions,
                    "newFits" to 0,
                    "qualification" to false,
                    "files" to inventory(directory),
                ),
            )
    }

    internal fun verifyFinished(
        input: ExpansionBaselineInput,
        directory: Path,
        stage: String,
    ): JsonNode {
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        val worlds = if (stage == "historical") 0 else 512
        val reproductions = if (stage == "historical") 2 else 6
        require(
            finished["kind"]?.asText() == ExpansionBaselineInput.VERSION &&
                finished["signature"]?.asText() == input.signature &&
                finished["stage"]?.asText() == stage &&
                finished["complete"]?.booleanValue() == true &&
                finished["worlds"]?.intValue() == worlds &&
                finished["reproductions"]?.intValue() == reproductions &&
                finished["files"] == ExpansionOnPolicyStore.wire(inventory(directory))
        ) {
            "Completed $stage membership, source identity or terminal counts changed."
        }
        require(
            PurchaseJson.read(directory.resolve("manifest.json")) ==
                ExpansionOnPolicyStore.wire(input.manifest())
        ) {
            "Completed stage did not use the original full bank and exact role subjects."
        }
        return finished
    }
}
