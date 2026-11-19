package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Epoch
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.TextCodec

/** Last epoch in which a safer market can replace a near-exhaustive attack. */
data class PeacefulExpansionAlternativeConfig(val through: Epoch) {
    companion object {
        const val VERSION = "peaceful-expansion-alternative-1"
    }
}

/** Use the actual card-price amount as a reserve in a contested expansion decision. */
internal object PeacefulExpansionAlternative {
    fun choose(
        view: GameView,
        observation: BotObservation,
        ordinary: String,
        config: PeacefulExpansionAlternativeConfig,
    ): String {
        if (
            view.currentEpoch > config.through.number ||
                view.endingTriggered ||
                !ordinary.startsWith("attack ")
        )
            return ordinary
        if (observation.candidates.none { it.command == BoardCommand.BuyExpansionCard.toText() }) {
            return ordinary
        }
        val price = view.expansionCardCost ?: return ordinary
        val selected = TextCodec.parseCommand(ordinary) as? BoardCommand.Attack ?: return ordinary
        val offer =
            view.expansionOffers.singleOrNull { it.destination == selected.province }
                ?: return ordinary
        val poolAfterReserve = observation.availableTokens - price
        if (!offer.establishesMarker || offer.requiredTokens <= poolAfterReserve) return ordinary

        val candidates = observation.candidates.associateBy { it.command }
        return view.expansionOffers
            .asSequence()
            .filter { quote ->
                quote.primaryAction == ExpansionAction.PLACE &&
                    quote.establishesMarker &&
                    !quote.uncertain &&
                    quote.blockers.isEmpty() &&
                    quote.requiredTokens in 1..poolAfterReserve
            }
            .mapNotNull { quote ->
                val command = BoardCommand.Place(quote.requiredTokens, quote.destination).toText()
                val candidate = candidates[command] ?: return@mapNotNull null
                Market(
                    command,
                    (quote.incomeAfter ?: 0) - (quote.incomeBefore ?: 0),
                    candidate.features[BotEconomicFeature.HELD_COMMODITY.key] ?: 0.0,
                    quote.requiredTokens,
                )
            }
            .maxWithOrNull(
                compareBy<Market> { it.income }
                    .thenBy { it.heldGain }
                    .thenByDescending { it.cost }
                    .thenByDescending { it.command }
            )
            ?.command ?: ordinary
    }

    private data class Market(
        val command: String,
        val income: Int,
        val heldGain: Double,
        val cost: Int,
    )
}
