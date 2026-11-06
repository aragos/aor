package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/**
 * Frozen choices for one source position. [index] identifies its original position artifact;
 * [scenarioSeed] recreates the curriculum root. Predictions and label rewards use strategy enum
 * order. Choices are fixed before any diagnostic continuations are generated.
 */
data class PilotDiagnosticSelection(
    val index: Int,
    val scenarioSeed: Int,
    val tag: String,
    val teacher: CommodityExperimentStrategy,
    val student: CommodityExperimentStrategy,
    val predictions: List<Double>,
    val labelRewards: List<Double>,
) {
    init {
        require(index in 0..9999) { "Diagnostic position indices must be between 0 and 9999." }
        require(tag.isNotBlank()) { "A diagnostic position requires a nonblank family tag." }
        require(
            predictions.size == CommodityExperimentStrategy.entries.size &&
                predictions.all(Double::isFinite)
        ) {
            "Diagnostic predictions require one finite value per strategy."
        }
        require(
            labelRewards.size == CommodityExperimentStrategy.entries.size &&
                labelRewards.all { it.isFinite() && it in 0.0..1.0 }
        ) {
            "Diagnostic labels require one finite reward between zero and one per strategy."
        }
    }
}

/**
 * Fresh paired terminal rewards; sample indices share initial conditions across all three choices.
 */
data class PilotDiagnosticOutcome(
    val selection: PilotDiagnosticSelection,
    val baselineRewards: List<Double>,
    val teacherRewards: List<Double>,
    val studentRewards: List<Double>,
) {
    init {
        require(
            baselineRewards.isNotEmpty() &&
                baselineRewards.size == teacherRewards.size &&
                baselineRewards.size == studentRewards.size
        ) {
            "Diagnostic outcomes require equal nonempty paired reward samples."
        }
        require(
            listOf(baselineRewards, teacherRewards, studentRewards).all { rewards ->
                rewards.all { it.isFinite() && it in 0.0..1.0 }
            }
        ) {
            "Diagnostic rewards must be finite and between zero and one."
        }
    }
}

/** Descriptive differences across independent scenario means, including per-family results. */
data class PilotDiagnosticGroup(
    val scenarios: Int,
    val teacherVsBaseline: PilotInterval,
    val studentVsBaseline: PilotInterval,
    val teacherVsStudent: PilotInterval,
    val teacherChoices: Map<String, Int>,
    val studentChoices: Map<String, Int>,
)

/** Investigative results on previously inspected roots; this report cannot promote a model. */
data class PilotDiagnosticReport(
    val scenarios: Int,
    val minimumSamplesPerScenario: Int,
    val maximumSamplesPerScenario: Int,
    val baselineMeanReward: Double,
    val teacherMeanReward: Double,
    val studentMeanReward: Double,
    val teacherVsBaseline: PilotInterval,
    val studentVsBaseline: PilotInterval,
    val teacherVsStudent: PilotInterval,
    val teacherChoices: Map<String, Int>,
    val studentChoices: Map<String, Int>,
    val byTag: Map<String, PilotDiagnosticGroup>,
) {
    val promoted: Boolean = false
    val scope: String =
        "Post-hoc diagnostic on previously inspected curriculum roots using fresh continuations. " +
            "Choices are frozen from the original labels and student. " +
            "Intervals use a normal approximation across scenario means, not individual rollouts. " +
            "This is not an independent full-game qualification or model promotion."
}

/** Freeze choices on old evidence, then compare them on separately generated chance outcomes. */
object PilotDiagnostic {
    /** Arithmetic equality tolerance for sampled means, not a minimum strategic advantage. */
    const val NUMERICAL_TIE_TOLERANCE = 1e-12

    fun select(row: JsonNode, student: PilotStudent): PilotDiagnosticSelection {
        val strategies = row.required("strategies")
        require(
            strategies.isArray &&
                strategies.map { it.required("strategy").asText() } ==
                    CommodityExperimentStrategy.entries.map { it.name }
        ) {
            "Diagnostic labels must retain the complete current strategy enum order."
        }
        val values = strategies.map { strategy ->
            val value = strategy.required("reward").required("mean")
            require(value.isNumber) { "A diagnostic label mean must be numeric." }
            value.doubleValue()
        }
        val featureValues = row.required("features")
        require(
            featureValues.isArray && featureValues.size() == CommodityExperiment.featureNames.size
        ) {
            "Diagnostic features must match the current curriculum feature order and dimension."
        }
        val features = featureValues.map { feature ->
            require(feature.isNumber && feature.doubleValue().isFinite()) {
                "Diagnostic features must contain finite numbers."
            }
            feature.doubleValue()
        }
        val predictions = student.predict(features)
        fun integer(name: String): Int {
            val value = row.required(name)
            require(value.isIntegralNumber && value.canConvertToInt()) {
                "Diagnostic position $name must be a 32-bit integer."
            }
            return value.intValue()
        }
        val tag = row.required("tag")
        require(tag.isTextual) { "A diagnostic family tag must be text." }
        val maximum = values.max()
        val teacherIndex =
            requireNotNull(
                values.indices.firstOrNull { index ->
                    maximum - values[index] <= NUMERICAL_TIE_TOLERANCE
                }
            ) {
                "Diagnostic labels require finite reward values."
            }
        return PilotDiagnosticSelection(
            index = integer("index"),
            scenarioSeed = integer("seed"),
            tag = tag.asText(),
            teacher = CommodityExperimentStrategy.entries[teacherIndex],
            student = CommodityExperimentStrategy.entries[student.choose(features)],
            predictions = predictions,
            labelRewards = values,
        )
    }

