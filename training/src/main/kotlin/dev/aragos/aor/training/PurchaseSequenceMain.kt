package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One bounded invocation, with no refill, resume, retry, policy fitting or activation. */
internal object PurchaseSequenceMain {
    fun run(arguments: List<String>) {
        val started = System.nanoTime()
        val options = PurchaseSequenceOptions.parse(arguments)
        val input = PurchaseSequenceInput.read(options.source)
        require(options.profile == input.study.profile) {
            "The source profile changed between option parsing and complete input validation."
        }
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val deadline = started + (options.hours * 3_600_000_000_000L).toLong()
        checkTokenValueDeadline(deadline)
        val store =
            PurchaseSequenceStore(
                input,
                requireNotNull(options.output) { "Collection requires an output directory." },
            )
        try {
            var completed = 0
            Tournaments(options.workers, deadline).use { workers ->
                workers.run(
                    input.study.origins.indices.map { index ->
                        {
                            val teacher =
                                PurchaseSequenceTeacher(
                                    input.study,
                                    deadline,
                                    store.originArtifacts(index),
                                )
                            store.save(teacher.collect(index))
                            index
                        }
                    }
                ) {
                    completed++
                    store.progress("collecting", completed)
                    println("Purchase sequence origins $completed/${input.study.profile.origins}.")
                }
            }
            checkTokenValueDeadline(deadline)
            store.finish(deadline)
        } catch (failure: Exception) {
            store.failed(failure)
            throw failure
        }
    }
}

internal data class PurchaseSequenceOptions(
    val source: Path,
    val output: Path?,
    val workers: Int,
    val hours: Double,
    val describe: Boolean,
    val profile: PurchaseSequenceProfile = PurchaseSequenceProfile.PILOT_32,
) {
    companion object {
        fun parse(arguments: List<String>): PurchaseSequenceOptions {
            require(arguments.size % 2 == 0) {
                "Use --option value pairs for the purchase sequence teacher."
            }
            val fields = arguments.chunked(2).associate { it[0] to it[1] }
            require(
                fields.size * 2 == arguments.size &&
                    fields.keys.all {
                        it in
                            setOf(
                                "--mode",
                                "--source",
                                "--output",
                                "--workers",
                                "--hours",
                                "--describe",
                            )
                    }
            ) {
                "Teacher options cannot repeat or include unknown fields."
            }
            require(fields["--mode"] == "purchase-sequence") {
                "The purchase-sequence mode must be explicit."
            }
            val describe =
                fields["--describe"]?.let { value ->
                    require(value in setOf("true", "false")) { "Describe must be true or false." }
                    value == "true"
                } ?: false
            val source =
                Path.of(
                        requireNotNull(fields["--source"]) {
                            "Teacher input requires --source PATH."
                        }
                    )
                    .toRealPath()
            val output = fields["--output"]?.let { Path.of(it).toAbsolutePath().normalize() }
            require(describe || output != null) { "Collection requires --output NEW_DIRECTORY." }
            val cpus =
                System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                    ?: Runtime.getRuntime().availableProcessors()
            val maximum = ((cpus * .8).toInt() - 2).coerceAtLeast(1)
            val workers = fields["--workers"]?.toInt() ?: maximum
            require(workers in 1..maximum) {
                "Use 1–$maximum workers under the unchanged native CPU allowance."
            }
            val hours = fields["--hours"]?.toDouble() ?: 1.0
            val profile = PurchaseSequenceProfile.read(PurchaseJson.read(source)["profile"])
            require(hours.isFinite() && hours > 0 && hours <= profile.maximumHours) {
                "Teacher hours must be finite, positive and at most ${profile.maximumHours}."
            }
            return PurchaseSequenceOptions(source, output, workers, hours, describe, profile)
        }
    }
}

