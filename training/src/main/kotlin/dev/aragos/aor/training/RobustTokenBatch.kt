package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Reuse JVM code and static game data while preserving each shard's native evidence contract. */
internal object RobustTokenBatch {
    const val MODE = "robust-token-batch"
    private const val VERSION = "robust-token-batch-1"

    private data class Job(val source: Path, val output: Path)

    fun run(planPath: Path, seconds: Long, workers: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        val normalized = planPath.toAbsolutePath().normalize()
        val plan = PurchaseJson.read(normalized)
        PurchaseJson.fields(plan, setOf("kind", "jobs"))
        require(plan["kind"]?.asText() == VERSION) { "Declare the exact robust batch version." }
        val jobs =
            PurchaseJson.array(plan["jobs"], "robust batch jobs").map { node ->
                PurchaseJson.fields(node, setOf("source", "output"))
                Job(
                    path(normalized.parent, node["source"]),
                    path(normalized.parent, node["output"]),
                )
            }
        require(jobs.size in 1..64) { "A robust batch must contain one through 64 shards." }
        val outputs = jobs.map(Job::output)
        require(
            outputs.distinct().size == outputs.size &&
                outputs.none { output ->
                    outputs.any { other -> other != output && other.startsWith(output) }
                }
        ) {
            "Robust batch output directories must be distinct and cannot contain each other."
        }
        val inputs = jobs.map(Job::source) + listOf(normalized)
        require(
            inputs.all(Files::isRegularFile) &&
                inputs.none { source -> outputs.any(source::startsWith) }
        ) {
            "A robust batch requires existing sources outside its output directories."
        }
        require(outputs.none(Files::exists)) { "Robust batch outputs must be new directories." }
        for (job in jobs) {
            val remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())
            check(remaining > 0) { "The robust batch deadline elapsed before the next shard." }
            RobustTokenMain.run(
                listOf(
                    "--mode",
                    "robust-token-screen-chunk",
                    "--source",
                    job.source.toString(),
                    "--output",
                    job.output.toString(),
                    "--seconds",
                    remaining.toString(),
                    "--workers",
                    workers.toString(),
                )
            )
        }
    }

    private fun path(folder: Path, node: JsonNode): Path {
        require(node.isTextual && node.asText().isNotBlank()) { "Every batch path must be text." }
        return folder.resolve(node.asText()).normalize()
    }
}
