package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException

/**
 * Bounded native jobs. Optional batches share parsed models and one deadline, while each game
 * retains its own immutable record. The supervisor owns whole-stage completion hashes.
 */
internal object CardAdvantageMain {
    fun run(arguments: List<String>) {
        val options = CardAdvantageOptions.parse(arguments)
        when (options.mode) {
            "card-advantage-collect" -> collect(options)
            "card-policy-coupling" -> collectCoupling(options)
            else -> evaluate(options)
        }
    }

    private fun collect(options: CardAdvantageOptions) {
        val batchDeadline = options.indices?.let { deadline(options.hours) }
        val sourceVersion =
            PurchaseJson.integer(
                PurchaseJson.read(options.source).required("schemaVersion"),
                "schemaVersion",
            )
        if (sourceVersion == 3) {
            collectDiagnostic(options, batchDeadline)
            return
        }
        val input = CardAdvantageCollectionInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val indices =
            options.indices
                ?: listOf(
                    requireNotNull(options.index) {
                        "Collection requires an explicit origin index."
                    }
                )
        require(indices.all { it in input.origins.indices }) {
            "Every origin index must belong to its frozen source before any batch work starts."
        }
        val inputs =
            listOf(input.source, input.environment.baseline.path, input.environment.purchase.path) +
                listOfNotNull(input.continuation?.path)
        for (index in indices) {
            if (batchDeadline != null) checkDeadline(batchDeadline)
            job(options, index, "origin", inputs, batchDeadline) { path, deadline ->
                if (batchDeadline != null) checkDeadline(batchDeadline)
                val document =
                    CardTrajectoryFiles.readIfPresent(path)
                        ?: PurchaseJson.mapper.valueToTree<JsonNode>(
                            CardAdvantageCollector(input, deadline).collect(index)
                        )
                CardAdvantageArtifacts.validate(document, input, index)
                CardTrajectoryFiles.save(path, document)
            }
        }
    }

    private fun collectDiagnostic(options: CardAdvantageOptions, batchDeadline: Long?) {
        val input = CardContinuationDiagnosticInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val indices =
            options.indices
                ?: listOf(
                    requireNotNull(options.index) {
                        "A diagnostic requires an explicit origin index."
                    }
                )
        require(indices.all { it in input.origins.indices }) {
            "Every diagnostic index must belong to its source before any batch work starts."
        }
        val inputs =
            listOf(
                input.source,
                input.environment.baseline.path,
                input.environment.purchase.path,
                input.priority.path,
            )
        for (index in indices) {
            if (batchDeadline != null) checkDeadline(batchDeadline)
            job(options, index, "origin", inputs, batchDeadline) { path, deadline ->
                if (batchDeadline != null) checkDeadline(batchDeadline)
                val document =
                    CardTrajectoryFiles.readIfPresent(path)
                        ?: PurchaseJson.mapper.valueToTree<JsonNode>(
                            CardContinuationCollector(input, deadline).collect(index)
                        )
                CardContinuationArtifacts.validate(document, input, index)
                CardTrajectoryFiles.save(path, document)
            }
        }
    }

    private fun collectCoupling(options: CardAdvantageOptions) {
        val batchDeadline = options.indices?.let { deadline(options.hours) }
        val input = CardPolicyCouplingInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val indices =
            options.indices
                ?: listOf(
                    requireNotNull(options.index) {
                        "A coupling diagnostic requires an explicit origin index."
                    }
                )
        require(indices.all { it in input.origins.indices }) {
            "Every coupling index must belong to its frozen source before any work starts."
        }
        val inputs =
            listOf(input.source, input.environment.baseline.path, input.environment.purchase.path)
        for (index in indices) {
            if (batchDeadline != null) checkDeadline(batchDeadline)
            job(options, index, "origin", inputs, batchDeadline) { path, deadline ->
                if (batchDeadline != null) checkDeadline(batchDeadline)
                val document =
                    CardTrajectoryFiles.readIfPresent(path)
                        ?: PurchaseJson.mapper.valueToTree<JsonNode>(
                            CardPolicyCouplingCollector(input, deadline).collect(index)
                        )
                CardPolicyCouplingArtifacts.validate(document, input, index)
                CardTrajectoryFiles.save(path, document)
            }
        }
    }

