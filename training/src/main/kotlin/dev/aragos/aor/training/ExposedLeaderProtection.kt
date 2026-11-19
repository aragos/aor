package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.GameView
import dev.aragos.aor.LeaderRules
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation

/** The same leader play with alternative offered protection escrows. */
internal data class ExposedLeaderProtection(
    val alternatives: Map<String, String>,
    val facts: Map<String, Any>,
) {
    companion object {
        fun forPlay(
            view: GameView,
            actor: PlayerId,
            observation: BotObservation,
            command: String,
        ): ExposedLeaderProtection? {
            val original = TextCodec.parseCommand(command) as? CardCommand.PlayCard ?: return null
            val card = view.hand.singleOrNull { it.reference == original.reference } ?: return null
            val printed =
                CardEffectData.forCard(card.definition.id) as? CardEffectSpec.Leader ?: return null
            val offer = view.cardOffers.single { it.reference == card.reference }
            val maximum = offer.protectionMax ?: return null
            val current = protection(original)
            if (maximum <= current) return null
            val rivals =
                view.players.mapNotNull { rival ->
                    if (
                        rival.id == actor ||
                            rival.misery == MiseryStep.CHAOS ||
                            AdvanceId.PATRONAGE !in rival.advances ||
                            rival.cash <= current
                    )
                        return@mapNotNull null
                    val possible =
                        offer.leaderDiscounts
                            .filter { it.advance !in rival.advances }
                            .sumOf {
                                it.conditionalAmount +
                                    LeaderRules.writtenRecordBonus(rival.advances)
                            }
                    possible.takeIf { it > current }
                }
            val possibleCredit = rivals.maxOrNull() ?: return null
            val offered =
                observation.candidates
                    .mapNotNull { option ->
                        if (option.group != "card.${original.reference}") return@mapNotNull null
                        val other =
                            TextCodec.parseCommand(option.command) as? CardCommand.PlayCard
                                ?: return@mapNotNull null
                        val amount = protection(other)
                        if (
                            other.reference == original.reference &&
                                amount in (current + 1)..possibleCredit
                        ) {
                            amount to option.command
                        } else null
                    }
                    .toMap()
            val moderate = offered.entries.filter { it.key in 5..10 }.minByOrNull { it.key }
            val large = offered.entries.filter { it.key in 11..20 }.maxByOrNull { it.key }
            val alternatives = buildMap {
                put("ordinary", command)
                moderate?.let { put("moderate", it.value) }
                large?.let { put("larger", it.value) }
            }
            if (alternatives.size < 2) return null
            return ExposedLeaderProtection(
                alternatives,
                mapOf(
                    "leader" to printed.leader.cardId,
                    "eligiblePatrons" to rivals.size,
                    "maximumProjectedRivalCredit" to possibleCredit,
                    "printedWordRebate" to offer.leaderDiscounts.sumOf { it.rebate },
                    "cash" to observation.availableCash,
                    "protectionAmounts" to
                        alternatives.mapValues { (_, selected) ->
                            protection(TextCodec.parseCommand(selected) as CardCommand.PlayCard)
                        },
                ),
            )
        }

        private fun protection(command: CardCommand.PlayCard): Int =
            (command.choice as? CardChoice.LeaderProtection)?.cash ?: 0
    }
}
