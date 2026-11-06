package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotModel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs

/** Source is read-only; output must be a separate directory, including through symbolic links. */
data class PilotDiagnosticOptions(
    val source: Path,
    val output: Path,
    val workers: Int,
    val hours: Double,
    val seed: Int,
    val samples: Int,
) {
    /** Resource limits and directory locations do not change the sampled experiment. */
    fun identity(): String = "$seed:$samples"

    companion object {
        fun parse(arguments: List<String>): PilotDiagnosticOptions {
            require(arguments.size % 2 == 0) {
                "Use --option value pairs for pilot diagnostic arguments."
            }
            val fields = arguments.chunked(2).associate { it[0] to it[1] }
            require(fields.size == arguments.size / 2) { "Diagnostic options may not repeat." }
            val allowed =
                setOf(
                    "--mode",
                    "--source",
                    "--output",
                    "--workers",
                    "--hours",
                    "--seed",
                    "--samples",
                )
            require(fields.keys.all { it in allowed }) { "Unknown pilot diagnostic option." }
            require(fields["--mode"] == "pilot-diagnose") { "Diagnostic mode must be explicit." }
            fun integer(name: String, default: Int, range: IntRange): Int {
                val value =
                    fields[name]?.let {
                        requireNotNull(it.toIntOrNull()) { "$name must be an integer." }
                    } ?: default
                require(value in range) {
                    "$name must be between ${range.first} and ${range.last}."
                }
                return value
            }
            fun path(name: String): Path {
                val value = requireNotNull(fields[name]) { "Diagnostic mode requires $name PATH." }
                require(value.isNotBlank()) { "$name must be a nonempty path." }
                return Path.of(value).toAbsolutePath().normalize()
            }
            val sourcePath = path("--source")
            require(Files.isDirectory(sourcePath)) {
                "Diagnostic source must be an existing directory."
            }
            val source = sourcePath.toRealPath()
            val output = canonicalOutput(path("--output"))
            require(!source.startsWith(output) && !output.startsWith(source)) {
                "Diagnostic source and output directories must not overlap."
            }
            val cpus =
                System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                    ?: Runtime.getRuntime().availableProcessors()
            val maximumWorkers = ((cpus * 0.8).toInt() - 2).coerceAtLeast(1)
            val hours =
                fields["--hours"]?.let {
                    requireNotNull(it.toDoubleOrNull()) { "--hours must be a number." }
                } ?: 2.0
            require(hours.isFinite() && hours > 0 && hours <= 72) {
                "Diagnostic hours must be positive and at most 72."
            }
            return PilotDiagnosticOptions(
                source = source,
                output = output,
                workers = integer("--workers", maximumWorkers, 1..maximumWorkers),
                hours = hours,
                seed = integer("--seed", 20261107, 0..100_000_000),
                samples = integer("--samples", 32, 1..256),
            )
        }

        private fun canonicalOutput(path: Path): Path {
            val existing =
                requireNotNull(
                    generateSequence(path) { it.parent }.firstOrNull { Files.exists(it) }
                ) {
                    "Diagnostic output requires an existing filesystem ancestor."
                }
            require(Files.isDirectory(existing)) { "Diagnostic output must be a directory path." }
            return existing.toRealPath().resolve(existing.relativize(path))
        }
    }
}

/**
 * Validated snapshot of a completed schema-2 pilot. Reading creates no directories or files.
 * [sourceHash] covers exact bytes of the manifest, checkpoint, models and every position/validation
 * unit; incidental logs and runtime status are not part of experiment identity.
 */
class PilotDiagnosticInput(source: Path) {
    val run: JsonNode
    val student: PilotStudent
    val baseline: BotModel
    val rows: List<JsonNode>
    val validation: Map<Int, JsonNode>
    val sourceHash: String
    val seed: Int

