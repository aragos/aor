package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.DieRoll
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameRollKind
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation

/**
 * A proposed attack and conditional same-turn follow-up. Costs count expansion tokens. The
 * probability is the initial attack's ordinary quoted odds and the route reveals no future roll.
 */
data class CaravanAttackPlan(
    val attack: BoardCommand.Attack,
    val destination: ProvinceId,
    val firstCost: Int,
    val secondCost: Int,
    val secondAction: ExpansionAction,
    val chance: Double,
) {
    companion object {
        const val VERSION = "single-caravan-route-1"

        internal fun select(
            state: GameState,
            actor: PlayerId,
            public: GameView,
            observation: BotObservation,
            ordinary: String,
        ): CaravanAttackPlan? {
            val current = TextCodec.parseCommand(ordinary) as? BoardCommand.Attack ?: return null
            if (!state.playerState(actor).advances.owns(AdvanceId.CARAVAN)) return null
            if (observation.availableTokens < 7) return null
            val reference =
                public.expansionOffers.firstOrNull { it.destination == current.province }
                    ?: return null
            val minimumChance = (reference.competitionWinProbability ?: return null) - .15
            val closed =
                public.expansionInspections
                    .filter { quote ->
                        !quote.reachable &&
                            quote.establishesMarker &&
                            quote.missingAccess.isEmpty() &&
                            quote.requiredTokens in 2..10
                    }
                    .map { it.destination }
                    .toSet()
            if (closed.isEmpty()) return null
            val adjacent = public.catalog?.landBorders.orEmpty()
            val gateways =
                adjacent
                    .flatMap { edge ->
                        listOfNotNull(
                            edge.first.takeIf { edge.second in closed },
                            edge.second.takeIf { edge.first in closed },
                        )
                    }
                    .toSet()
            val commands = observation.candidates.map { it.command }.toSet()
            return public.expansionOffers
                .asSequence()
                .filter { quote ->
                    quote.destination in gateways &&
                        quote.primaryAction == ExpansionAction.ATTACK &&
                        quote.blockers.isEmpty() &&
                        quote.requiredTokens <= reference.requiredTokens + 2 &&
                        quote.requiredTokens + 2 <= observation.availableTokens &&
                        (quote.competitionWinProbability ?: 0.0) >= maxOf(.45, minimumChance)
                }
                .sortedWith(compareBy({ it.requiredTokens }, { it.destination.value }))
                .take(6)
                .mapNotNull { quote ->
                    val attack = BoardCommand.Attack(quote.destination)
                    if (attack.toText() !in commands) return@mapNotNull null
                    val successful =
                        successfulAttack(state, actor, attack) ?: return@mapNotNull null
                    val access =
                        successful.expansionOffers
                            .filter { offer ->
                                offer.destination in closed && offer.admitsPlannedMarket()
                            }
                            .minWithOrNull(
                                compareBy({ it.requiredTokens }, { it.destination.value })
                            ) ?: return@mapNotNull null
                    CaravanAttackPlan(
                        attack,
                        access.destination,
                        quote.requiredTokens,
                        access.requiredTokens,
                        access.primaryAction,
                        requireNotNull(quote.competitionWinProbability) {
                            "A shortlisted attack must have a quoted dice probability."
                        },
                    )
                }
                .maxWithOrNull(
                    compareBy<CaravanAttackPlan> { it.chance }
                        .thenByDescending { it.firstCost + it.secondCost }
                        .thenByDescending { it.attack.province.value }
                )
        }

        /**
         * Resolve only the projected successful ordinary dice outcome. No draw or hidden hand is
         * sampled.
         */
        private fun successfulAttack(
            state: GameState,
            actor: PlayerId,
            attack: BoardCommand.Attack,
        ): GameView? {
            val pending = state.step(TextCodec.parseAction(attack.toText(), state, actor))
            val need =
                (pending.prompts().singleOrNull() as? Prompt.System)?.need as? SystemNeed.GameRoll
                    ?: return null
            if (need.kind != GameRollKind.COMPETITION) return null
            val won =
                pending.step(
                    GameOutcome.Roll(
                        need.kind,
                        listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
                        need.players,
                    )
                )
            val outcome = won.viewForDecision(actor)
            return outcome.takeIf { view ->
                view.board.any { it.id == attack.province && it.marker?.owner == actor }
            }
        }
    }
}

/** Execute one planned route per game, within the actual availability of the original turn. */
internal class CaravanRouteController(private val reference: SimulationController) :
    SimulationController by reference {
    var attempted = false
        private set

    var followedThrough = false
        private set

    private var pending: CaravanAttackPlan? = null
    private var plannedTurn: Int? = null

    override fun wantsCaravanForecast(): Boolean = !attempted

    override fun choose(decision: SimulationDecision): String? {
        val turn = decision.view.turnNumber
        if (decision.observation.decision != "expand" || turn == null || turn < 3) {
            return reference.choose(decision)
        }
        val followUp = pending
        pending = null
        if (followUp != null && turn == plannedTurn) {
            survivingRoute(decision, followUp)?.let {
                followedThrough = true
                return it
            }
        }
        val selected = reference.choose(decision) ?: return null
        if (attempted || !selected.startsWith("attack ")) return selected
        val plan = decision.caravanForecast?.invoke(selected) ?: return selected
        attempted = true
        pending = plan
        plannedTurn = turn
        return plan.attack.toText()
    }

    private fun survivingRoute(
        decision: SimulationDecision,
        plan: CaravanAttackPlan,
    ): String? {
        if (
            decision.view.board.none {
                it.id == plan.attack.province &&
                    it.marker?.owner?.value == decision.observation.playerId
            }
        ) {
            return null
        }
        val quote =
            decision.view.expansionOffers.firstOrNull { it.destination == plan.destination }
                ?: return null
        if (quote.primaryAction != plan.secondAction || !quote.admitsPlannedMarket()) return null
        val command =
            if (plan.secondAction == ExpansionAction.ATTACK) {
                BoardCommand.Attack(plan.destination).toText()
            } else {
                BoardCommand.Place(quote.requiredTokens, plan.destination).toText()
            }
        return command.takeIf { offer ->
            decision.observation.candidates.any { it.command == offer }
        }
    }
}

private fun dev.aragos.aor.ExpansionOfferView.admitsPlannedMarket(): Boolean =
    establishesMarker &&
        blockers.isEmpty() &&
        when (primaryAction) {
            ExpansionAction.PLACE -> !uncertain
            ExpansionAction.ATTACK -> (competitionWinProbability ?: 0.0) >= .65
            ExpansionAction.INSPECT -> false
        }
