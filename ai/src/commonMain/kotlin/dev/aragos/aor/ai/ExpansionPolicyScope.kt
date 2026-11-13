package dev.aragos.aor.ai

/**
 * Input scope for a separately admitted expansion actor. This grants only projection eligibility;
 * installing a model additionally requires its exact reference and behavior composition.
 */
object ExpansionPolicyScope {
    const val RULES_VERSION = PurchasePolicyScope.CURRENT_RULES_VERSION
    const val DATA_VERSION = PurchasePolicyScope.DATA_VERSION
    const val ACTOR_VERSION = "aor-expansion-policy-2"
    val configuration = PurchasePolicyScope.configuration

    fun supports(observation: BotObservation, difficulty: BotDifficulty): Boolean =
        difficulty == BotDifficulty.HARD &&
            observation.decision == TerritoryPhase.EXPANSION.decision &&
            observation.rulesVersion == RULES_VERSION &&
            observation.dataVersion == DATA_VERSION &&
            observation.configuration == configuration
}
