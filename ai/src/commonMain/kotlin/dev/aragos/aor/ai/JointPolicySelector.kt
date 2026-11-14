package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId

/** Explicit behavior identity; omission preserves the original full-support C/P samplers. */
enum class JointSupportProfile(val version: String) {
    KNOWN_OWN_CHAOS_1(KnownOwnChaosSupport.VERSION)
}

/**
 * Own/public C/P inference shared by native evaluation and the application. [ordinary] owns the
 * card HARD prior; [purchaseBaseline] owns the independently frozen F0 purchase HARD prior. [seed]
 * in [choose] is the original bot seed, before the unchanged phase-specific salt.
 */
class JointPolicySelector(
    private val ordinary: BotModel,
    private val purchaseBaseline: BotModel,
    private val card: CardPolicyModel,
    private val purchase: PurchasePolicyModel,
    private val support: JointSupportProfile? = null,
) {
    fun controls(decision: String): Boolean =
        decision == "play-cards" ||
            decision == "purchase-shipping" ||
            decision == "purchase-advances"

    fun choose(view: GameView, observation: BotObservation, seed: Int): String {
        require(controls(observation.decision)) {
            "The joint selector controls only C/P decisions."
        }
        val player = PlayerId(observation.playerId)
        val isCard = observation.decision == "play-cards"
        val recommendation =
            BotPolicy.choose(
                observation,
                BotDifficulty.HARD,
                seed,
                if (isCard) ordinary else purchaseBaseline,
            )
        if (isCard) {
            val encoded = CardObservationEncoder.from(view, player, observation, recommendation)
            val actorSeed = CardPolicy.actorSeed(seed)
            if (support == null) return CardPolicy.choose(encoded, card, actorSeed).command
            return KnownOwnChaosSupport.card(
                    encoded,
                    CardPolicy.evaluate(encoded, card),
                    CardPolicy.uniform(actorSeed),
                )
                .command
        }
        val encoded = PurchaseObservationEncoder.from(view, player, observation, recommendation)
        val actorSeed = PurchasePolicy.actorSeed(seed)
        if (support == null) return PurchasePolicy.choose(encoded, purchase, actorSeed).command
        return KnownOwnChaosSupport.purchase(
                encoded,
                PurchasePolicy.evaluate(encoded, purchase),
                PurchasePolicy.uniform(actorSeed),
            )
            .command
    }
}