    fun report(outcomes: List<PilotDiagnosticOutcome>): PilotDiagnosticReport {
        require(outcomes.isNotEmpty()) { "A diagnostic report requires at least one scenario." }
        require(outcomes.map { it.selection.index }.distinct().size == outcomes.size) {
            "Diagnostic position indices must be unique; samples belong to one outcome."
        }
        require(outcomes.map { it.selection.scenarioSeed }.distinct().size == outcomes.size) {
            "Diagnostic scenario seeds must be unique; samples belong to one outcome."
        }
        val overall = summarize(outcomes)
        return PilotDiagnosticReport(
            scenarios = outcomes.size,
            minimumSamplesPerScenario = outcomes.minOf { it.baselineRewards.size },
            maximumSamplesPerScenario = outcomes.maxOf { it.baselineRewards.size },
            baselineMeanReward = outcomes.map { it.baselineRewards.average() }.average(),
            teacherMeanReward = outcomes.map { it.teacherRewards.average() }.average(),
            studentMeanReward = outcomes.map { it.studentRewards.average() }.average(),
            teacherVsBaseline = overall.teacherVsBaseline,
            studentVsBaseline = overall.studentVsBaseline,
            teacherVsStudent = overall.teacherVsStudent,
            teacherChoices = overall.teacherChoices,
            studentChoices = overall.studentChoices,
            byTag =
                outcomes
                    .groupBy { it.selection.tag }
                    .toSortedMap()
                    .mapValues { (_, group) ->
                        summarize(group)
                    },
        )
    }

    private fun summarize(outcomes: List<PilotDiagnosticOutcome>): PilotDiagnosticGroup {
        fun comparison(
            first: (PilotDiagnosticOutcome) -> List<Double>,
            second: (PilotDiagnosticOutcome) -> List<Double>,
        ): PilotInterval =
            pilotInterval(
                outcomes.map { outcome ->
                    first(outcome)
                        .zip(second(outcome)) { candidate, baseline -> candidate - baseline }
                        .average()
                }
            )
        fun choices(
            select: (PilotDiagnosticSelection) -> CommodityExperimentStrategy
        ): Map<String, Int> =
            CommodityExperimentStrategy.entries.associate { strategy ->
                strategy.name to outcomes.count { select(it.selection) == strategy }
            }
        return PilotDiagnosticGroup(
            scenarios = outcomes.size,
            teacherVsBaseline =
                comparison(
                    PilotDiagnosticOutcome::teacherRewards,
                    PilotDiagnosticOutcome::baselineRewards,
                ),
            studentVsBaseline =
                comparison(
                    PilotDiagnosticOutcome::studentRewards,
                    PilotDiagnosticOutcome::baselineRewards,
                ),
            teacherVsStudent =
                comparison(
                    PilotDiagnosticOutcome::teacherRewards,
                    PilotDiagnosticOutcome::studentRewards,
                ),
            teacherChoices = choices(PilotDiagnosticSelection::teacher),
            studentChoices = choices(PilotDiagnosticSelection::student),
        )
    }
}

/**
 * Negative continuation seed, separate from the source pilot's positive teacher/validation seeds.
 * For a fixed experiment [seed], every position [index] and sample [sample] has a unique seed. A
 * new experiment seed does not imply disjointness from another diagnostic run's seeds.
 */
internal fun diagnosticSeed(seed: Int, index: Int, sample: Int): Int {
    require(seed in 0..100_000_000) { "The diagnostic seed must be between 0 and 100000000." }
    require(index in 0..9999) { "Diagnostic seed position indices must be between 0 and 9999." }
    require(sample in 0..255) { "Diagnostic sample indices must be between 0 and 255." }
    return Int.MIN_VALUE + seed + index * 256 + sample
}
