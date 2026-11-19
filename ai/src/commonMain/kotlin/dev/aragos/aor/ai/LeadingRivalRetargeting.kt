package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TextCodec

/**
 * Use public score points to bound how far behind the acting seat can be. The additional commodity
 * condition admits only targets with at least five dollars more projected public denial.
 */
data class LeadingRivalRetargetingConfig(
    val maximumTrailingPoints: Int,
    val requireGreaterCommodityDenial: Boolean,
) {
    init {
        require(maximumTrailingPoints in 1..10000) {
            "Retargeting requires a positive score gap of at most 10,000 points."
        }
    }

    companion object {
        const val VERSION = "leading-rival-retargeting-1"
    }
}

/** Redirect an already selected attack to a comparable fight against a visible score leader. */
internal object LeadingRivalRetargeting {
    fun choose(
        view: GameView,
        actor: PlayerId,
        observation: BotObservation,
        ordinary: String,
        config: LeadingRivalRetargetingConfig,
    ): String {
        if ((view.turnNumber ?: 0) < 4 || !ordinary.startsWith("attack ")) return ordinary
        val original = TextCodec.parseCommand(ordinary) as? BoardCommand.Attack ?: return ordinary
        val scores =
            view.scores
                .mapNotNull { score ->
                    score.total?.let { score.player to it }
                }
                .toMap()
        val own = scores[actor] ?: return ordinary
        val lead = scores.maxOfOrNull { it.value } ?: return ordinary
        if (lead - own !in 1..config.maximumTrailingPoints) return ordinary
        val marketOwners =
            view.board
                .mapNotNull { province ->
                    province.marker?.owner?.let { province.id to it }
                }
                .toMap()
        val originalDefender = marketOwners[original.province] ?: return ordinary
        val rivalScore = scores[originalDefender] ?: return ordinary
        if (rivalScore >= lead) return ordinary

        val offers =
            view.expansionOffers
                .filter { offer ->
                    offer.primaryAction == ExpansionAction.ATTACK && offer.blockers.isEmpty()
                }
                .associateBy { it.destination }
        val originals = observation.candidates.associateBy { it.command }
        fun action(province: ProvinceId): Attack? {
            val offer = offers[province] ?: return null
            val command = BoardCommand.Attack(province).toText()
            val candidate = originals[command] ?: return null
            return Attack(
                command = command,
                cost = candidate.tokenCost,
                odds = offer.competitionWinProbability ?: return null,
                denial = candidate.features[BotEconomicFeature.COMMODITY_DENIAL.key] ?: 0.0,
            )
        }
        val baseline = action(original.province) ?: return ordinary
        return offers.keys
            .asSequence()
            .filter { province -> marketOwners[province]?.let { scores[it] == lead } == true }
            .mapNotNull(::action)
            .filter { move ->
                move.cost <= baseline.cost + 2 &&
                    move.odds >= baseline.odds - .15 &&
                    (!config.requireGreaterCommodityDenial || move.denial >= baseline.denial + 5)
            }
            .maxWithOrNull(
                compareBy<Attack> { it.odds }
                    .thenByDescending { it.cost }
                    .thenBy { it.denial }
                    .thenByDescending { it.command }
            )
            ?.command ?: ordinary
    }

    private data class Attack(
        val command: String,
        val cost: Int,
        val odds: Double,
        val denial: Double,
    )
}
