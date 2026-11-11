package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal data class ExpansionImitationMetrics(
    val count: Int,
    val correct: Int,
    val changedFromBaseline: Int,
    val linearLoss: Double,
    val commands: List<String>,
)

internal data class ExpansionImitationCandidate(
    val regularization: Double,
    val modelFile: String,
    val modelSha256: String,
    val fit: ExpansionImitationFit,
    val training: ExpansionImitationMetrics,
    val validation: ExpansionImitationMetrics,
)

/**
 * One fixed-data, fixed-hyperparameter fit; creates rivals for screening, never activates a policy.
 */
object ExpansionImitationMain {
    fun run(arguments: List<String>) {
        require(
            arguments.size == 6 &&
                arguments.chunked(2).map { it[0] }.toSet() ==
                    setOf("--mode", "--source", "--output")
        ) {
            "Imitation fitting requires distinct --mode, --source and --output options."
        }
        val options = arguments.chunked(2).associate { it[0] to it[1] }
        require(options["--mode"] == "expansion-imitation-fit") {
            "Unsupported imitation fitting mode."
        }
        val source =
            Path.of(requireNotNull(options["--source"]) { "Imitation requires a source." })
                .toRealPath()
        val output =
            Path.of(requireNotNull(options["--output"]) { "Imitation requires an output." })
                .toAbsolutePath()
                .normalize()
        require(!Files.exists(output)) {
            "Imitation permits one new output only; no adaptive retries or resume."
        }
        val input = ExpansionImitationInput.read(source)
        require(
            listOf(source, input.observations, input.baselinePath).none { it.startsWith(output) }
        ) {
            "Imitation output must not contain any frozen input."
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + 600_000_000_000
        val artifacts = TrainingArtifacts(output)
        artifacts.ensureFileSnapshot(source, input.sourceSha256, "source.json")
        artifacts.ensureFileSnapshot(
            input.observations,
            input.observationsSha256,
            "human-observations.jsonl",
        )
        artifacts.ensureFileSnapshot(input.baselinePath, input.baselineSha256, "baseline.json")
        val trainRows = input.expansionRows.take(ExpansionImitationProtocol.TRAIN)
        val validationRows =
            input.expansionRows
                .drop(ExpansionImitationProtocol.TRAIN)
                .take(ExpansionImitationProtocol.VALIDATION)
        val auditRows = input.expansionRows.takeLast(ExpansionImitationProtocol.AUDIT)
        val training = trainRows.map(input::example)
        val validation = validationRows.map(input::example)
        val scale = ExpansionImitationScale.from(training, input.baseline)
        val trainContrasts = ExpansionImitationOptimizer.contrasts(training, input.baseline, scale)
        val baselineValidation =
            metrics(validation, input.baseline, input.baseline, scale, List(8) { 0.0 })
        val identity =
            mapOf(
                "kind" to ExpansionImitationProtocol.VERSION,
                "signature" to
                    trainingSignature(
                        "${ExpansionImitationProtocol.VERSION}:${input.sourceSha256}"
                    ),
                "sourceSha256" to input.sourceSha256,
                "observationsSha256" to input.observationsSha256,
                "baselineSha256" to input.baselineSha256,
                "gameId" to input.gameId,
                "gameSourceSha256" to input.gameSourceSha256,
            )
        val protocol =
            mapOf(
                "featureNames" to ExpansionImitationProtocol.features,
                "regularizations" to ExpansionImitationProtocol.regularizations,
                "trainEntries" to trainRows.map { it["entry"].intValue() },
                "validationEntries" to validationRows.map { it["entry"].intValue() },
                "auditEntries" to auditRows.map { it["entry"].intValue() },
                "proposalSeed" to null,
                "diagnosticPolicySeed" to ExpansionImitationProtocol.POLICY_SEED,
                "normalization" to scale,
                "maximumIterations" to ExpansionImitationProtocol.MAX_ITERATIONS,
                "maximumSeconds" to 600,
                "routing" to
                    "expand only; all other decisions retain their actual ordinary model and shared Purchase overlay",
                "selectionRule" to
                    "HARD validation correct descending, linear loss ascending, regularization descending; require at least one extra correct and two changed commands; at most two distinct command vectors",
                "screeningRule" to
                    "All three fixed candidates share 128 fresh paired worlds; frozen eligible rivals reject if mean reward difference < -0.05 or mean win difference < -0.10; descriptive only",
                "auditRole" to
                    "Descriptive chronological ten-row audit after selection is frozen; cannot change selection",
                "qualificationPassed" to false,
                "productionActivation" to false,
                "humanStrengthVerified" to false,
            )
        artifacts.write(
            "run.json",
            identity +
                protocol +
                input.transfer?.metadata().orEmpty() +
                mapOf("startedAt" to started.toString(), "phase" to "fitting"),
        )
        val candidates =
            ExpansionImitationProtocol.regularizations.map { regularization ->
                val fit = ExpansionImitationOptimizer.fit(trainContrasts, regularization, deadline)
                val model = ExpansionImitationOptimizer.model(input.baseline, scale, fit)
                check(ExpansionImitationProtocol.preservesOtherWeights(input.baseline, model)) {
                    "Imitation changed an undeclared coefficient."
                }
                val name = "lambda-$regularization.json"
                artifacts.model(model, name)
                ExpansionImitationCandidate(
                    regularization,
                    name,
                    PurchaseJson.hash(output.resolve(name)),
                    fit,
                    metrics(training, input.baseline, model, scale, fit.coefficients),
                    metrics(validation, input.baseline, model, scale, fit.coefficients),
                )
            }
        val selected = select(candidates, baselineValidation)
        artifacts.write(
            "selection.json",
            identity +
                mapOf(
                    "frozenAt" to Instant.now().toString(),
                    "baselineValidation" to baselineValidation,
                    "candidates" to candidates,
                    "eligibleRivalModelFiles" to selected,
                    "auditConsulted" to false,
                ),
        )
        val selectionHash = PurchaseJson.hash(output.resolve("selection.json"))
        // The audit labels are first decoded and evaluated only after immutable model/selection
        // receipts exist.
        val audit = auditRows.map(input::example)
        val auditMetrics = candidates.associate { candidate ->
            checkTokenValueDeadline(deadline)
            candidate.modelFile to
                metrics(
                    audit,
                    input.baseline,
                    TrainingArtifacts.readModel(output.resolve(candidate.modelFile)),
                    scale,
                    candidate.fit.coefficients,
                )
        }
        artifacts.write(
            "audit.json",
            identity +
                mapOf(
                    "selectionSha256" to selectionHash,
                    "descriptiveOnly" to true,
                    "baseline" to
                        metrics(audit, input.baseline, input.baseline, scale, List(8) { 0.0 }),
                    "candidates" to auditMetrics,
                ),
        )
        check(PurchaseJson.hash(output.resolve("selection.json")) == selectionHash) {
            "The audit cannot revise frozen rival selection."
        }
        artifacts.write(
            "finished.json",
            identity +
                mapOf(
                    "phase" to "complete",
                    "selectionSha256" to selectionHash,
                    "auditSha256" to PurchaseJson.hash(output.resolve("audit.json")),
                    "models" to candidates.associate { it.modelFile to it.modelSha256 },
                    "at" to Instant.now().toString(),
                    "qualificationPassed" to false,
                    "productionActivation" to false,
                ),
        )
        println(
            "Three fixed imitation fits complete; ${selected.size} validation-eligible training rivals await whole-game screening."
        )
    }

    internal fun select(
        candidates: List<ExpansionImitationCandidate>,
        baseline: ExpansionImitationMetrics,
    ): List<String> =
        candidates
            .filter {
                it.validation.correct > baseline.correct && it.validation.changedFromBaseline >= 2
            }
            .sortedWith(
                compareByDescending<ExpansionImitationCandidate> { it.validation.correct }
                    .thenBy { it.validation.linearLoss }
                    .thenByDescending { it.regularization }
            )
            .distinctBy { it.validation.commands }
            .take(2)
            .map { it.modelFile }

    private fun metrics(
        examples: List<ExpansionImitationExample>,
        baseline: BotModel,
        model: BotModel,
        scale: ExpansionImitationScale,
        coefficients: List<Double>,
    ): ExpansionImitationMetrics {
        val commands = ExpansionImitationOptimizer.commands(examples, model)
        val baselineCommands = ExpansionImitationOptimizer.commands(examples, baseline)
        return ExpansionImitationMetrics(
            examples.size,
            commands.zip(examples).count { (command, example) ->
                command == example.selectedCommand
            },
            commands.zip(baselineCommands).count { (actual, expected) -> actual != expected },
            ExpansionImitationOptimizer.objective(
                    ExpansionImitationOptimizer.contrasts(examples, baseline, scale),
                    coefficients,
                    0.0,
                )
                .loss,
            commands,
        )
    }
}
