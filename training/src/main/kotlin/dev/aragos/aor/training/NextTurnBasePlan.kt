package dev.aragos.aor.training

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation

/** A present legal placement, plus one market reachable after surviving pieces are flipped. */
internal data class NextTurnBasePlan(
    val currentPlacement: String,
    val target: ProvinceId,
    val additionalMarkets: Int,
    val immediateCost: Int,
) {
    companion object {
        fun select(
            state: GameState,
            actor: PlayerId,
            view: BotObservation,
            defaultCommand: String,
        ): NextTurnBasePlan? {
            if (view.availableTokens < 4) return null
            val public = state.viewForDecision(actor)
            if (public.endingTriggered) return null
            val presentOffers = public.expansionOffers.associateBy { it.destination }
            val candidatePlaces =
                view.candidates
                    .asSequence()
                    .filter { it.tokenCost in 1..2 && it.command != defaultCommand }
                    .mapNotNull { action ->
                        if (!action.command.startsWith("place ")) return@mapNotNull null
                        val parsed =
                            TextCodec.parseCommand(action.command) as? BoardCommand.Place
                                ?: return@mapNotNull null
                        val quote = presentOffers[parsed.province] ?: return@mapNotNull null
                        if (quote.uncertain || state.board.hasPresence(actor, parsed.province)) {
                            return@mapNotNull null
                        }
                        parsed
                    }
                    .distinctBy { it.province }
                    .take(8)
                    .toList()
            if (candidatePlaces.isEmpty()) return null

            // This projection changes only piece sides. The continuation applies real Income,
            // draws, contested actions, purchases and later expansion in the recorded rules.
            val before = state.copy(board = state.board.flipWhitePieces()).viewForDecision(actor)
            val unopened =
                before.expansionInspections
                    .filter { quote ->
                        !quote.reachable &&
                            quote.establishesMarker &&
                            !quote.uncertain &&
                            quote.primaryAction == ExpansionAction.PLACE &&
                            quote.requiredTokens in 2..7
                    }
                    .map { it.destination }
                    .toSet()
            if (unopened.isEmpty()) return null

            return candidatePlaces
                .mapNotNull { place ->
                    val applied = state.step(TextCodec.parseAction(place.toText(), state, actor))
                    val simulated = applied.copy(board = applied.board.flipWhitePieces())
                    val after = simulated.viewForDecision(actor)
                    val opened =
                        after.expansionInspections.filter { quote ->
                            quote.destination in unopened &&
                                quote.reachable &&
                                !quote.uncertain &&
                                quote.primaryAction == ExpansionAction.PLACE
                        }
                    if (opened.size < 2) return@mapNotNull null
                    val target =
                        opened.minWith(compareBy({ it.requiredTokens }, { it.destination.value }))
                    NextTurnBasePlan(place.toText(), target.destination, opened.size, place.tokens)
                }
                .maxWithOrNull(
                    compareBy<NextTurnBasePlan> { it.additionalMarkets }
                        .thenByDescending { it.immediateCost }
                        .thenByDescending { it.currentPlacement }
                )
        }
    }
}

/** Acts on the planned destination only if the subsequent real turn still offers it peacefully. */
internal class NextTurnBaseContinuation(
    private val reference: SimulationController,
    private val target: ProvinceId,
    private val nextTurn: Int,
) : SimulationController by reference {
    var followedThrough = false
        private set

    override fun choose(decision: SimulationDecision): String? {
        if (
            !followedThrough &&
                decision.observation.decision == "expand" &&
                decision.view.turnNumber == nextTurn
        ) {
            val quote = decision.view.expansionOffers.singleOrNull { it.destination == target }
            if (
                quote != null &&
                    quote.primaryAction == ExpansionAction.PLACE &&
                    quote.establishesMarker &&
                    !quote.uncertain &&
                    quote.blockers.isEmpty()
            ) {
                val placement = BoardCommand.Place(quote.requiredTokens, target).toText()
                if (decision.observation.candidates.any { it.command == placement }) {
                    followedThrough = true
                    return placement
                }
            }
        }
        return reference.choose(decision)
    }
}
