package dev.aragos.aor.training

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.ai.BotObservation

/** A legal preventive alternative with actor-visible features used to select it. */
internal enum class MiseryProtection(val decision: String, val threat: String) {
    GRAIN("expand", "famine"),
    SCIENCE("purchase-advances", "mysticism-abounds");

    data class Opportunity(
        val command: String,
        val facts: Map<String, Any>,
        val cashCost: Int? = null,
    )

    fun opportunity(
        view: GameView,
        actor: PlayerId,
        observation: BotObservation,
        ordinary: String,
    ): Opportunity? {
        if (observation.decision != decision) return null
        val self = view.players.single { it.id == actor }
        val misery = self.misery.value ?: return null
        if (misery < 350) return null
        if (view.unplayedCards.none { it.id == threat }) return null
        val catalog = requireNotNull(view.catalog) { "Protection needs the actual public catalog." }
        val offered = observation.candidates.map { it.command }.toSet()
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
                    val agriculture = if (AdvanceId.IMPROVED_AGRICULTURE in self.advances) 1 else 0
                    val parts = ordinary.split(' ')
                    val chosen = BoardCommand.parse(parts.first(), parts.drop(1))
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
                    if (sciences.any { TurnCommand.BuyAdvance(it.letter).toText() == ordinary }) {
                        return null
                    }
                    val owned = sciences.count { it.id in self.advances }
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
                    Opportunity(
                        best.second,
                        mapOf("price" to best.third, "science" to owned),
                        cashCost = best.third,
                    )
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

/** Optional training override constrained by printed distance to Chaos and an affordable quote. */
internal data class ScienceSurvival(val maximumDistance: Int, val maximumPrice: Int) {
    init {
        require(maximumDistance in 1..8 && maximumPrice in 0..40) {
            "Science survival requires a distance of one through eight steps and a price through 40."
        }
    }

    fun choose(view: GameView, observation: BotObservation, ordinary: String): String? {
        if (observation.decision != MiseryProtection.SCIENCE.decision) return null
        val actor = PlayerId(observation.playerId)
        val misery = view.players.single { it.id == actor }.misery
        if (MiseryStep.CHAOS.ordinal - misery.ordinal !in 1..maximumDistance) return null
        val opportunity =
            MiseryProtection.SCIENCE.opportunity(view, actor, observation, ordinary) ?: return null
        val price =
            requireNotNull(opportunity.cashCost) { "A legal science purchase needs a price." }
        return opportunity.command.takeIf { price <= maximumPrice }
    }

    companion object {
        const val VERSION = "near-chaos-science-1"
    }
}
