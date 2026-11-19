package dev.aragos.aor.training

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameState
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.ai.BotObservation

/** A legal preventive alternative with actor-visible features used to select it. */
internal enum class MiseryProtection(val decision: String, val threat: String) {
    GRAIN("expand", "famine"),
    SCIENCE("purchase-advances", "mysticism-abounds");

    data class Opportunity(val command: String, val facts: Map<String, Any>)

    fun opportunity(
        state: GameState,
        actor: PlayerId,
        observation: BotObservation,
        ordinary: String,
    ): Opportunity? {
        if (observation.decision != decision) return null
        val self = state.playerState(actor)
        val misery = self.misery.value ?: return null
        if (misery < 350) return null
        val view = state.viewForPlayer(actor)
        if (view.unplayedCards.none { it.id == threat }) return null
        val catalog = requireNotNull(view.catalog) { "Protection needs the actual public catalog." }
        val offered = observation.candidates.map { it.command }.toSet()
        val chosen = TextCodec.parseAction(ordinary, state, actor).command
        val option =
            when (this) {
                GRAIN -> {
                    val grain = catalog.provinces.filter { Commodity.GRAIN in it.commodities }
                    fun isGrain(province: ProvinceId): Boolean = grain.any { it.id == province }
                    val farms =
                        view.commodityPayouts
                            .single { it.commodity == Commodity.GRAIN }
                            .owners
                            .firstOrNull { it.player == actor }
                            ?.marketCount ?: 0
                    val agriculture =
                        if (self.advances.owns(AdvanceId.IMPROVED_AGRICULTURE)) 1 else 0
                    val chosenGrain =
                        when (chosen) {
                            is BoardCommand.Place -> isGrain(chosen.province)
                            is BoardCommand.Attack -> isGrain(chosen.province)
                            else -> false
                        }
                    if (farms + agriculture >= 4 || chosenGrain) return null
                    val quote =
                        view.expansionOffers
                            .filter {
                                it.establishesMarker &&
                                    !it.uncertain &&
                                    it.blockers.isEmpty() &&
                                    isGrain(it.destination) &&
                                    BoardCommand.Place(it.requiredTokens, it.destination)
                                        .toText() in offered
                            }
                            .minWithOrNull(
                                compareBy({ it.requiredTokens }, { it.destination.value })
                            ) ?: return null
                    Opportunity(
                        BoardCommand.Place(quote.requiredTokens, quote.destination).toText(),
                        mapOf("tokens" to quote.requiredTokens, "grainMarkets" to farms),
                    )
                }
                SCIENCE -> {
                    val sciences =
                        catalog.advances.filter { it.category == AdvanceCategory.SCIENCE }
                    if (
                        chosen is TurnCommand.BuyAdvance &&
                            sciences.any { it.letter == chosen.letter }
                    ) {
                        return null
                    }
                    val owned = sciences.count { self.advances.owns(it.id) }
                    if (owned >= 4) return null
                    val quotes = view.advanceOffers.associateBy { it.advance }
                    val best =
                        sciences
                            .mapNotNull { definition ->
                                val price = quotes[definition.id]?.price ?: return@mapNotNull null
                                val command = TurnCommand.BuyAdvance(definition.letter).toText()
                                if (command !in offered) return@mapNotNull null
                                Triple(definition, command, price)
                            }
                            .minWithOrNull(
                                compareBy(
                                    { it.third },
                                    { it.first.id != AdvanceId.HUMAN_BODY },
                                    { it.first.letter },
                                )
                            ) ?: return null
                    Opportunity(best.second, mapOf("price" to best.third, "science" to owned))
                }
            }
        return option.copy(
            facts =
                option.facts +
                    mapOf(
                        "kind" to name.lowercase(),
                        "threat" to threat,
                        "misery" to misery,
                        "stepsToChaos" to (MiseryStep.CHAOS.ordinal - self.misery.ordinal),
                        "finalTurn" to view.endingTriggered,
                    )
        )
    }
}
