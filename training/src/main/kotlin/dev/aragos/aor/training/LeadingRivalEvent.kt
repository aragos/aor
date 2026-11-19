package dev.aragos.aor.training

import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardChoiceKind
import dev.aragos.aor.CardChoiceView
import dev.aragos.aor.CardCommand
import dev.aragos.aor.EventId
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation

/** One legal substitute target for an economic event the reference already decided to play. */
internal data class LeadingRivalEvent(
    val card: EventId,
    val ordinaryTarget: PlayerId,
    val target: PlayerId,
    val command: String,
    val trailingPoints: Int,
    val ordinaryDamage: Int,
    val damage: Int,
) {
    companion object {
        fun select(
            view: GameView,
            actor: PlayerId,
            observation: BotObservation,
            command: String,
        ): LeadingRivalEvent? {
            val played = TextCodec.parseCommand(command) as? CardCommand.PlayCard ?: return null
            val ordinaryTarget = (played.choice as? CardChoice.PlayerChoice)?.player ?: return null
            val handCard = view.hand.firstOrNull { it.reference == played.reference } ?: return null
            val event =
                when (handCard.definition.id) {
                    EventId.ALCHEMISTS_GOLD.cardId -> EventId.ALCHEMISTS_GOLD
                    EventId.CIVIL_WAR.cardId -> EventId.CIVIL_WAR
                    else -> return null
                }
            val points =
                view.scores
                    .mapNotNull { score ->
                        score.total?.let { score.player to it }
                    }
                    .toMap()
            val own = points[actor] ?: return null
            val top = points.values.maxOrNull() ?: return null
            val trailing = top - own
            if (trailing !in 1..400 || points[ordinaryTarget] == top) return null
            val offers =
                view.cardOffers.firstOrNull { it.reference == played.reference }?.choices
                    ?: return null
            val reference = offers.firstOrNull { it.command == command } ?: return null
            val normalDamage = damage(event, reference, ordinaryTarget, view)
            val legalCommands = observation.candidates.map { it.command }.toSet()
            return offers
                .asSequence()
                .filter {
                    it.kind == CardChoiceKind.PLAYER && !it.noEffect && it.command in legalCommands
                }
                .mapNotNull { offer ->
                    val target = PlayerId(offer.value ?: return@mapNotNull null)
                    if (points[target] != top) return@mapNotNull null
                    val impact = damage(event, offer, target, view)
                    if (impact <= 0 || impact * 5 < normalDamage * 3) return@mapNotNull null
                    LeadingRivalEvent(
                        event,
                        ordinaryTarget,
                        target,
                        offer.command ?: return@mapNotNull null,
                        trailing,
                        normalDamage,
                        impact,
                    )
                }
                .maxWithOrNull(
                    compareBy<LeadingRivalEvent> { it.damage }.thenByDescending { it.command }
                )
        }

        /**
         * Approximate public disruption in score-equivalent dollars. Gold uses the actual quote.
         * Civil War includes misery, capital presence, delay and the cheaper published loss option.
         */
        private fun damage(
            event: EventId,
            offer: CardChoiceView,
            target: PlayerId,
            view: GameView,
        ): Int {
            if (event == EventId.ALCHEMISTS_GOLD) {
                val cash = offer.cashChanges.firstOrNull { it.player == target } ?: return 0
                return (cash.before - cash.after).coerceAtLeast(0)
            }
            val owner = view.players.first { it.id == target }
            val misery = offer.miseryChanges.firstOrNull { it.player == target }
            if (misery != null && misery.toValue == null) return 1500
            val miseryLoss = (misery?.toValue ?: 0) - (misery?.fromValue ?: 0)
            val capitalLoss =
                if (
                    view.board.any {
                        it.id.value == owner.capital && it.marker?.owner == target
                    }
                )
                    view.players.size * 4
                else 0
            val delay = view.turnOrder.lastIndex - view.turnOrder.indexOf(target)
            val cash = minOf(owner.cash, (owner.writtenCash + 1) / 2)
            val tokens = (owner.poolTokens + 1) / 2
            return miseryLoss + capitalLoss + 8 * delay + minOf(cash, 4 * tokens)
        }
    }
}
