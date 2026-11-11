package dev.aragos.aor.ai

import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerView

/**
 * Public target facts for completed attacks on opposing markets. Shares include the acting player
 * and all other active players; an empty denominator gives zero. Every value is multiplied by the
 * offer's dice-only success probability, without predicting optional Cathedral declarations. These
 * are learning inputs, not prescribed target preferences; existing models assign zero weight.
 */
enum class BotExpansionTargetFeature(val key: String) {
    MARKET("expansion.targetMarket"),
    INCOME_SHARE("expansion.targetIncomeShare"),
    ADVANCE_SHARE("expansion.targetAdvanceShare"),
    CASH_SHARE("expansion.targetCashShare"),
}

internal class BotExpansionTargets(view: GameView, private val actor: PlayerId) {
    private val players = view.players.filter { it.misery != MiseryStep.CHAOS }
    private val playersById = players.associateBy(PlayerView::id)
    private val markers =
        view.board
            .mapNotNull { province ->
                province.marker?.let { province.id to it.owner }
            }
            .toMap()
    private val income = players.sumOf { it.income?.ordinary ?: 0 }.toDouble()
    private val advances = players.sumOf { it.advances.size }.toDouble()
    private val cash = players.sumOf { it.cash.toDouble() }

    fun features(offer: ExpansionOfferView, complete: Boolean): Map<String, Double> {
        if (!complete || offer.primaryAction != ExpansionAction.ATTACK) return emptyMap()
        val owner = markers[offer.destination] ?: return emptyMap()
        if (owner == actor) return emptyMap()
        val target = playersById[owner] ?: return emptyMap()
        val chance = offer.competitionWinProbability ?: 0.5
        return mapOf(
            BotExpansionTargetFeature.MARKET.key to chance,
            BotExpansionTargetFeature.INCOME_SHARE.key to
                share(target.income?.ordinary ?: 0, income, chance),
            BotExpansionTargetFeature.ADVANCE_SHARE.key to
                share(target.advances.size, advances, chance),
            BotExpansionTargetFeature.CASH_SHARE.key to share(target.cash, cash, chance),
        )
    }

    private fun share(value: Int, total: Double, chance: Double): Double =
        if (total == 0.0) 0.0 else chance * value / total
}
