package dev.aragos.aor.ai

import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardKind
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec

/** Maximum immediate own dollars a challenger will postpone to avoid becoming the score leader. */
data class CommodityLeadTimingConfig(val maximumPayout: Int) {
    init {
        require(maximumPayout in 1..200) {
            "The challenger payout ceiling must be between one and 200 dollars."
        }
    }

    companion object {
        const val VERSION = "commodity-lead-timing-1"
    }
}

/** A simulated rival can hold a card when its certain payout exposes it to imminent pressure. */
internal object CommodityLeadTiming {
    fun choose(
        view: GameView,
        observation: BotObservation,
        ordinary: String,
        config: CommodityLeadTimingConfig,
    ): String {
        if (
            view.stage != Stage.PLAY_CARDS ||
                view.endingTriggered ||
                (view.turnNumber ?: 0) < 4 ||
                !ordinary.startsWith("play ") ||
                observation.candidates.none { it.command == "pass" }
        )
            return ordinary
        val play = TextCodec.parseCommand(ordinary) as? CardCommand.PlayCard ?: return ordinary
        val card = view.hand.singleOrNull { it.reference == play.reference } ?: return ordinary
        if (card.definition.kind != CardKind.COMMODITY) return ordinary
        val actor = PlayerId(observation.playerId)
        val quote =
            view.cardOffers
                .singleOrNull { it.reference == card.reference }
                ?.choices
                ?.singleOrNull { it.command == ordinary } ?: return ordinary
        val change =
            quote.cashChanges
                .groupBy { it.player }
                .mapValues { (_, effects) ->
                    effects.sumOf { it.after - it.before }
                }
        val payout = change[actor] ?: return ordinary
        if (payout !in 1..config.maximumPayout) return ordinary
        val scores =
            view.scores
                .mapNotNull { score ->
                    score.total?.let { score.player to it }
                }
                .toMap()
        val ownScore = scores[actor] ?: return ordinary
        val rivals = scores.filterKeys { it != actor }
        if (rivals.isEmpty() || rivals.values.none { it > ownScore }) return ordinary
        val after = ownScore + payout
        val rivalsAfter = rivals.mapValues { (rival, score) -> score + (change[rival] ?: 0) }
        if (rivalsAfter.values.any { it >= after }) return ordinary
        val prepared =
            view.players.any { rival ->
                val risk = rivalsAfter[rival.id]
                rival.misery != MiseryStep.CHAOS &&
                    risk != null &&
                    after - risk in 1..99 &&
                    rival.poolAvailable &&
                    rival.poolTokens >= 6
            }
        return if (prepared) "pass" else ordinary
    }
}
