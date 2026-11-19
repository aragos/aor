package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TextCodec

/**
 * Treat an unused public declaration as an available tactical option on a single-defender attack.
 */
enum class CathedralAttackOdds {
    DICE_ONLY,
    DEFENSE,
    BOTH,
}

/**
 * Use public score points to bound how far behind the acting seat can be. The additional commodity
 * condition admits only targets with at least five dollars more projected public denial.
 * [maximumAdditionalTokens] and [maximumOddsDrop] bound a substitute's extra cost and reduction in
 * its quoted dice probability, with optional consideration of [cathedralOdds]. [earliestTurn]
 * counts normal game turns from one.
 */
data class LeadingRivalRetargetingConfig(
    val maximumTrailingPoints: Int,
    val requireGreaterCommodityDenial: Boolean,
    val maximumAdditionalTokens: Int = 2,
    val maximumOddsDrop: Double = .15,
    val earliestTurn: Int = 4,
    val cathedralOdds: CathedralAttackOdds = CathedralAttackOdds.DICE_ONLY,
) {
    init {
        require(maximumTrailingPoints in 1..10000) {
            "Retargeting requires a positive score gap of at most 10,000 points."
        }
        require(maximumAdditionalTokens in 0..6 && maximumOddsDrop in 0.0..0.35) {
            "An alternative attack can add at most six tokens and lose at most 35 points of dice probability."
        }
        require(earliestTurn in 2..8) {
            "Retargeting must begin between normal game turns two and eight."
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
        if ((view.turnNumber ?: 0) < config.earliestTurn || !ordinary.startsWith("attack ")) {
            return ordinary
        }
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
        val availableCathedral =
            if (config.cathedralOdds == CathedralAttackOdds.DICE_ONLY) emptyList()
            else
                view.cathedralPairs.filter {
                    !it.used && (it.owner == actor || it.opponent == actor)
                }
        fun action(province: ProvinceId): Attack? {
            val offer = offers[province] ?: return null
            val command = BoardCommand.Attack(province).toText()
            val candidate = originals[command] ?: return null
            val odds = offer.competitionWinProbability ?: return null
            val defender = marketOwners[province] ?: return null
            val effectiveOdds =
                when {
                    availableCathedral.any { it.owner == defender && it.opponent == actor } -> 0.0
                    config.cathedralOdds == CathedralAttackOdds.BOTH &&
                        availableCathedral.any { it.owner == actor && it.opponent == defender } ->
                        1.0
                    else -> odds
                }
            return Attack(
                command = command,
                cost = candidate.tokenCost,
                odds = effectiveOdds,
                denial = candidate.features[BotEconomicFeature.COMMODITY_DENIAL.key] ?: 0.0,
            )
        }
        val baseline = action(original.province) ?: return ordinary
        return offers.keys
            .asSequence()
            .filter { province -> marketOwners[province]?.let { scores[it] == lead } == true }
            .mapNotNull(::action)
            .filter { move ->
                move.cost <= baseline.cost + config.maximumAdditionalTokens &&
                    move.odds >= baseline.odds - config.maximumOddsDrop &&
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
