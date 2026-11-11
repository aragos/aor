package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.Prompt
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TextCodec
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One metadata-selected original training root; seeds are paired across actions and modes. */
internal data class ChanceCouplingRoot(
    val index: Int,
    val sha256: String,
    val reference: Int,
    val alternative: Int,
    val seeds: List<Long>,
) {
    init {
        require(index in 0 until 512 && sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Chance coupling requires an original training index and its frozen SHA-256."
        }
        require(seeds.size == 16 && seeds.distinct().size == 16) {
            "Each chance root requires exactly sixteen distinct fresh paired streams."
        }
        require(reference in 0..127 && alternative in 0..127 && reference != alternative) {
            "Chance roots require distinct existing reference/alternative indices."
        }
    }
}

/** Metadata/source validation consumes no origins, futures or policy random samples. */
internal data class ChanceCouplingInput(
    val source: Path,
    val collection: Path,
    val specification: TokenOrderSpecification,
    val originalSignature: String,
    val roots: List<ChanceCouplingRoot>,
) {
    init {
        require(roots.size == 32 && roots.map { it.index }.distinct().size == 32) {
            "The chance diagnostic freezes exactly thirty-two distinct retained training requests."
        }
        require(roots.flatMap { it.seeds }.distinct().size == 512) {
            "The chance diagnostic requires 512 distinct prospective master streams."
        }
        require(
            specification.trainingRoots == 512 &&
                specification.validationRoots == 128 &&
                specification.continuationProtocol ==
                    TokenOrderContinuationProtocol.ARENA_OVERLAYS_EIGHT_TAILS_1
        ) {
            "The diagnostic retains the original ordinary-reference eight-tail collection profile."
        }
    }

    companion object {
        const val KIND = "logical-chance-coupling-diagnostic-1"

        fun read(path: Path): ChanceCouplingInput {
            val node = PurchaseJson.read(path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "collection",
                    "collectionSource",
                    "collectionSourceSha256",
                    "originalSourceSha256",
                    "datasetSha256",
                    "finishedSha256",
                    "roots",
                ),
            )
            require(node.required("kind").asText() == KIND) {
                "Unsupported chance diagnostic kind."
            }
            val collection = Path.of(node.required("collection").asText()).toRealPath()
            val source = Path.of(node.required("collectionSource").asText()).toRealPath()
            for ((name, hashField) in
                listOf(
                    "source.json" to "originalSourceSha256",
                    "dataset.json" to "datasetSha256",
                    "finished.json" to "finishedSha256",
                )) {
                require(
                    PurchaseJson.hash(collection.resolve(name)) == node.required(hashField).asText()
                ) {
                    "The original completed collection $name changed."
                }
            }
            require(PurchaseJson.hash(source) == node.required("collectionSourceSha256").asText()) {
                "The frozen original collection source changed."
            }
            require(PurchaseJson.hash(source) == node.required("originalSourceSha256").asText()) {
                "The diagnostic must parse the original collection source without rewriting roles or paths."
            }
            val specification = TokenOrderSpecification.read(source)
            val dataset = PurchaseJson.read(collection.resolve("dataset.json"))
            require(dataset.required("complete").asBoolean()) {
                "Retained origins must be complete."
            }
            val roots =
                node.required("roots").map { row ->
                    ChanceCouplingRoot(
                        row.required("index").asInt(),
                        row.required("sha256").asText(),
                        PurchaseJson.integer(row.required("reference"), "reference index"),
                        PurchaseJson.integer(row.required("alternative"), "alternative index"),
                        row.required("seeds").map(JsonNode::asLong),
                    )
                }
            roots.forEach { root ->
                val recorded = collection.resolve("origin-${root.index}.json")
                val origin = PurchaseJson.read(recorded)
                require(
                    PurchaseJson.hash(recorded) == root.sha256 &&
                        PurchaseJson.read(recorded).required("split").asText() == "TRAINING"
                ) {
                    "A chance root must retain its exact training-only record."
                }
                val hashes =
                    PurchaseJson.read(collection.resolve("finished.json")).required("originSha256")
                require(hashes[root.index].asText() == root.sha256) {
                    "The root hash must be bound by the original collection completion receipt."
                }
                require(
                    origin.required("status").asText() == "COLLECTED" &&
                        root.reference ==
                            origin.required("observation").required("baselineIndex").asInt() &&
                        root.alternative == ChanceCouplingDiagnostic.nearestNeighbor(origin)
                ) {
                    "Every retained root must be collected and bind its original reference/nearest proposal."
                }
            }
            return ChanceCouplingInput(
                path,
                collection,
                specification,
                dataset.required("signature").asText(),
                roots,
            )
        }
    }
}

