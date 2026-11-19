package dev.aragos.aor.training

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotEconomicFeature
import dev.aragos.aor.ai.BotObservation

/** Compare already legal full market attacks with similar immediate cost and odds. */
internal data class RivalMarketDenial(
    val actions: Map<String, String>,
    val facts: Map<String, Any>,
) {
    companion object {
        fun from(
            view: GameView,
            actor: PlayerId,
            observation: BotObservation,
            selected: String,
        ): RivalMarketDenial? {
            val scores =
                view.scores
                    .mapNotNull { score ->
                        score.total?.let { score.player to it }
                    }
                    .toMap()
            val ownScore = scores[actor] ?: return null
            val leader = scores.maxOfOrNull { it.value } ?: return null
            if (leader <= ownScore) return null
            val owners =
                view.board
                    .mapNotNull { province ->
                        province.marker?.owner?.let { province.id to it }
                    }
                    .toMap()
            val offers = view.expansionOffers.associateBy { it.destination }
            val attacks =
                observation.candidates.mapNotNull { candidate ->
                    if (!candidate.command.startsWith("attack ")) return@mapNotNull null
                    val action =
                        TextCodec.parseCommand(candidate.command) as? BoardCommand.Attack
                            ?: return@mapNotNull null
                    val defender = owners[action.province] ?: return@mapNotNull null
                    if (defender == actor || defender !in scores) return@mapNotNull null
                    val offer = offers[action.province] ?: return@mapNotNull null
                    if (
                        offer.primaryAction != ExpansionAction.ATTACK || offer.blockers.isNotEmpty()
                    ) {
                        return@mapNotNull null
                    }
                    val probability = offer.competitionWinProbability ?: return@mapNotNull null
                    Attack(
                        candidate.command,
                        defender,
                        candidate.tokenCost,
                        probability,
                        candidate.features[BotEconomicFeature.COMMODITY_DENIAL.key] ?: 0.0,
                    )
                }
            val ordinary = attacks.singleOrNull { it.command == selected } ?: return null
            val alternatives = attacks.filter {
                it.cost <= ordinary.cost + 2 && it.probability >= ordinary.probability - .15
            }
            val scoreboard =
                alternatives
                    .filter { scores[it.defender] == leader }
                    .maxWithOrNull(
                        compareBy<Attack> { it.probability }
                            .thenByDescending { it.cost }
                            .thenBy { it.denial }
                            .thenByDescending { it.command }
                    )
            val commodity =
                alternatives
                    .filter { it.denial >= ordinary.denial + 5.0 }
                    .maxWithOrNull(
                        compareBy<Attack> { it.denial }
                            .thenBy { it.probability }
                            .thenByDescending { it.cost }
                            .thenByDescending { it.command }
                    )
            val choices = buildMap {
                put("ordinary", ordinary)
                if (scoreboard != null && scores[ordinary.defender] != leader) {
                    put("score-leader", scoreboard)
                }
                if (commodity != null) put("commodity-pressure", commodity)
            }
            if (choices.size < 2) return null
            return RivalMarketDenial(
                actions = choices.mapValues { (_, attack) -> attack.command },
                facts =
                    mapOf(
                        "leaders" to scores.filterValues { it == leader }.keys.map(PlayerId::value),
                        "ownGap" to leader - ownScore,
                        "offers" to
                            choices.mapValues { (_, attack) ->
                                mapOf(
                                    "defender" to attack.defender.value,
                                    "tokens" to attack.cost,
                                    "winProbability" to attack.probability,
                                    "publicCommodityDenial" to attack.denial,
                                )
                            },
                    ),
            )
        }
    }

    private data class Attack(
        val command: String,
        val defender: PlayerId,
        val cost: Int,
        val probability: Double,
        val denial: Double,
    )
}
