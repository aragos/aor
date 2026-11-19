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
    val chance: Double,
) {
    companion object {
        fun select(
            state: GameState,
            actor: PlayerId,
            observation: BotObservation,
            ordinary: String,
        ): CaravanAttackPlan? {
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
                            quote.primaryAction == ExpansionAction.PLACE &&
                            quote.establishesMarker &&
                            quote.requiredTokens in 2..6
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
                        quote.destination != current.province &&
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
                                offer.destination in closed &&
                                    offer.primaryAction == ExpansionAction.PLACE &&
                                    offer.establishesMarker &&
                                    !offer.uncertain &&
                                    offer.blockers.isEmpty()
                            }
                            .minWithOrNull(
                                compareBy({ it.requiredTokens }, { it.destination.value })
                            ) ?: return@mapNotNull null
                    CaravanAttackPlan(
                        attack,
                        access.destination,
                        quote.requiredTokens,
                        access.requiredTokens,
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
