package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.TextCodec

/** Require this many additional own markets to lose their only possible land entry point. */
data class LeadFrontierDefenseConfig(val minimumProtectedMarkets: Int) {
    init {
        require(minimumProtectedMarkets in 1..3) {
            "Frontier defense requires between one and three additional protected markets."
        }
    }

    companion object {
        const val VERSION = "lead-frontier-defense-1"
    }
}

/** A public approximation of the threats that a comparable current attack can cut off. */
internal object LeadFrontierDefense {
    fun choose(
        view: GameView,
        observation: BotObservation,
        actor: PlayerId,
        selected: String,
        config: LeadFrontierDefenseConfig,
    ): String {
        if ((view.turnNumber ?: 0) < 3 || !selected.startsWith("attack ")) return selected
        val current = TextCodec.parseCommand(selected) as? BoardCommand.Attack ?: return selected
        val ownScore = view.scores.firstOrNull { it.player == actor }?.total ?: return selected
        if (view.scores.any { (it.total ?: Int.MIN_VALUE) > ownScore }) return selected
        val threats = threatenedByFrontier(view, actor)
        if (threats.isEmpty()) return selected
        val offers =
            view.expansionOffers.filter {
                it.primaryAction == ExpansionAction.ATTACK && it.blockers.isEmpty()
            }
        val normal = offers.firstOrNull { it.destination == current.province } ?: return selected
        val normalOdds = normal.competitionWinProbability ?: return selected
        val normalThreats = threats[current.province] ?: 0
        val commands = observation.candidates.map { it.command }.toSet()
        return offers
            .asSequence()
            .filter {
                (threats[it.destination] ?: 0) >= normalThreats + config.minimumProtectedMarkets &&
                    it.requiredTokens <= normal.requiredTokens + 1 &&
                    (it.competitionWinProbability ?: 0.0) >= maxOf(.50, normalOdds - .10)
            }
            .mapNotNull { offer ->
                val odds = offer.competitionWinProbability ?: return@mapNotNull null
                val command = BoardCommand.Attack(offer.destination).toText()
                if (command !in commands) return@mapNotNull null
                val atRisk = threats[offer.destination] ?: return@mapNotNull null
                Defense(command, atRisk * odds, odds, offer.requiredTokens)
            }
            .filter { it.expectedProtected > normalThreats * normalOdds }
            .maxWithOrNull(
                compareBy<Defense> { it.expectedProtected }
                    .thenBy { it.odds }
                    .thenByDescending { it.cost }
                    .thenByDescending { it.command }
            )
            ?.command ?: selected
    }

    /**
     * Count potential entry routes conservatively. A two-edge path can make a second base relevant
     * for Caravan owners even if intermediate control would block the exact path.
     */
    private fun threatenedByFrontier(view: GameView, actor: PlayerId): Map<ProvinceId, Int> {
        val catalog = view.catalog ?: return emptyMap()
        val actorPosition = view.turnOrder.indexOf(actor)
        if (actorPosition < 0) return emptyMap()
        val later = view.turnOrder.drop(actorPosition + 1).toSet()
        val rivals =
            view.players.filter { it.id in later && it.poolAvailable && it.poolTokens >= 2 }
        if (rivals.isEmpty()) return emptyMap()
        val owned = view.board.associateBy { it.id }
        val assets =
            catalog.provinces.filter { province ->
                province.access.isEmpty() &&
                    owned[province.id]?.marker?.let {
                        it.owner == actor && it.side == PieceSide.COLOURED
                    } == true
            }
        if (assets.isEmpty()) return emptyMap()
        val adjacent =
            catalog.landBorders
                .flatMap { edge -> listOf(edge.first to edge.second, edge.second to edge.first) }
                .groupBy({ it.first }, { it.second })
        return rivals
            .flatMap { rival ->
                val capital =
                    rival.capital?.let { name ->
                        catalog.provinces.firstOrNull { it.capital == name }?.id
                    }
                val bases = buildSet {
                    capital?.let(::add)
                    for (province in view.board) {
                        val marker = province.marker
                        if (
                            marker?.owner == rival.id && marker.side == PieceSide.COLOURED ||
                                province.tokens.any { it.owner == rival.id && it.coloured > 0 }
                        ) {
                            add(province.id)
                        }
                    }
                }
                val caravan = AdvanceId.CARAVAN in rival.advances
                assets.mapNotNull { asset ->
                    if (
                        asset.marketNumber > rival.poolTokens ||
                            asset.coastal && rival.ships != ShippingCapability.NONE
                    ) {
                        return@mapNotNull null
                    }
                    val direct = adjacent[asset.id].orEmpty()
                    val routes =
                        if (caravan) direct + direct.flatMap { adjacent[it].orEmpty() } else direct
                    routes.toSet().intersect(bases).singleOrNull()?.takeIf { it != capital }
                }
            }
            .groupingBy { it }
            .eachCount()
    }

    private data class Defense(
        val command: String,
        val expectedProtected: Double,
        val odds: Double,
        val cost: Int,
    )
}