    private fun evaluate(options: CardAdvantageOptions) {
        // A batch's allowance includes input loading and cached-record validation, not just games.
        val batchDeadline = options.indices?.let { deadline(options.hours) }
        val input = CardAdvantageEvaluationInput.read(options.source)
        if (options.describe) {
            println(PurchaseJson.mapper.writeValueAsString(input.manifest()))
            return
        }
        val indices =
            options.indices
                ?: listOf(
                    requireNotNull(options.index) { "Evaluation requires an explicit game index." }
                )
        require(indices.all { it in input.jobs.indices }) {
            "Every game index must belong to its frozen source before any batch work starts."
        }
        val batchEvaluator = batchDeadline?.let { CardAdvantageEvaluator(input, it) }
        val inputs =
            listOf(input.source, input.environment.baseline.path, input.environment.purchase.path) +
                input.policies.values.flatMap {
                    it.territoryBundle?.files() ?: it.jointBundle?.files() ?: listOfNotNull(it.path)
                }
        for (index in indices) {
            if (batchDeadline != null) checkDeadline(batchDeadline)
            job(options, index, "game", inputs, batchDeadline) { path, deadline ->
                if (batchDeadline != null) checkDeadline(batchDeadline)
                val evaluator = batchEvaluator ?: CardAdvantageEvaluator(input, deadline)
                val document =
                    CardTrajectoryFiles.readIfPresent(path)
                        ?: PurchaseJson.mapper.valueToTree<JsonNode>(evaluator.evaluate(index))
                evaluator.validate(document, index)
                CardTrajectoryFiles.save(path, document)
            }
        }
    }

    private fun job(
        options: CardAdvantageOptions,
        index: Int,
        prefix: String,
        inputs: List<Path>,
        deadlineNanos: Long? = null,
        action: (Path, Long) -> Unit,
    ) {
        val output = requireNotNull(options.output) { "A native job requires an output directory." }
        Files.createDirectories(output)
        val realOutput = output.toRealPath()
        require(inputs.none { it.startsWith(realOutput) }) {
            "An advantage output may not contain any frozen input."
        }
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
                    action(
                        realOutput.resolve("$prefix-$index.json.gz"),
                        deadlineNanos ?: deadline(options.hours),
                    )
                }
            }
    }

    private fun deadline(hours: Double): Long =
        System.nanoTime() + (hours * 3_600_000_000_000L).toLong()

    private fun checkDeadline(deadlineNanos: Long) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
            throw CancellationException("The native batch exhausted its original allowance.")
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
    /** Explicit source indices, never seeds; unique and limited to 64 complete native jobs. */
    val indices: List<Int>? = null,
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
                                "--indices",
                                "--hours",
                            )
                    }
            ) {
                "Advantage options may not repeat or contain unknown fields."
            }
            val mode = requireNotNull(values["--mode"]) { "Advantage mode is required." }
            require(
                mode in
                    setOf(
                        "card-advantage-collect",
                        "card-advantage-evaluate",
                        "card-policy-coupling",
                    )
            ) {
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
            val indices =
                values["--indices"]?.let { text ->
                    val parts = text.split(',')
                    require(parts.size in 1..64 && parts.all { it.matches(Regex("[0-9]+")) }) {
                        "Native batches require one through 64 comma-separated nonnegative indices."
                    }
                    val parsed = parts.map { part ->
                        requireNotNull(part.toIntOrNull()) {
                            "A game index exceeds the integer range."
                        }
                    }
                    require(parsed.distinct().size == parsed.size) {
                        "Evaluation batch indices may not repeat."
                    }
                    parsed
                }
            require(indices == null || index == null) {
                "Use either --indices or --index, never both."
            }
            val hours = values["--hours"]?.toDouble() ?: 0.0
            require(
                describe ||
                    output != null &&
                        ((index != null && index >= 0) || indices != null) &&
                        hours.isFinite() &&
                        hours > 0 &&
                        hours <= 12
            ) {
                "A native advantage job needs output, nonnegative index and a finite positive allowance up to12hours."
            }
            return CardAdvantageOptions(mode, source, describe, output, index, hours, indices)
        }
    }
}
