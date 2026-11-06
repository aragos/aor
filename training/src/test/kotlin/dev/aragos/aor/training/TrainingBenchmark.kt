package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.management.ThreadMXBean
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Fixed development games measure throughput and allocation, never playing strength. */
object TrainingBenchmark {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size in 1..2) { "Use OUTPUT_JSON [BASELINE_JSON]." }
        val output = Path.of(arguments[0]).toAbsolutePath()
        require(!Files.exists(output)) { "Use a new benchmark output file: $output." }
        val json = ObjectMapper()
        val previous =
            arguments.getOrNull(1)?.let { baseline ->
                json.readTree(Path.of(baseline).toFile()).also { report ->
                    require(report["runs"]?.size() == 40) {
                        "The baseline must contain the same fixed game matrix."
                    }
                }
            }
        val clock =
            checkNotNull(ManagementFactory.getThreadMXBean() as? ThreadMXBean) {
                "The benchmark requires an OpenJDK thread allocation counter."
            }
        check(clock.isCurrentThreadCpuTimeSupported && clock.isThreadAllocatedMemorySupported) {
            "The benchmark requires thread CPU and allocation counters."
        }
        clock.isThreadCpuTimeEnabled = true
        clock.isThreadAllocatedMemoryEnabled = true
        val runs = buildList {
            // Replay-verified warmup, ordinary warmup, then three measured repetitions.
            for (iteration in -2..2) {
                TrainingMatrix.stratified.take(8).forEachIndexed { index, configuration ->
                    val difficulty =
                        if (index % 2 == 0) BotDifficulty.NORMAL else BotDifficulty.HARD
                    val competitors =
                        List(configuration.playerCount) {
                            Competitor(BotModels.default, difficulty)
                        }
                    val cpu = clock.currentThreadCpuTime
                    val allocated = clock.getThreadAllocatedBytes(Thread.currentThread().threadId())
                    val started = System.nanoTime()
                    val game =
                        Simulation()
                            .play(
                                configuration = configuration,
                                seed = 19790531 + index * 8191,
                                competitors = competitors,
                                verifyReplay = iteration == -2,
                            )
                    val wallNanos = System.nanoTime() - started
                    val cpuNanos = clock.currentThreadCpuTime - cpu
                    val allocatedBytes =
                        clock.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated
                    add(
                        mapOf(
                            "iteration" to iteration,
                            "case" to index,
                            "difficulty" to difficulty,
                            "wallNanos" to wallNanos,
                            "cpuNanos" to cpuNanos,
                            "allocatedBytes" to allocatedBytes,
                            "behavior" to game.copy(elapsedMillis = 0),
                        )
                    )
                    println("iteration=$iteration case=$index steps=${game.steps} ns=$wallNanos")
                }
            }
        }
        // Parse both reports through the same wire representation: Jackson's in-memory tree
        // distinguishes Long(0) from Int(0), while JSON stores both as the number 0.
        val tree = json.readTree(json.writeValueAsBytes(runs))
        for (index in 0 until 8) {
            val behaviors = tree.filter { it["case"].intValue() == index }.map { it["behavior"] }
            check(behaviors.distinct().size == 1) {
                "Repeated game $index changed behavior, including its replay-verified warmup."
            }
        }
        previous?.let { baseline ->
            for (index in 0 until tree.size()) {
                check(baseline["runs"][index]["behavior"] == tree[index]["behavior"]) {
                    "Game behavior differs from the baseline at run $index."
                }
            }
        }
        val probes = benchmarkProbes(clock)
        previous?.let { baseline ->
            baseline["probes"]?.let { prior ->
                check(
                    prior["regrets"] == json.readTree(json.writeValueAsBytes(probes["regrets"]))
                ) {
                    "Directed regret differs from the baseline."
                }
            }
        }
        val measured = tree.filter { it["iteration"].intValue() >= 0 }
        val report =
            mapOf(
                "javaVersion" to System.getProperty("java.version"),
                "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
                "measuredGames" to measured.size,
                "wallNanos" to measured.sumOf { it["wallNanos"].longValue() },
                "cpuNanos" to measured.sumOf { it["cpuNanos"].longValue() },
                "allocatedBytes" to measured.sumOf { it["allocatedBytes"].longValue() },
                "runs" to runs,
                "probes" to probes,
            )
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
            StandardOpenOption.CREATE_NEW,
        )
        println("Measured ${measured.size} games and directed regret; report: $output")
    }

    private fun benchmarkProbes(clock: ThreadMXBean): Map<String, Any> {
        val prior = BotModels.default
        val models =
            listOf(
                prior,
                prior.copy(
                    weights =
                        prior.weights +
                            mapOf("cash" to 1.15, "points" to 0.9, "reserveShortfall" to -4.0)
                ),
                prior.copy(
                    weights =
                        prior.weights + mapOf("cash" to 0.8, "income" to 4.0, "commodity" to 2.1)
                ),
            )
        val initializationStarted = System.nanoTime()
        val probes = DecisionProbes(TrainingMatrix.stratified, CoverageLedger())
        val initializationWallNanos = System.nanoTime() - initializationStarted
        repeat(5) { models.forEach { model -> probes.regret(model) } }
        val cpu = clock.currentThreadCpuTime
        val allocated = clock.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val started = System.nanoTime()
        val regrets = List(20) { models.map(probes::regret) }
        val wallNanos = System.nanoTime() - started
        val cpuNanos = clock.currentThreadCpuTime - cpu
        val allocatedBytes =
            clock.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated
        check(regrets.distinct().size == 1) { "Repeated directed regret evaluations must agree." }
        return mapOf(
            "observations" to probes.observations.size,
            "evaluations" to regrets.size * models.size,
            "modelWeights" to models.map(BotModel::weights),
            "regrets" to regrets.first(),
            "initializationWallNanos" to initializationWallNanos,
            "wallNanos" to wallNanos,
            "cpuNanos" to cpuNanos,
            "allocatedBytes" to allocatedBytes,
        )
    }
}
