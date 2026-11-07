package dev.aragos.aor.training

/** Fixed-input choices only; this report contains no counterfactual game outcome. */
internal data class JointEconomyChoices(
    val decision: String,
    val incumbent: String,
    val candidates: Map<String, String>,
)

/**
 * Reuses one frozen controller pool across consumed observations. A caller may record these choices
 * while executing only the incumbent. Actual SimulationDecision seeds preserve sealed batch
 * semantics; no alternative is applied and no authority state enters this helper.
 */
internal class JointEconomyPreflight(environment: CardAdvantageEnvironment) {
    private val controllers =
        JointEconomyCandidates.generate(environment.baseline.model).associate {
            it.id to JointEconomyController(environment, it.policy)
        }

    fun choices(decision: SimulationDecision): JointEconomyChoices? {
        val incumbent =
            requireNotNull(controllers["incumbent"]) {
                "The fixed economy pool includes its incumbent."
            }
        if (!incumbent.controls(decision.observation.decision)) return null
        val commands = controllers.mapValues { (_, controller) ->
            requireNotNull(controller.choose(decision)) {
                "Every economy controller handles the same declared decision stages."
            }
        }
        return JointEconomyChoices(
            decision.observation.decision,
            requireNotNull(commands["incumbent"]) { "The incumbent choice must be recorded." },
            commands,
        )
    }
}
