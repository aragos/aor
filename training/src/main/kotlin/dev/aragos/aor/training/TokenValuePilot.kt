package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotTokenValue

/** One origin remains the statistical unit after all paired continuation samples are averaged. */
data class TokenValueSelection(
    val index: Int,
    val gameSeed: Int,
    val baselineCommand: String,
    val selectedCommand: String,
    val predictedAdvantage: Double,
    val observedAdvantage: Double,
    val requestedEpoch: Int,
    val actualEpoch: Int,
    val bestPredictedAdvantage: Double,
) {
    val overridden: Boolean
        get() = baselineCommand != selectedCommand
}

data class TokenValueFit(
    val regularization: Double,
    val coefficients: List<Double>,
    val trainingMse: Double,
    val validationMse: Double,
    val validation: List<TokenValueSelection>,
)

/** Atomic origin checkpoints, bounded collection workers, and training-only ridge fitting. */
internal class TokenValuePilotRunner(
    private val specification: TokenValueSpecification,
    private val artifacts: TrainingArtifacts,
    private val workers: Tournaments,
    private val deadlineNanos: Long,
    metadata: Map<String, Any> = emptyMap(),
    private val collectOrigin: (Int) -> TokenValueOrigin =
        TokenValueCollector(specification, deadlineNanos)::collect,
) {
    private val metadata = metadata + ("learningMode" to specification.learningMode)

    fun run(): BotModel {
        artifacts.write("state.json", metadata + mapOf("phase" to "collecting"))
        var completed = 0
        val origins =
            workers
                .run(
                    List(specification.origins) { index ->
                        {
                            val name = "origin-$index.json"
                            val cached = artifacts.read(name)
                            val origin =
                                if (cached == null) {
                                    checkTokenValueDeadline(deadlineNanos)
                                    collectOrigin(index).also {
                                        validateOrigin(it, index)
                                        artifacts.write(name, it)
                                    }
                                } else TokenValueOrigin.read(cached)
                            validateOrigin(origin, index)
                            origin
                        }
                    }
                ) {
                    completed++
                    if (completed % 8 == 0 || completed == specification.origins) {
                        println("Token-value origins $completed/${specification.origins}")
                    }
                }
                .sortedBy(TokenValueOrigin::index)
        artifacts.write(
            "dataset.json",
            metadata +
                mapOf(
                    "originSchemaVersion" to TOKEN_VALUE_ORIGIN_SCHEMA_VERSION,
                    "selectionProtocol" to TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
                    "featureVersion" to BotTokenValue.FEATURE_VERSION,
                    "featureNames" to BotTokenValue.featureNames,
                    "rng" to
                        "ExperimentSession SplitMix64; seed arithmetic shared with arena, RNG differs",
                    "trainingSeedOffset" to specification.arena.seed,
                    "validationSeedOffset" to specification.arena.seed + 1,
                    "requestedOrigins" to specification.origins,
                    "counts" to
                        TokenValueSplit.entries.associate { split ->
                            split.name to counts(origins.filter { it.split == split })
                        },
                    "rootSelection" to
                        "Precommitted epoch bankIndex%3+1 and uniform ordinal 1..6 (epochs 1/2) or 1..4 (epoch 3); no fallback",
                    "selectionRng" to "Kotlin Random(gameSeed xor 0x2e59c17), before game creation",
                    "originDecisions" to
                        "Collected: prefix length before selected sealed batch; skipped: full-game length",
                    "continuationReplicates" to specification.continuationReplicates,
                    "meanDeltaZeroTolerance" to TOKEN_VALUE_MEAN_ZERO_TOLERANCE,
                    "targets" to
                        "Every action's mean paired terminal composite-reward difference; no teacher argmax labels",
                    "originFiles" to origins.map { "origin-${it.index}.json" },
                ),
        )
        val collected = origins.filter { it.status == TokenValueOriginStatus.COLLECTED }
        val training = collected.filter { it.split == TokenValueSplit.TRAINING }
        val validation = collected.filter { it.split == TokenValueSplit.VALIDATION }
        require(training.size >= 2 && validation.size >= 2) {
            "Token-value fitting needs at least two collected origins in each bank; inspect dataset.json skips."
        }
        artifacts.write("state.json", metadata + mapOf("phase" to "fitting"))
        val examples = training.flatMap { origin ->
            val alternatives = origin.alternatives.filter { it.command != origin.baselineCommand }
            alternatives.map { alternative ->
                TokenValueExample(
                    alternative.features,
                    origin.delta(alternative),
                    1.0 / alternatives.size,
                )
            }
        }
        val fits =
            specification.regularization.mapIndexed { index, regularization ->
                val coefficients = TokenValueRidge.fit(examples, regularization, deadlineNanos)
                val model =
                    specification.learningMode.withWeights(
                        specification.arena.candidate,
                        coefficients,
                    )
                artifacts.model(model, "ridge-$index.json")
                TokenValueFit(
                    regularization,
                    coefficients,
                    mse(training, coefficients),
                    mse(validation, coefficients),
                    evaluate(validation, coefficients),
                )
            }
        val selected =
            fits.minWith(
                compareBy<TokenValueFit> { it.validationMse }.thenByDescending { it.regularization }
            )
        val model =
            specification.learningMode.withWeights(
                specification.arena.candidate,
                selected.coefficients,
            )
        artifacts.model(model, "candidate.json")
        val advantages = selected.validation.map(TokenValueSelection::observedAdvantage)
        val positives = advantages.filter { it > 0.0 }
        val positiveSum = positives.sum()
        val largestFraction = if (positiveSum == 0.0) null else positives.max() / positiveSum
        val overridden = selected.validation.count(TokenValueSelection::overridden)
        artifacts.write(
            "fit.json",
            metadata +
                mapOf(
                    "complete" to true,
                    "selectionOnly" to true,
                    "promoted" to false,
                    "humanStrengthVerified" to false,
                    "selectionRule" to
                        "Lowest validation origin-weighted MSE; ties prefer stronger regularization",
                    "minimumPredictedAdvantage" to BotTokenValue.MINIMUM_ADVANTAGE,
                    "trainingOrigins" to training.size,
                    "validationOrigins" to validation.size,
                    "trainingExamples" to examples.size,
                    "fits" to fits,
                    "selectedRegularization" to selected.regularization,
                    "labelDiagnostics" to
                        TokenValueSplit.entries.associate { split ->
                            split.name to labelDiagnostics(collected.filter { it.split == split })
                        },
                    "selectedPredictionDistribution" to predictionDistribution(selected.validation),
                    "validationRewardDifference" to pilotInterval(advantages),
                    "positiveSelectedOrigins" to positives.size,
                    "overriddenOrigins" to overridden,
                    "largestPositiveOriginFraction" to largestFraction,
                    "scalingDiagnosticPassed" to
                        (advantages.average() > 0.0 &&
                            positives.size >= 4 &&
                            overridden >= 8 &&
                            largestFraction != null &&
                            largestFraction <= 0.5),
                    "scaling" to
                        "Diagnostic only; more data and final independent arena require a separate decision",
                    "confirmation" to
                        "Not run; all validation estimates used for model selection are optimistic",
                ),
        )
        return model
    }

    private fun validateOrigin(origin: TokenValueOrigin, index: Int) {
        require(origin.learningMode == specification.learningMode) {
            "A token-value checkpoint must match its collection learning mode."
        }
        val expected = specification.lineup(index)
        val request = specification.rootRequest(index)
        require(
            origin.index == index &&
                origin.split == specification.split(index) &&
                origin.gameSeed == expected.seed &&
                origin.configuration == expected.configuration.id &&
                origin.seat == expected.seat &&
                origin.opponentIndices == expected.opponentIndices &&
                origin.requestedEpoch == request.epoch
        ) {
            "A token-value checkpoint must match its immutable origin bank and lineup."
        }
        require(
            origin.schemaVersion == TOKEN_VALUE_ORIGIN_SCHEMA_VERSION &&
                origin.selectionProtocol == TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1 &&
                origin.requestedOrdinal == request.ordinal
        ) {
            "A token-value checkpoint must match its precommitted root-selection protocol and ordinal."
        }
        require(
            origin.alternatives.all { it.outcomes.size == specification.continuationReplicates }
        ) {
            "A token-value checkpoint must preserve its paired continuation count."
        }
        require(
            origin.alternatives.all { alternative ->
                alternative.outcomes.withIndex().all { (replicate, outcome) ->
                    outcome.continuationSeed ==
                        TokenValueCollector.continuationSeed(expected.seed, replicate)
                }
            }
        ) {
            "A token-value checkpoint must preserve its origin's exact continuation seeds."
        }
    }

    private fun counts(origins: List<TokenValueOrigin>): Map<String, Int> =
        mapOf(
            "requested" to origins.size,
            "collected" to origins.count { it.status == TokenValueOriginStatus.COLLECTED },
            "skipped" to origins.count { it.status == TokenValueOriginStatus.SKIPPED },
            "requestedRootNotReached" to
                origins.count { it.skipReason == TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED },
            "precommittedRootIneligible" to
                origins.count {
                    it.skipReason == TokenValueSkipReason.PRECOMMITTED_ROOT_INELIGIBLE
                },
        )

    companion object {
        private fun labelDiagnostics(origins: List<TokenValueOrigin>): Map<String, Any> {
            val alternatives = origins.flatMap { origin ->
                origin.alternatives
                    .filter { it.command != origin.baselineCommand }
                    .map { origin to it }
            }
            val zeros = alternatives.count { (origin, option) -> origin.delta(option) == 0.0 }
            val allZero = origins.count { origin ->
                origin.alternatives.all { origin.delta(it) == 0.0 }
            }
            val scoreOnly = alternatives.count { (origin, option) ->
                origin.delta(option) == 0.0 &&
                    option.outcomes.zip(origin.baseline().outcomes).any { (after, before) ->
                        after.scores != before.scores
                    }
            }
            return mapOf(
                "origins" to origins.size,
                "nonbaselineAlternatives" to alternatives.size,
                "zeroRewardAlternatives" to zeros,
                "zeroRewardAlternativeFraction" to zeros.toDouble() / alternatives.size,
                "allZeroRewardOrigins" to allZero,
                "allZeroRewardOriginFraction" to allZero.toDouble() / origins.size,
                "scoreOnlyAlternatives" to scoreOnly,
            )
        }

        private fun predictionDistribution(
            selections: List<TokenValueSelection>
        ): Map<String, Any> {
            val values = selections.map(TokenValueSelection::bestPredictedAdvantage).sorted()
            return mapOf(
                "minimum" to values.first(),
                "median" to values[values.size / 2],
                "mean" to values.average(),
                "maximum" to values.last(),
                "aboveMargin" to values.count { it > BotTokenValue.MINIMUM_ADVANTAGE },
            )
        }

        internal fun mse(origins: List<TokenValueOrigin>, coefficients: List<Double>): Double =
            origins
                .map { origin ->
                    origin.alternatives
                        .filter { it.command != origin.baselineCommand }
                        .map { alternative ->
                            val error =
                                TokenValueRidge.predict(coefficients, alternative.features) -
                                    origin.delta(alternative)
                            error * error
                        }
                        .average()
                }
                .average()

        internal fun evaluate(
            origins: List<TokenValueOrigin>,
            coefficients: List<Double>,
        ): List<TokenValueSelection> = origins.map { origin ->
            val best =
                origin.alternatives.maxBy { TokenValueRidge.predict(coefficients, it.features) }
            val prediction = TokenValueRidge.predict(coefficients, best.features)
            val selected =
                if (prediction > BotTokenValue.MINIMUM_ADVANTAGE) best else origin.baseline()
            TokenValueSelection(
                origin.index,
                origin.gameSeed,
                checkNotNull(origin.baselineCommand) {
                    "Evaluation requires a collected baseline."
                },
                selected.command,
                TokenValueRidge.predict(coefficients, selected.features),
                origin.delta(selected),
                origin.requestedEpoch,
                checkNotNull(origin.actualEpoch) { "Evaluation requires a collected epoch." },
                prediction,
            )
        }
    }
}