    init {
        require(Files.isDirectory(source)) { "Diagnostic source must be an existing directory." }
        val mapper = ObjectMapper()
        val bytes = mutableMapOf<String, ByteArray>()
        fun read(name: String): JsonNode {
            val path = source.resolve(name)
            require(Files.isRegularFile(path)) { "Diagnostic source requires $name." }
            val contents = Files.readAllBytes(path)
            bytes[name] = contents
            val node = mapper.readTree(contents)
            require(node != null && node.isObject) {
                "Diagnostic source $name must be a JSON object."
            }
            return node
        }
        run = read("run.json")
        val state = read("state.json")
        require(
            run["kind"]?.asText() == "automata-pilot" &&
                state["kind"]?.asText() == "automata-pilot" &&
                state["phase"]?.asText() == "complete"
        ) {
            "Diagnostic source must be a completed automata pilot."
        }
        val signature = run["signature"]?.asText()
        require(!signature.isNullOrBlank() && state["signature"]?.asText() == signature) {
            "Diagnostic source manifest and checkpoint signatures must match."
        }
        require(
            run["rulesVersion"]?.asText() == RULES_VERSION &&
                run["dataVersion"]?.asText() == DATA_VERSION
        ) {
            "Diagnostic source uses a different rules or catalog version."
        }
        require(run["configuration"]?.asText() == CommodityExperiment.configuration.id) {
            "Diagnostic source uses a different pilot configuration."
        }
        val teacher = read("teacher.json")
        val studentNode = read("student.json")
        for (node in listOf(run, teacher, studentNode)) {
            require(node["curriculumVersion"]?.asText() == CommodityExperiment.VERSION) {
                "Diagnostic source uses a different curriculum version."
            }
        }
        for (node in listOf(teacher, studentNode)) {
            require(strings(node, "featureNames") == CommodityExperiment.featureNames) {
                "Diagnostic source feature names must match the current pilot order."
            }
            require(
                strings(node, "strategies") == CommodityExperimentStrategy.entries.map { it.name }
            ) {
                "Diagnostic source strategies must match the current pilot order."
            }
        }
        require(integer(studentNode, "schemaVersion") == 2) {
            "Diagnostic source requires student schema 2."
        }
        require(
            number(studentNode, "minimumPredictedAdvantage") ==
                PilotStudent.DEFAULT_MINIMUM_ADVANTAGE
        ) {
            "Diagnostic source uses a different student prediction margin."
        }
        student = PilotStudent.fromJson(studentNode.required("network"))
        require(student.inputWeights.first().size == CommodityExperiment.featureNames.size) {
            "Diagnostic student input dimension must match the curriculum features."
        }
        val baselineNode = read("baseline.json")
        require(
            integer(baselineNode, "schemaVersion") == 1 &&
                baselineNode["rulesVersion"]?.asText() == RULES_VERSION &&
                baselineNode["dataVersion"]?.asText() == DATA_VERSION
        ) {
            "Diagnostic source baseline requires the current model schema, rules and catalog."
        }
        val weights = baselineNode.required("weights")
        require(weights.isObject) { "Diagnostic baseline weights must be an object." }
        baseline =
            BotModel(
                weights =
                    weights.properties().associate { (name, value) ->
                        require(value.isNumber && value.doubleValue().isFinite()) {
                            "Diagnostic baseline weight $name must be finite."
                        }
                        name to value.doubleValue()
                    }
            )
        val settings =
            run.required("settings").asText().split(':').map { field ->
                requireNotNull(field.toIntOrNull()) {
                    "Diagnostic source settings must contain integer experiment counts."
                }
            }
        require(
            settings.size == 5 &&
                settings[0] in 0..100_000_000 &&
                settings[2] in 2..10000 &&
                settings[3] in 1..256
        ) {
            "Diagnostic source settings require the pilot seed and valid experiment counts."
        }
        seed = settings[0]
        val positions = settings[2]
        val samples = settings[3]
        require(
            integer(teacher, "positions") == positions &&
                integer(teacher, "samplesPerStrategy") == samples
        ) {
            "Diagnostic source teacher counts must match its run settings."
        }
        val heldOut = (0 until positions).filter { it % 5 == 0 }
        require(
            integer(studentNode, "trainingPositions") == positions - heldOut.size &&
                integer(studentNode, "validationPositions") == heldOut.size
        ) {
            "Diagnostic source student split counts must match its run settings."
        }
        val expectedNames =
            (0 until positions).map { "position-$it.json" }.toSet() +
                heldOut.map { "validation-$it.json" }
        val actualNames =
            Files.list(source).use { paths ->
                paths
                    .map { it.fileName.toString() }
                    .filter { it.matches(Regex("(?:position|validation)-[0-9]+\\.json")) }
                    .toList()
                    .toSet()
            }
        require(actualNames == expectedNames) {
            "Diagnostic source must contain exactly every position and held-out validation unit."
        }
        rows =
            (0 until positions).map { index ->
                val row = read("position-$index.json")
                require(
                    integer(row, "index") == index && integer(row, "seed") == seed + index * 8191
                ) {
                    "Diagnostic position $index has an inconsistent index or scenario seed."
                }
                require(row["split"]?.asText() == if (index % 5 == 0) "validation" else "train") {
                    "Diagnostic position $index has an inconsistent train/validation split."
                }
                val features = numbers(row, "features")
                require(features.size == CommodityExperiment.featureNames.size) {
                    "Diagnostic position $index has an inconsistent feature dimension."
                }
                val strategies = row.required("strategies")
                require(
                    strategies.isArray &&
                        strategies.map { it.required("strategy").asText() } ==
                            CommodityExperimentStrategy.entries.map { it.name }
                ) {
                    "Diagnostic position $index must contain every strategy in curriculum order."
                }
                val values = strategies.map { strategy ->
                    val rewards = rewards(strategy, "rewards", samples)
                    val mean = number(strategy.required("reward"), "mean")
                    require(abs(mean - rewards.average()) < 1e-12) {
                        "Diagnostic position $index has an inconsistent sampled reward mean."
                    }
                    mean
                }
                PilotExample(features, values)
                val baselineRewards = rewards(row, "baselineRewards", samples)
                require(baselineRewards == numbers(strategies[0], "rewards")) {
                    "Diagnostic position $index must retain the sampled baseline as its first strategy."
                }
                row
            }
        validation = heldOut.associateWith { index ->
            val result = read("validation-$index.json")
            val row = rows[index]
            require(
                integer(result, "scenarioSeed") == integer(row, "seed") &&
                    result["tag"]?.asText() == row["tag"]?.asText()
            ) {
                "Diagnostic validation $index must refer to its original scenario."
            }
            val strategy = result.required("strategy").asText()
            val expectedStrategy =
                CommodityExperimentStrategy.entries[student.choose(numbers(row, "features"))].name
            require(strategy == expectedStrategy) {
                "Diagnostic validation $index must retain the frozen student's selected strategy."
            }
            PilotValidationOutcome(
                scenarioSeed = integer(result, "scenarioSeed"),
                tag = result.required("tag").asText(),
                baselineRewards = rewards(result, "baselineRewards", samples),
                studentRewards = rewards(result, "studentRewards", samples),
                strategy = strategy,
            )
            result
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, contents) in bytes.toSortedMap()) {
            digest.update("$name\n${contents.size}\n".toByteArray())
            digest.update(contents)
        }
        sourceHash = digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun integer(node: JsonNode, name: String): Int {
        val value = node.required(name)
        require(value.isIntegralNumber && value.canConvertToInt()) {
            "Diagnostic $name must be an integer."
        }
        return value.intValue()
    }

    private fun number(node: JsonNode, name: String): Double {
        val value = node.required(name)
        require(value.isNumber && value.doubleValue().isFinite()) {
            "Diagnostic $name must be finite."
        }
        return value.doubleValue()
    }

    private fun strings(node: JsonNode, name: String): List<String> {
        val value = node.required(name)
        require(value.isArray && value.all { it.isTextual }) {
            "Diagnostic $name must be a string array."
        }
        return value.map { it.asText() }
    }

    private fun numbers(node: JsonNode, name: String): List<Double> {
        val value = node.required(name)
        require(value.isArray && value.all { it.isNumber && it.doubleValue().isFinite() }) {
            "Diagnostic $name must be a finite numerical array."
        }
        return value.map { it.doubleValue() }
    }

    private fun rewards(node: JsonNode, name: String, samples: Int): List<Double> =
        numbers(node, name).also { values ->
            require(values.size == samples && values.all { it in 0.0..1.0 }) {
                "Diagnostic $name must contain $samples rewards between zero and one."
            }
        }
}