/** New-directory ownership prevents a failed/partial origin from becoming an automatic retry. */
internal class PurchaseSequenceStore(
    private val input: PurchaseSequenceInput,
    val directory: Path,
) {
    private val artifacts: TrainingArtifacts
    private val startedAt = Instant.now()

    init {
        require(!Files.exists(directory)) {
            "A teacher invocation requires a completely new output directory."
        }
        Files.createDirectories(
            requireNotNull(directory.parent) { "Teacher output requires a parent directory." }
        )
        Files.createDirectory(directory)
        artifacts = TrainingArtifacts(directory)
        input.snapshot(directory.resolve("inputs"))
        artifacts.write("manifest.json", input.manifest())
        progress("starting", 0)
    }

    fun originArtifacts(index: Int): TrainingArtifacts {
        require(index in input.study.origins.indices) {
            "Origin artifacts require a declared source index."
        }
        return TrainingArtifacts(directory.resolve("origins").resolve("origin-$index"))
    }

    fun save(result: PurchaseSequenceResult) {
        val owner = originArtifacts(result.index)
        require(owner.read("result.json") == null) { "A completed origin cannot be overwritten." }
        val node = PurchaseJson.mapper.valueToTree<JsonNode>(result)
        verify(node, result.index, owner.directory)
        owner.write("result.json", result)
    }

    fun progress(status: String, completed: Int) {
        artifacts.write(
            "state.json",
            mapOf(
                "signature" to input.signature(),
                "status" to status,
                "completedOrigins" to completed,
                "startedAt" to startedAt.toString(),
            ),
        )
    }

    fun failed(failure: Exception) {
        artifacts.write(
            "failure.json",
            mapOf(
                "signature" to input.signature(),
                "exception" to failure.javaClass.name,
                "message" to failure.message,
                "failedAt" to Instant.now().toString(),
            ),
        )
    }

    fun finish(deadlineNanos: Long) {
        input.verifySources()
        val origins =
            input.study.origins.indices.map { index ->
                checkTokenValueDeadline(deadlineNanos)
                val path = directory.resolve("origins/origin-$index")
                val node = PurchaseJson.read(path.resolve("result.json"))
                verify(node, index, path)
                val tails =
                    node["root"]?.takeUnless(JsonNode::isNull)?.required("tails")?.size() ?: 0
                tails to node.required("missingRoot").booleanValue()
            }
        val tails = origins.sumOf { it.first }
        val missing = origins.count { it.second }
        check(tails <= input.study.profile.maximumTails) {
            "Completed tails exceed the fixed study allowance."
        }
        var incidentalFinderFiles = 0
        val hashes =
            Files.walk(directory).use { files ->
                files
                    .filter(Files::isRegularFile)
                    .filter { path ->
                        if (path.fileName.toString() == ".DS_Store") {
                            incidentalFinderFiles++
                            false
                        } else {
                            path.fileName.toString() != "state.json"
                        }
                    }
                    .sorted()
                    .toList()
                    .associate { path ->
                        checkTokenValueDeadline(deadlineNanos)
                        directory.relativize(path).toString() to PurchaseJson.hash(path)
                    }
            }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to PurchaseSequenceProtocol.VERSION,
                "signature" to input.signature(),
                "completed" to true,
                "origins" to origins.size,
                "missingRoots" to missing,
                "simulatedBackbones" to origins.size,
                "simulatedTails" to tails,
                "fullCanonicalTraversals" to origins.size + tails,
                "partialPrefixReconstructions" to origins.size - missing,
                "additionalFullPolicyReproductions" to 0,
                "incidentalFinderFilesExcluded" to incidentalFinderFiles,
                "fits" to 0,
                "qualification" to false,
                "activation" to false,
                "artifactSha256" to hashes,
            ) + input.study.profile.metadata(),
        )
        progress("completed", origins.size)
    }

    private fun verify(node: JsonNode, index: Int, directory: Path) {
        require(
            node.required("version").asText() == PurchaseSequenceProtocol.VERSION &&
                node.required("index").intValue() == index
        ) {
            "Origin identity differs from the fixed teacher profile."
        }
        require(wire(node.required("origin")) == wire(input.study.origins[index])) {
            "An origin must retain its exact world, seat, rival indices and streams."
        }
        require(
            node.required("environmentSha256").asText() ==
                CardAdvantageExecutor.hash(listOf(input.study.arena.identity()))
        ) {
            "An origin belongs to a different frozen controller environment."
        }
        val backbone = verifyEvidence(node.required("backboneEvidence"), directory)
        require(
            CardAdvantageExecutor.hash(
                PurchaseJson.strings(backbone.required("entries"), "entries")
            ) == node.required("backboneCanonicalSha256").asText()
        ) {
            "Backbone canonical entries differ from their retained hash."
        }
        val root = node["root"]?.takeUnless(JsonNode::isNull)
        require(node.required("missingRoot").booleanValue() == (root == null)) {
            "Missing roots cannot contain invented teacher tails."
        }
        if (root == null) return
        val plans = PurchaseJson.array(root.required("proposals").required("plans"), "plans")
        require(
            plans.size in 1..PurchaseSequenceProtocol.PLANS &&
                PurchaseJson.strings(plans.first().required("commands"), "commands") ==
                    listOf("pass")
        ) {
            "The bounded plan set must retain incumbent pass."
        }
        val prefixes = plans.map { PurchaseJson.strings(it.required("commands"), "commands") }
        val expected =
            (prefixes.map { PurchaseSequenceArm.COMPLETE_PREFIX.name to it } +
                    prefixes
                        .map { PurchaseSequenceArm.FIRST_ACTION_ONLY.name to listOf(it.first()) }
                        .distinct())
                .flatMap { (arm, commands) ->
                    input.study.origins[index].continuationSeeds.map { Triple(arm, commands, it) }
                }
        val tails = PurchaseJson.array(root.required("tails"), "tails")
        val actual = tails.map {
            Triple(
                it.required("arm").asText(),
                PurchaseJson.strings(it.required("commands"), "commands"),
                stream(it.required("continuationSeed")),
            )
        }
        require(actual == expected) {
            "Every selected sibling and distinct first-action control requires both ordered shared streams."
        }
        for (tail in tails) {
            require(
                stream(tail.required("outcome").required("continuationSeed")) ==
                    stream(tail.required("continuationSeed"))
            ) {
                "A terminal outcome must retain its declared continuation stream."
            }
            require(tail.required("replayVerified").booleanValue()) {
                "Every terminal tail requires canonical verification."
            }
            val canonical = verifyEvidence(tail.required("canonicalEvidence"), directory)
            require(
                canonical.required("backbone") == node.required("backboneEvidence") &&
                    canonical.required("arm") == tail.required("arm") &&
                    canonical.required("commands") == tail.required("commands") &&
                    stream(canonical.required("continuationSeed")) ==
                        stream(tail.required("continuationSeed"))
            ) {
                "A canonical suffix must bind this backbone, arm, forced commands and stream."
            }
            require(
                CardAdvantageExecutor.hash(
                    PurchaseJson.strings(canonical.required("entries"), "entries")
                ) == tail.required("canonicalSha256").asText()
            ) {
                "Tail canonical entries differ from their retained hash."
            }
        }
    }

    private fun wire(value: Any): JsonNode =
        PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

    private fun stream(value: JsonNode): Long {
        require(value.isIntegralNumber && value.canConvertToLong()) {
            "A stored continuation stream must be a signed 64-bit integer."
        }
        return value.longValue()
    }

    private fun verifyEvidence(reference: JsonNode, directory: Path): JsonNode {
        val name = PurchaseJson.text(reference.required("file"), "evidence file")
        require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "Canonical evidence must be a simple local file name."
        }
        val path = directory.resolve(name)
        require(PurchaseJson.hash(path) == reference.required("sha256").asText()) {
            "Canonical evidence bytes changed after execution."
        }
        return PurchaseJson.read(path)
    }
}
