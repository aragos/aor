package dev.aragos.aor.training

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec

/** One nominated held card and its coupled expansion and next-turn play hypotheses. */
internal data class CommodityExpansionPlan(
    val reference: String,
    val commodities: Set<Commodity>,
    val currentPayout: Int,
) {
    companion object {
        fun capture(view: GameView, player: PlayerId, command: String): CommodityExpansionPlan {
            val play = TextCodec.parseCommand(command) as CardCommand.PlayCard
            val card = view.hand.single { it.reference == play.reference }
            val definition =
                CardEffectData.forCard(card.definition.id) as CardEffectSpec.CommodityChoices
            val selected = (play.choice as? CardChoice.CommodityChoice)?.commodity
            val choice =
                view.cardOffers
                    .single { it.reference == card.reference }
                    .choices
                    .single {
                        it.command == command
                    }
            return CommodityExpansionPlan(
                reference = card.reference,
                commodities = selected?.let(::setOf) ?: definition.choices,
                currentPayout =
                    choice.cashChanges
                        .filter { it.player == player }
                        .sumOf { it.after - it.before },
            )
        }
    }

    fun controller(
        ordinary: SimulationController,
        actor: PlayerId,
        originTurn: Int,
        redeem: Boolean,
    ): CommodityExpansionController =
        CommodityExpansionController(this, ordinary, actor, originTurn, redeem)
}

/** Only the ordinary legal candidate list can supply a market placement or the later card play. */
internal class CommodityExpansionController(
    private val plan: CommodityExpansionPlan,
    private val ordinary: SimulationController,
    private val actor: PlayerId,
    private val originTurn: Int,
    private val redeem: Boolean,
) : SimulationController by ordinary {
    var selectedMarkets: Int = 0
        private set

    var scheduledPlay: Boolean = false
        private set

    override fun controls(decision: String): Boolean =
        decision == "expand" || redeem && decision == "play-cards" || ordinary.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        val view = decision.view
        if (view.hand.any { it.reference == plan.reference }) {
            if (
                view.turnNumber == originTurn &&
                    view.stage == Stage.EXPANSION &&
                    decision.observation.decision == "expand" &&
                    selectedMarkets < 2
            ) {
                market(decision)?.let { command ->
                    selectedMarkets += 1
                    return command
                }
            }
            if (
                redeem &&
                    view.turnNumber == originTurn + 1 &&
                    view.stage == Stage.PLAY_CARDS &&
                    decision.observation.decision == "play-cards"
            ) {
                profitablePlay(decision)?.let { command ->
                    scheduledPlay = true
                    return command
                }
            }
        }
        return ordinary.takeIf { it.controls(decision.observation.decision) }?.choose(decision)
    }

    private fun market(decision: SimulationDecision): String? {
        val view = decision.view
        val wanted =
            view.catalog
                ?.provinces
                .orEmpty()
                .filter { province -> province.commodities.any { it in plan.commodities } }
                .map { it.id }
                .toSet()
        val valid =
            view.expansionOffers
                .filter { offer ->
                    offer.destination in wanted &&
                        offer.establishesMarker &&
                        !offer.uncertain &&
                        offer.blockers.isEmpty()
                }
                .associateBy { it.destination }
        if (valid.isEmpty()) return null
        return decision.observation.candidates
            .asSequence()
            .filter { it.family == "expansion" && it.command.startsWith("place ") }
            .filter { candidate ->
                val placement = TextCodec.parseCommand(candidate.command) as? BoardCommand.Place
                placement != null && valid[placement.province]?.requiredTokens == placement.tokens
            }
            .minWithOrNull(compareBy({ it.tokenCost }, { it.command }))
            ?.command
    }

    private fun profitablePlay(decision: SimulationDecision): String? {
        val allowed = decision.observation.candidates.map { it.command }.toSet()
        return decision.view.cardOffers
            .singleOrNull { it.reference == plan.reference }
            ?.choices
            .orEmpty()
            .filter { it.command in allowed }
            .mapNotNull { choice ->
                val payouts =
                    choice.cashChanges
                        .groupBy { it.player }
                        .mapValues { (_, changes) ->
                            changes.sumOf { it.after - it.before }
                        }
                val own = payouts[actor] ?: 0
                val rival = payouts.filterKeys { it != actor }.values.maxOrNull() ?: 0
                if (own >= plan.currentPayout + 8 && own > rival) choice.command to own - rival
                else null
            }
            .maxWithOrNull(compareBy({ it.second }, { it.first }))
            ?.first
    }
}
