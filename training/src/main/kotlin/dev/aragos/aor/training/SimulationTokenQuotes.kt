package dev.aragos.aor.training

import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TokenPurchaseOfferView
import dev.aragos.aor.TokenPurchaseProjection
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import dev.aragos.aor.ai.TokenNeighborAdmission
import dev.aragos.aor.ai.TokenNeighborQuote
import kotlin.math.abs

/** Revision-bound, actor-owned quote admission; this authority state never reaches a policy. */
internal class SimulationTokenQuotes
private constructor(
    private val state: GameState,
    private val actor: PlayerId,
    private val view: GameView,
    private val original: BotObservation,
    private val mixture: LocalTokenNeighborMixture,
) {
    private val accepted = mutableMapOf<Int, TokenPurchaseOfferView>()
    val capability = TokenNeighborQuote { amount ->
        require(
            original.candidates.any {
                abs(LocalTokenNeighborPolicy.amount(it.command).toLong() - amount) <= mixture.radius
            }
        ) {
            "An authority-quoted neighbor must remain within its declared local radius."
        }
        TokenPurchaseProjection.quote(state, actor, amount).also { accepted[amount] = it }
    }

    fun admit(command: String): SimulationTokenQuoteAdmission {
        val amount = LocalTokenNeighborPolicy.amount(command)
        val quote =
            requireNotNull(accepted[amount]) {
                "An unoffered token command requires its exact accepted authority quote."
            }
        require(quote == TokenPurchaseProjection.quote(state, actor, amount)) {
            "An accepted token quote must retain its common uncommitted authority revision."
        }
        val (quotedView, observation) =
            BotObservations.withQuotedTokenPurchase(view, original, quote)
        return SimulationTokenQuoteAdmission(
            command,
            quote,
            original,
            quotedView,
            observation,
            observation.candidates.single { it.command == command },
        )
    }

    companion object {
        fun create(
            state: GameState,
            actor: PlayerId,
            view: GameView?,
            observation: BotObservation,
            mixture: LocalTokenNeighborMixture?,
        ): SimulationTokenQuotes? {
            if (
                observation.decision != "buy-tokens" ||
                    mixture?.admission != TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS ||
                    mixture.departureProbability == 0.0
            )
                return null
            return SimulationTokenQuotes(
                state,
                actor,
                requireNotNull(view) {
                    "An opt-in token quote requires its original owner view."
                },
                observation,
                mixture,
            )
        }
    }
}