/** No policy fitting or selection. Each tail issues ordinary commands and records exact replay. */
internal class ChanceCouplingDiagnostic(
    private val input: ChanceCouplingInput,
    private val deadline: Long,
) {
    private fun capture(root: ChanceCouplingRoot): TokenOrderCapture {
        val captured =
            TokenOrderCollector(input.specification, input.originalSignature, deadline)
                .capture(root.index, recordPrefix = true)
        val expected = PurchaseJson.read(input.collection.resolve("origin-${root.index}.json"))
        val withoutLabels =
            (expected.deepCopy<JsonNode>() as ObjectNode).apply { remove("alternatives") }
        require(
            withoutLabels ==
                PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(captured.origin))
        ) {
            "Reconstructed chance root differs from its original metadata, observation or offers."
        }
        return captured
    }

    fun verifyPrefix(root: ChanceCouplingRoot): Map<String, Any> {
        val captured = capture(root)
        return mapOf(
            "index" to root.index,
            "originSha256" to root.sha256,
            "originDecisions" to captured.origin.originDecisions,
            "exactActorObservationAndCommands" to true,
            "newContinuationWorlds" to 0,
        )
    }

    fun collect(root: ChanceCouplingRoot): Map<String, Any?> {
        val captured = capture(root)
        val session =
            requireNotNull(captured.session) {
                "An eligible retained root must reproduce its original collected decision."
            }
        val actor = requireNotNull(captured.actor) { "A chance root requires its sealed actor." }
        val observation =
            requireNotNull(captured.origin.observation) {
                "A collected chance root requires its original token observation."
            }
        val reference = root.reference
        val alternative = root.alternative
        val cpu = ManagementFactory.getThreadMXBean()
        require(cpu.isCurrentThreadCpuTimeSupported) {
            "The diagnostic requires per-thread CPU time."
        }
        if (!cpu.isThreadCpuTimeEnabled) cpu.isThreadCpuTimeEnabled = true
        val tails =
            root.seeds.flatMapIndexed { replicate, seed ->
                val modes =
                    ExperimentChanceMode.entries.let { values ->
                        if ((root.index + replicate) % 2 == 0) values else values.reversed()
                    }
                modes.flatMap { mode ->
                    listOf(reference, alternative)
                        .let { actions ->
                            if ((root.index + replicate) % 2 == 0) actions else actions.reversed()
                        }
                        .map { action ->
                            checkTokenValueDeadline(deadline)
                            val started = System.nanoTime()
                            val startedCpu = cpu.currentThreadCpuTime
                            val branch = session.chanceFork(seed, mode)
                            branch.apply(
                                captured.commands + (actor to observation.actions[action].command),
                                deadline,
                            )
                            val outcome =
                                captured.continuation.finish(
                                    branch,
                                    captured.origin.seat,
                                    captured.origin.configuration,
                                    seed,
                                )
                            val cpuNanos = cpu.currentThreadCpuTime - startedCpu
                            val wallNanos = System.nanoTime() - started
                            val auditStarted = cpu.currentThreadCpuTime
                            val canonical = branch.entries.map(Entry::toText)
                            val trace = replay(session.state, branch.state, canonical, seed, mode)
                            require(cpuNanos > 0 && wallNanos > 0) {
                                "Measured tail costs must be positive."
                            }
                            mapOf(
                                "replicate" to replicate,
                                "mode" to mode.name,
                                "action" to action,
                                "seed" to seed,
                                "outcome" to outcome,
                                "cpuNanos" to cpuNanos,
                                "wallNanos" to wallNanos,
                                "canonical" to canonical,
                                "chanceTrace" to trace,
                                "auditCpuNanos" to cpu.currentThreadCpuTime - auditStarted,
                                "replayVerified" to true,
                            )
                        }
                }
            }
        return mapOf(
            "index" to root.index,
            "status" to "COLLECTED",
            "originSha256" to root.sha256,
            "prefixHeader" to session.state.header,
            "prefixEntries" to session.entries.map(Entry::toText),
            "root" to captured.origin,
            "reference" to reference,
            "alternative" to alternative,
            "tails" to tails,
        )
    }

    /** Replay and trace extraction happen after the timed simulation, for both sampling modes. */
    private fun replay(
        initial: GameState,
        terminal: GameState,
        canonical: List<String>,
        seed: Long,
        mode: ExperimentChanceMode,
    ): List<ExperimentChanceRecord> {
        val legacy = ExperimentRandom(seed)
        val sampler = ExperimentChance(mode, seed)
        val replay =
            canonical.fold(initial) { state, text ->
                checkTokenValueDeadline(deadline)
                val entry = TextCodec.parse(text)
                if (entry !is Entry.Action) {
                    val need = (state.prompts().singleOrNull() as? Prompt.System)?.need
                    requireNotNull(need) {
                        "A recorded authority outcome requires its original request."
                    }
                    val pending =
                        if (need is SystemNeed.Draw) null else sampler.prepare(state, need, legacy)
                    val expected =
                        ExperimentOutcomes.from(
                            state,
                            need,
                            pending?.random ?: legacy,
                            mode == ExperimentChanceMode.LOGICAL_REQUEST_1,
                        )
                    require(expected == entry) {
                        "Chance replay changed a recorded random-table outcome."
                    }
                    if (pending != null) sampler.accepted(pending, entry)
                }
                state.step(entry)
            }
        require(replay == terminal) { "Every chance tail must replay its exact terminal state." }
        return sampler.trace
    }

    companion object {
        /** Pure offered-action rule: no rewards, predictions, validation or future state. */
        fun nearestNeighbor(origin: JsonNode): Int {
            val observation = origin.required("observation")
            val baseline = observation.required("baselineIndex").asInt()
            val actions = observation.required("actions")
            fun amount(index: Int) =
                actions[index].required("command").asText().removePrefix("buy-tokens ").toInt()
            return requireNotNull(
                origin
                    .required("proposedIndices")
                    .map(JsonNode::asInt)
                    .filter { it != baseline }
                    .minWithOrNull(
                        compareBy<Int> { kotlin.math.abs(amount(it) - amount(baseline)) }
                            .thenBy { actions[it].required("command").asText() }
                    )
            ) {
                "A collected root requires a distinct existing token proposal."
            }
        }
    }
}

