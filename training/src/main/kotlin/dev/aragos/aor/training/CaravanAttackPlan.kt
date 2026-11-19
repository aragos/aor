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

/** A legal attack whose success would open an otherwise unreachable same-turn Caravan market. */
internal data class CaravanAttackPlan(
    val attack: BoardCommand.Attack,
    val destination: ProvinceId,
    val firstCost: Int,
    val secondCost: Int,
    val secondAction: ExpansionAction,
    val chance: Double,
    val secondChance: Double,
) {
    /** Expected markets captured per expected token committed, from the original decision. */
    private val marketEfficiency: Double
        get() = chance * (1.0 + secondChance) / (firstCost + chance * secondCost)

    data class Choices(val ordinary: CaravanAttackPlan, val efficient: CaravanAttackPlan)

    companion object {
        fun select(
            state: GameState,
            actor: PlayerId,
            observation: BotObservation,
            ordinary: String,
        ): Choices? {
            val current = TextCodec.parseCommand(ordinary) as? BoardCommand.Attack ?: return null
            if (!state.playerState(actor).advances.owns(AdvanceId.CARAVAN)) return null
            if (observation.availableTokens < 7) return null
            val public = state.viewForDecision(actor)
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
            val branches =
                public.expansionOffers
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
                        val chance =
                            requireNotNull(quote.competitionWinProbability) {
                                "A shortlisted attack must have a quoted dice probability."
                            }
                        successful.expansionOffers
                            .filter { offer ->
                                offer.destination in closed && offer.admitsPlannedMarket()
                            }
                            .mapNotNull followUp@{ access ->
                                val secondChance =
                                    if (access.primaryAction == ExpansionAction.ATTACK) {
                                        access.competitionWinProbability ?: return@followUp null
                                    } else 1.0
                                CaravanAttackPlan(
                                    attack,
                                    access.destination,
                                    quote.requiredTokens,
                                    access.requiredTokens,
                                    access.primaryAction,
                                    chance,
                                    secondChance,
                                )
                            }
                            .takeIf { it.isNotEmpty() }
                    }
                    .toList()
            val ordinary =
                branches
                    .map { paths ->
                        paths.minWith(compareBy({ it.secondCost }, { it.destination.value }))
                    }
                    .maxWithOrNull(
                        compareBy<CaravanAttackPlan> { it.chance }
                            .thenByDescending { it.firstCost + it.secondCost }
                            .thenByDescending { it.attack.province.value }
                    ) ?: return null
            val efficient =
                branches
                    .asSequence()
                    .flatten()
                    .maxWith(
                        compareBy<CaravanAttackPlan> { it.marketEfficiency }
                            .thenBy { it.chance }
                            .thenByDescending { it.attack.province.value }
                            .thenByDescending { it.destination.value }
                    )
            return Choices(ordinary, efficient)
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
