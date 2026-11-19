package dev.aragos.aor.ai

import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId

/**
 * The dollar-valued timing signals scale one expected printed turn-order box. The expansion
 * allocation, relative bid limit and public counter forecast remain the ordinary initiative rules.
 */
data class PhaseInitiativeConfig(
    val allocation: InitiativePolicyConfig,
    val earlyPayout: Double,
    val buyingCard: Double,
    val secureMarkets: Double,
    val earlyCompetition: Double,
) {
    init {
        require(
            listOf(earlyPayout, buyingCard, secureMarkets, earlyCompetition).all {
                it.isFinite() && it in 0.0..2.0
            }
        ) {
            "Phase initiative weights must be finite numbers between zero and two."
        }
    }
}

/** Price earlier and later positions from the acting seat's hand and the public board. */
object PhaseInitiativePolicy {
    fun bid(
        view: GameView,
        actor: PlayerId,
        ordinary: String,
        seed: Int,
        config: PhaseInitiativeConfig,
    ): String {
        if (view.pastTokenPurchases.size < 2) return ordinary
        val payouts = view.commodityPayouts.associateBy { it.commodity }
        val heldAdvantage =
            view.hand.maxOfOrNull { card ->
                val effect = CardEffectData.forCard(card.definition.id)
                if (effect !is CardEffectSpec.CommodityChoices) return@maxOfOrNull 0
                effect.choices.maxOf { commodity ->
                    val owners = payouts[commodity]?.owners.orEmpty()
                    val own = owners.firstOrNull { it.player == actor }?.payout ?: 0
                    val rival = owners.filter { it.player != actor }.maxOfOrNull { it.payout } ?: 0
                    (own - rival).coerceAtLeast(0)
                }
            } ?: 0
        val cardNeed =
            if (view.deckRemaining > view.players.size) (2 - view.hand.size).coerceAtLeast(0) / 2.0
            else 0.0
        val competition =
            if (view.expansionForecast?.contestedMarkets?.isNotEmpty() == true) 1.0 else 0.0
        val exposed = exposedMarkets(view, actor)
        val exposedPayout =
            payouts.values.maxOfOrNull { payout ->
                val owner =
                    payout.owners.firstOrNull { it.player == actor } ?: return@maxOfOrNull 0.0
                if (owner.provinces.none { it in exposed }) return@maxOfOrNull 0.0
                val box = owner.effectiveBox.coerceAtLeast(1)
                owner.payout.toDouble() * (2 * box - 1) / (box * box)
            } ?: 0.0
        val earlier =
            config.earlyPayout * (heldAdvantage / 40.0).coerceAtMost(1.0) +
                config.buyingCard * cardNeed +
                config.earlyCompetition * competition
        val later = config.secureMarkets * (exposedPayout / 24.0).coerceAtMost(1.0)
        return InitiativePolicy.bid(
            view,
            actor,
            ordinary,
            seed,
            config.allocation,
            orderWeight = (earlier - later).coerceIn(-1.5, 1.5),
        )
    }

    private fun exposedMarkets(view: GameView, actor: PlayerId): Set<ProvinceId> {
        val rivals =
            view.players
                .filter {
                    it.id != actor && it.misery != MiseryStep.CHAOS && it.stockTokens > 0
                }
                .map { it.id }
                .toSet()
        if (rivals.isEmpty()) return emptySet()
        val present =
            view.board
                .filter { province ->
                    province.marker?.owner in rivals || province.tokens.any { it.owner in rivals }
                }
                .map { it.id }
                .toSet()
        if (present.isEmpty()) return emptySet()
        return buildSet {
            for (border in view.catalog?.landBorders.orEmpty()) {
                if (border.first in present) add(border.second)
                if (border.second in present) add(border.first)
            }
        }
    }
}