/** One-shot bounded native entry point. Preparation validates input without capturing roots. */
object ChanceCouplingMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Chance diagnostic options must be name/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        if (fields["--mode"] == "chance-coupling-vectors") {
            require(
                fields.size * 2 == arguments.size && fields.keys == setOf("--mode", "--output")
            ) {
                "Chance vectors require only mode and a fresh output directory."
            }
            val output = Path.of(requireNotNull(fields["--output"]) { "Missing vector output." })
            require(!Files.exists(output)) { "Preserve previous codec vectors." }
            TrainingArtifacts(output).write("vectors.json", ChanceCouplingVectors.result())
            return
        }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys == setOf("--mode", "--source", "--output", "--workers", "--hours") &&
                fields["--mode"] in
                    setOf(
                        "chance-coupling-prepare",
                        "chance-coupling",
                        "chance-coupling-prefix-check",
                    )
        ) {
            "Chance diagnostic requires its explicit mode, source, output, workers and hours."
        }
        val source =
            Path.of(requireNotNull(fields["--source"]) { "Missing chance source." }).toRealPath()
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Missing chance output." })
                .toAbsolutePath()
        require(!Files.exists(output)) {
            "Chance diagnostic is one attempt; output must be absent."
        }
        require(fields["--workers"] == "3" && fields["--hours"] == "1.5") {
            "Chance diagnostic freezes three workers and ninety minutes."
        }
        val input = ChanceCouplingInput.read(source)
        val cpu = ManagementFactory.getThreadMXBean()
        require(cpu.isCurrentThreadCpuTimeSupported) {
            "Per-thread CPU timing is required before any tails."
        }
        if (!cpu.isThreadCpuTimeEnabled) cpu.isThreadCpuTimeEnabled = true
        require(cpu.currentThreadCpuTime >= 0) { "The diagnostic CPU timer is unavailable." }
        val artifacts = TrainingArtifacts(output)
        val sourceHash = PurchaseJson.hash(source)
        artifacts.ensureFileSnapshot(source, sourceHash, "source.json")
        if (fields["--mode"] == "chance-coupling-prepare") {
            artifacts.write(
                "prepared.json",
                mapOf(
                    "kind" to ChanceCouplingInput.KIND,
                    "sourceSha256" to sourceHash,
                    "roots" to 32,
                    "maximumTails" to 2048,
                    "newGames" to 0,
                    "actualFits" to 0,
                    "cpuTimerAvailable" to true,
                ),
            )
            return
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + 5_400_000_000_000L
        val diagnostic = ChanceCouplingDiagnostic(input, deadline)
        if (fields["--mode"] == "chance-coupling-prefix-check") {
            val receipts =
                Tournaments(3, deadline).use { pool ->
                    pool.run(input.roots.map { root -> { diagnostic.verifyPrefix(root) } }) {}
                }
            artifacts.write(
                "prefix-check.json",
                mapOf(
                    "kind" to ChanceCouplingInput.KIND,
                    "sourceSha256" to sourceHash,
                    "roots" to receipts,
                    "retainedPrefixes" to 32,
                    "newOriginWorlds" to 0,
                    "newContinuationWorlds" to 0,
                    "actualFits" to 0,
                ),
            )
            return
        }
        val results =
            Tournaments(3, deadline).use { pool ->
                pool.run(
                    input.roots.map { root ->
                        {
                            val result = diagnostic.collect(root)
                            artifacts.write("origin-${root.index}.json", result)
                            result
                        }
                    }
                ) {
                    println("Completed chance diagnostic root.")
                }
            }
        require(PurchaseJson.hash(source) == sourceHash) { "Chance diagnostic source changed." }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to ChanceCouplingInput.KIND,
                "sourceSha256" to sourceHash,
                "rootCount" to results.size,
                "startedAt" to started.toString(),
                "finishedAt" to Instant.now().toString(),
                "originSha256" to
                    input.roots.associate { root ->
                        "origin-${root.index}.json" to
                            PurchaseJson.hash(output.resolve("origin-${root.index}.json"))
                    },
                "productionActivation" to false,
                "qualificationPassed" to false,
            ),
        )
    }
}
