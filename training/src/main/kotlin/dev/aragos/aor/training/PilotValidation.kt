package dev.aragos.aor.training

/**
 * Fresh paired continuations for one unseen scenario and the student's already selected plan.
 * Rewards use terminal win-share/rank utility in [0, 1]. A sample index pairs the two policies'
 * initial conditions; samples are averaged within [scenarioSeed] before estimating uncertainty.
 */
data class PilotValidationOutcome(
    val scenarioSeed: Int,
    val tag: String,
    val baselineRewards: List<Double>,
    val studentRewards: List<Double>,
    val strategy: String,
) {
    init {
        require(tag.isNotBlank()) { "A validation scenario requires a nonblank family tag." }
        require(baselineRewards.isNotEmpty() && baselineRewards.size == studentRewards.size) {
            "Validation requires equal nonempty paired baseline and student reward samples."
        }
        require(
            baselineRewards.all { it.isFinite() && it in 0.0..1.0 } &&
                studentRewards.all { it.isFinite() && it in 0.0..1.0 }
        ) {
            "Validation rewards must be finite and between zero and one."
        }
        require(CommodityExperimentStrategy.entries.any { it.name == strategy }) {
            "Validation strategy must identify a supported commodity plan."
        }
    }
}

/** Descriptive results for one scenario family; family intervals are not additional gates. */
data class PilotValidationGroup(
    val scenarios: Int,
    val rewardDifference: PilotInterval,
    val fallbackFraction: Double,
)

/**
 * Qualification for spending on the full-game tournament, not model promotion. The reward interval
 * is a normal approximation across scenario means, so more continuations do not inflate its count.
 */
data class PilotValidationReport(
    val passed: Boolean,
    val reason: String,
    val scenarios: Int,
    val minimumSamplesPerScenario: Int,
    val maximumSamplesPerScenario: Int,
    val rewardDifference: PilotInterval,
    val fallbackFraction: Double,
    val byTag: Map<String, PilotValidationGroup>,
)

/** Predeclared screening criterion for the fixed student on all held-out curriculum scenarios. */
object PilotValidation {
    const val MINIMUM_SCENARIOS = 32
    const val MINIMUM_SAMPLES = 2

    /**
     * Call with every scenario in the predeclared validation split, using continuation samples not
     * used for fitting or selecting the student's plan. Outcomes may not be filtered by
     * performance.
     */
    fun report(outcomes: List<PilotValidationOutcome>): PilotValidationReport {
        require(outcomes.isNotEmpty()) { "Validation requires at least one scenario outcome." }
        require(outcomes.map { it.scenarioSeed }.distinct().size == outcomes.size) {
            "Validation scenario seeds must be unique; repeated samples belong to one outcome."
        }
        val overall = summarize(outcomes)
        val minimumSamples = outcomes.minOf { it.baselineRewards.size }
        val sufficientScenarios = outcomes.size >= MINIMUM_SCENARIOS
        val sufficientSamples = minimumSamples >= MINIMUM_SAMPLES
        val positiveLowerBound =
            (overall.rewardDifference.lower95 ?: Double.NEGATIVE_INFINITY) > 0.0
        val passed = sufficientScenarios && sufficientSamples && positiveLowerBound
        val reason =
            when {
                !sufficientScenarios ->
                    "At least $MINIMUM_SCENARIOS unseen scenarios are required before a tournament."
                !sufficientSamples ->
                    "Every validation scenario requires at least $MINIMUM_SAMPLES fresh paired continuations."
                !positiveLowerBound ->
                    "Student improvement lacks a positive paired 95% lower bound on unseen scenarios."
                else ->
                    "Student improvement has a positive paired 95% lower bound on unseen scenarios."
            }
        return PilotValidationReport(
            passed = passed,
            reason = reason,
            scenarios = outcomes.size,
            minimumSamplesPerScenario = minimumSamples,
            maximumSamplesPerScenario = outcomes.maxOf { it.baselineRewards.size },
            rewardDifference = overall.rewardDifference,
            fallbackFraction = overall.fallbackFraction,
            byTag =
                outcomes
                    .groupBy { it.tag }
                    .toSortedMap()
                    .mapValues { (_, group) -> summarize(group) },
        )
    }

    private fun summarize(outcomes: List<PilotValidationOutcome>): PilotValidationGroup {
        val differences = outcomes.map { outcome ->
            outcome.studentRewards
                .zip(outcome.baselineRewards) { student, baseline ->
                    student - baseline
                }
                .average()
        }
        return PilotValidationGroup(
            scenarios = outcomes.size,
            rewardDifference = pilotInterval(differences),
            fallbackFraction =
                outcomes
                    .count { it.strategy == CommodityExperimentStrategy.KEEP_BASELINE.name }
                    .toDouble() / outcomes.size,
        )
    }
}
