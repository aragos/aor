package dev.aragos.aor.ai

import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule

/**
 * The one setup supported by the experimental purchase actor. This only qualifies an input:
 * inference additionally requires a confirmed compatible model and its exact baseline. Keep
 * versions explicit so a future rules/catalog update cannot silently broaden qualification.
 */
object PurchasePolicyScope {
    const val RULES_VERSION = "ah1996-three-epoch-4"
    const val DATA_VERSION = "ah1996-catalog-16"
    val configuration =
        BotConfiguration(
            playerCount = 4,
            map = MapRule.STANDARD,
            initialCard = InitialCardRule.NO_CRUSADES,
            deckModification = true,
            immediateMasterArtDiscard = true,
            drawThreeKeepTwo = false,
        )

    fun supports(observation: BotObservation, difficulty: BotDifficulty): Boolean =
        difficulty == BotDifficulty.HARD &&
            observation.decision in setOf("purchase-shipping", "purchase-advances") &&
            observation.rulesVersion == RULES_VERSION &&
            observation.dataVersion == DATA_VERSION &&
            observation.configuration == configuration
}
