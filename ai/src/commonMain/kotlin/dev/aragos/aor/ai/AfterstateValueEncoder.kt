package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage

/** Actual own decision/boundary contexts; ordinal is a versioned research wire identifier. */
enum class AfterstateContext(val decision: String, val prefix: String) {
    CARD("play-cards", "card"),
    SHIPPING("purchase-shipping", "purchase"),
    ADVANCES("purchase-advances", "purchase"),
    STABILIZATION("purchase-stabilization", "purchase"),
    RELIEF("purchase-relief", "purchase"),
    OWNER_EXIT("purchase-owner-exit", "purchase"),
}

/**
 * State-only research encoding. Existing joint columns retain their exact names, order and units.
 * New contexts append catalog-derived keys; no action, terminal label or future card is an input.
 * OWNER_EXIT requires the actual owner's ActivityPlanning boundary, never a next-player view.
 */
object AfterstateValueEncoder {
    const val VERSION = "aor-afterstate-value-1"

    fun legacyDictionary(catalog: PublicCatalog): List<String> =
        (CardObservationEncoder.dictionary(catalog).stateFeatureNames.map { "card.$it" } +
                PurchaseObservationEncoder.dictionary(catalog).stateFeatureNames.map {
                    "purchase.$it"
                })
            .sorted()

    fun dictionary(catalog: PublicCatalog): List<String> {
        val original = legacyDictionary(catalog)
        val purchase =
            RawActorState.dictionary(
                    catalog,
                    AfterstateContext.entries
                        .filter { it != AfterstateContext.CARD }
                        .map(AfterstateContext::decision),
                )
                .map { "purchase.$it" }
        return original + (purchase.toSet() - original.toSet()).sorted()
    }

    fun from(
        view: GameView,
        owner: PlayerId,
        context: AfterstateContext,
        observation: BotObservation? = null,
    ): Map<String, Double> {
        val raw =
            if (context == AfterstateContext.CARD) {
                val offered =
                    requireNotNull(observation) { "Card states require their actual offers." }
                require(offered.decision == context.decision) {
                    "Card state context must match the actual decision."
                }
                CardObservationEncoder.from(
                        view,
                        owner,
                        offered,
                        offered.candidates.first().command,
                    )
                    .stateFeatures
            } else {
                require(view.stage == Stage.PURCHASE || context == AfterstateContext.OWNER_EXIT) {
                    "Purchase value states require the actual purchase phase or its owner boundary."
                }
                RawActorState.from(view, owner, context.decision)
            }
        return raw.mapKeys { (name, _) -> "${context.prefix}.$name" }
    }
}
