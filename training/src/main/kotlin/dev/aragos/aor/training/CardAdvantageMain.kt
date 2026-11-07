package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One bounded native job. The local supervisor owns shared manifests, clocks and completion hashes.
 */
internal object CardAdvantageMain {
    fun run(arguments: List<String>) {
        val options = CardAdvantageOptions.parse(arguments)
        if (options.mode == "card-advantage-collect") collect(options) else evaluate(options)
    }

    private fun collect(options: CardAdvantageOptions) {
        val input = CardAdvantageCollectionInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val index =
            requireNotNull(options.index) { "Collection requires an explicit origin index." }
        require(index in input.origins.indices) { "Origin index is outside its frozen source." }
        job(
            options,
            "origin",
            listOf(input.source, input.environment.baseline.path, input.environment.purchase.path) +
                listOfNotNull(input.continuation?.path),
        ) { path, deadline ->
            val document =
                CardTrajectoryFiles.readIfPresent(path)
                    ?: PurchaseJson.mapper.valueToTree<JsonNode>(
                        CardAdvantageCollector(input, deadline).collect(index)
                    )
            CardAdvantageArtifacts.validate(document, input, index)
            CardTrajectoryFiles.save(path, document)
        }
    }

    private fun evaluate(options: CardAdvantageOptions) {
        val input = CardAdvantageEvaluationInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val index = requireNotNull(options.index) { "Evaluation requires an explicit game index." }
        require(index in input.jobs.indices) { "Game index is outside its frozen source." }
        job(
            options,
            "game",
            listOf(input.source, input.environment.baseline.path, input.environment.purchase.path) +
                input.policies.values.mapNotNull { it.path },
        ) { path, deadline ->
            val evaluator = CardAdvantageEvaluator(input, deadline)
            val document =
                CardTrajectoryFiles.readIfPresent(path)
                    ?: PurchaseJson.mapper.valueToTree<JsonNode>(evaluator.evaluate(index))
            evaluator.validate(document, index)
            CardTrajectoryFiles.save(path, document)
        }
    }

    private fun job(
        options: CardAdvantageOptions,
        prefix: String,
        inputs: List<Path>,
        action: (Path, Long) -> Unit,
    ) {
        val output = requireNotNull(options.output) { "A native job requires an output directory." }
        Files.createDirectories(output)
        val realOutput = output.toRealPath()
        require(inputs.none { it.startsWith(realOutput) }) {
            "An advantage output may not contain any frozen input."
        }
        val index = requireNotNull(options.index) { "A native job requires its index." }
        FileChannel.open(
                realOutput.resolve(".$prefix-$index.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { channel ->
                val lock =
                    requireNotNull(channel.tryLock()) {
                        "Another native process already owns this exact job."
                    }
                lock.use {
                    val deadline = System.nanoTime() + (options.hours * 3_600_000_000_000L).toLong()
                    action(realOutput.resolve("$prefix-$index.json.gz"), deadline)
                }
            }
    }
}

internal data class CardAdvantageOptions(
    val mode: String,
    val source: Path,
    val describe: Boolean,
    val output: Path?,
    val index: Int?,
    val hours: Double,
) {
    companion object {
        fun parse(arguments: List<String>): CardAdvantageOptions {
            require(arguments.size % 2 == 0) {
                "Use explicit --option value pairs for advantage jobs."
            }
            val values = arguments.chunked(2).associate { it[0] to it[1] }
            require(
                values.size * 2 == arguments.size &&
                    values.keys.all {
                        it in
                            setOf(
                                "--mode",
                                "--source",
                                "--describe",
                                "--output",
                                "--index",
                                "--hours",
                            )
                    }
            ) {
                "Advantage options may not repeat or contain unknown fields."
            }
            val mode = requireNotNull(values["--mode"]) { "Advantage mode is required." }
            require(mode in setOf("card-advantage-collect", "card-advantage-evaluate")) {
                "Unknown advantage execution mode."
            }
            val source =
                Path.of(requireNotNull(values["--source"]) { "Advantage source is required." })
                    .toRealPath()
            val describe =
                values["--describe"]?.let {
                    require(it == "true") {
                        "Use --describe true only for immutable metadata projection."
                    }
                    true
                } ?: false
            require(!describe || values.keys == setOf("--mode", "--source", "--describe")) {
                "Metadata description cannot launch a game or write output."
            }
            val output = values["--output"]?.let { Path.of(it).toAbsolutePath().normalize() }
            val index = values["--index"]?.toInt()
            val hours = values["--hours"]?.toDouble() ?: 0.0
            require(
                describe ||
                    output != null &&
                        index != null &&
                        index >= 0 &&
                        hours.isFinite() &&
                        hours > 0 &&
                        hours <= 12
            ) {
                "A native advantage job needs output, nonnegative index and a finite positive allowance up to12hours."
            }
            return CardAdvantageOptions(mode, source, describe, output, index, hours)
        }
    }
}
