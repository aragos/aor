package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Reevaluate frozen choices on fresh chance streams, keeping the original experiment read-only. */
object PilotDiagnosticMain {
    fun run(arguments: List<String>) {
        val options = PilotDiagnosticOptions.parse(arguments)
        val input = PilotDiagnosticInput(options.source)
        val selections =
            input.rows
                .filter { it.required("split").asText() == "validation" }
                .map { PilotDiagnostic.select(it, input.student) }
        val cases = replayCases(input, selections)
        val signature =
            trainingSignature("pilot-diagnostic-1:${input.sourceHash}:${options.identity()}")
        val artifacts = TrainingArtifacts(options.output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == "automata-pilot-diagnostic" &&
                    previous["signature"]?.asText() == signature
        ) {
            "Diagnostic code, source or settings changed; use a new output directory."
        }
        if (previous == null) {
            require(artifacts.read("run.json") == null && artifacts.read("plan.json") == null) {
                "Diagnostic output already contains another run; choose a new directory."
            }
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
        require(remaining > 0) { "The diagnostic has exhausted its three-day allowance." }
        val deadline = System.nanoTime() + remaining
        fun phase(name: String) {
            artifacts.write(
                "state.json",
                mapOf(
                    "kind" to "automata-pilot-diagnostic",
                    "signature" to signature,
                    "phase" to name,
                ),
            )
            println("Diagnostic: $name")
        }
        if (previous == null) phase("initialized")
        if (artifacts.read("plan.json") == null) {
            artifacts.write(
                "plan.json",
                mapOf(
                    "source" to options.source.toString(),
                    "sourceHash" to input.sourceHash,
                    "sourceSignature" to input.run.required("signature").asText(),
                    "selections" to selections,
                    "replayCases" to cases,
                    "samples" to options.samples,
                    "seed" to options.seed,
                    "seedMethod" to
                        "Negative seeds disjoint from original teacher, validation and tournament streams",
                    "teacherSelection" to
                        "Maximum original sampled mean; values within 1e-12 tie in enum order, baseline first",
                    "scope" to
                        "Post-hoc diagnostic on all original held-out roots; no fitting or promotion",
                ),
            )
        }
        artifacts.write(
            "run.json",
            mapOf(
                "kind" to "automata-pilot-diagnostic",
                "signature" to signature,
                "sourceHash" to input.sourceHash,
                "source" to options.source.toString(),
                "startedAt" to started.toString(),
                "settings" to options.identity(),
                "workers" to options.workers,
                "scenarios" to selections.size,
                "javaVersion" to System.getProperty("java.version"),
                "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            ),
        )
        phase("replay")
        Tournaments(options.workers, deadline).use { workers ->
            val runnerOptions =
                PilotOptions(
                    output = options.output,
                    model = null,
                    workers = options.workers,
                    hours = options.hours,
                    seed = options.seed,
                    benchmarkGames = 1,
                    positions = input.rows.size,
                    samples = options.samples,
                    pairs = 1,
                )
            val runner = PilotRunner(runnerOptions, artifacts, input.baseline, deadline, workers)
            for (case in cases) {
                val selection = selections.single { it.index == case.index }
                val scenario = verifiedScenario(input, selection)
                for (strategy in CommodityExperimentStrategy.entries) {
                    val name = "replay-${case.index}-${strategy.name}.json"
                    val replay =
                        cached(artifacts, name) {
                            runner.replay(scenario, case.seed, strategy)
                        }
                    require(replay.required("replayVerified").booleanValue()) {
                        "Diagnostic replay ${case.index}/$strategy must reproduce its recorded entries."
                    }
                    val seat = scenario.state.playerIds.indexOf(scenario.actor)
                    val reward = replay.required("game").required("rewards")[seat].doubleValue()
                    val original =
                        checkNotNull(input.validation[case.index]) {
                            "A replay case requires original validation outcomes."
                        }
                    if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
                        verifyOriginalReward(
                            reward,
                            original.required("baselineRewards")[case.sample].doubleValue(),
                            case,
                            strategy,
                        )
                    }
                    if (strategy == selection.student) {
                        verifyOriginalReward(
                            reward,
                            original.required("studentRewards")[case.sample].doubleValue(),
                            case,
                            strategy,
                        )
                    }
                }
                println(
                    "Replayed position ${case.index}; original baseline/student rewards reproduced"
                )
            }
            phase("compare")
            var completed = 0
            val outcomes =
                workers.run(
                    selections.map { selection ->
                        {
                            val node =
                                cached(artifacts, "comparison-${selection.index}.json") {
                                    compare(
                                        options,
                                        runner,
                                        verifiedScenario(input, selection),
                                        selection,
                                    )
                                }
                            PilotDiagnosticOutcome(
                                selection = selection,
                                baselineRewards =
                                    node.required("baselineRewards").map { it.doubleValue() },
                                teacherRewards =
                                    node.required("teacherRewards").map { it.doubleValue() },
                                studentRewards =
                                    node.required("studentRewards").map { it.doubleValue() },
                            )
                        }
                    }
                ) {
                    completed++
                    println("Diagnostic position $completed/${selections.size}")
                }
            val report = PilotDiagnostic.report(outcomes)
            artifacts.write("diagnostic.json", report)
            println("Teacher minus baseline: ${report.teacherVsBaseline}")
            println("Student minus baseline: ${report.studentVsBaseline}")
            println("Teacher minus student: ${report.teacherVsStudent}")
        }
        phase("complete")
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println(
            "Diagnostic complete: ${options.output.resolve("diagnostic.json")}; no model was changed."
        )
    }

    private fun compare(
        options: PilotDiagnosticOptions,
        runner: PilotRunner,
        scenario: CommodityExperimentScenario,
        selection: PilotDiagnosticSelection,
    ): PilotDiagnosticOutcome {
        val seat = scenario.state.playerIds.indexOf(scenario.actor)
        val strategies =
            listOf(CommodityExperimentStrategy.KEEP_BASELINE, selection.teacher, selection.student)
                .distinct()
        val rewards =
            List(options.samples) { sample ->
                val seed = diagnosticSeed(options.seed, selection.index, sample)
                strategies.associateWith { strategy ->
                    runner.continuation(scenario, seed, strategy).rewards[seat]
                }
            }
        fun values(strategy: CommodityExperimentStrategy): List<Double> = rewards.map { sample ->
            checkNotNull(sample[strategy]) { "Every selected diagnostic plan requires a reward." }
        }
        return PilotDiagnosticOutcome(
            selection,
            values(CommodityExperimentStrategy.KEEP_BASELINE),
            values(selection.teacher),
            values(selection.student),
        )
    }

    private fun verifiedScenario(
        input: PilotDiagnosticInput,
        selection: PilotDiagnosticSelection,
    ): CommodityExperimentScenario {
        val scenario = CommodityExperiment.scenario(selection.scenarioSeed)
        val current =
            CommodityExperiment.features(
                scenario.state.viewForDecision(scenario.actor),
                scenario.actor,
                scenario.commodity,
            )
        val original =
            input.rows
                .single { it.required("index").intValue() == selection.index }
                .required("features")
                .map { it.doubleValue() }
        require(
            current.size == original.size &&
                current.zip(original).all { (a, b) -> abs(a - b) < 1e-12 }
        ) {
            "Diagnostic root ${selection.index} no longer reproduces its original feature vector."
        }
        return scenario
    }

    private fun verifyOriginalReward(
        actual: Double,
        expected: Double,
        case: PilotReplayCase,
        strategy: CommodityExperimentStrategy,
    ) {
        require(abs(actual - expected) < 1e-12) {
            "Replay ${case.index}/$strategy changed reward from $expected to $actual; stop before comparing changed semantics."
        }
    }

    private fun cached(artifacts: TrainingArtifacts, name: String, compute: () -> Any): JsonNode {
        artifacts.read(name)?.let {
            return it
        }
        artifacts.write(name, compute())
        return checkNotNull(artifacts.read(name)) {
            "Completed diagnostic artifacts must be readable."
        }
    }

    private fun replayCases(
        input: PilotDiagnosticInput,
        selections: List<PilotDiagnosticSelection>,
    ): List<PilotReplayCase> {
        val candidates = selections.map { selection ->
            val original =
                checkNotNull(input.validation[selection.index]) {
                    "Every held-out position requires original validation."
                }
            require(original.required("strategy").asText() == selection.student.name) {
                "Frozen student choice changed at position ${selection.index}."
            }
            val differences =
                original.required("studentRewards").zip(original.required("baselineRewards")) {
                    student,
                    baseline ->
                    student.doubleValue() - baseline.doubleValue()
                }
            val mean = differences.average()
            val sample =
                if (mean < 0) {
                    differences.indices.minBy { differences[it] }
                } else {
                    differences.indices.maxBy { differences[it] }
                }
            PilotReplayCase(
                selection.index,
                sample,
                tournamentSeed(input.seed + 400_000_000, selection.index, sample, heldOut = true),
                mean,
            )
        }
        val losses =
            candidates
                .filter { it.originalMeanDifference < 0 }
                .sortedBy { it.originalMeanDifference }
                .take(3)
        val gain = candidates.maxByOrNull { it.originalMeanDifference }
        return (losses + listOfNotNull(gain?.takeIf { it.originalMeanDifference > 0 })).distinctBy {
            it.index
        }
    }
}

/** Sample selection is retrospective and explanatory; it does not enter aggregate fresh results. */
data class PilotReplayCase(
    val index: Int,
    val sample: Int,
    val seed: Int,
    val originalMeanDifference: Double,
)
