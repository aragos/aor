package dev.aragos.aor.ai

/**
 * Exact current-rules input scope for an optional qualified joint card/purchase actor. Eligibility
 * alone never installs an actor: the browser must validate both components and all policy bindings.
 */
object JointPolicyScope {
    const val RULES_VERSION = PurchasePolicyScope.CURRENT_RULES_VERSION
    const val DATA_VERSION = PurchasePolicyScope.DATA_VERSION
    val configuration = PurchasePolicyScope.configuration

    fun supports(observation: BotObservation, difficulty: BotDifficulty): Boolean =
        difficulty == BotDifficulty.HARD &&
            observation.rulesVersion == RULES_VERSION &&
            observation.dataVersion == DATA_VERSION &&
            observation.configuration == configuration &&
            observation.decision in setOf("play-cards", "purchase-shipping", "purchase-advances")
}
