package dev.aragos.aor

import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import dev.aragos.aor.ai.TokenNeighborAdmission
import dev.aragos.aor.ai.TokenNeighborQuote
import kotlin.math.abs

/**
 * One proposal's authority capability. Policies receive own purchase quotes, never [GameState].
 * Only commands actually quoted against this immutable revision may extend offered admission.
 */
internal class ServerTokenNeighborQuotes(
    private val state: GameState,
    private val seat: PlayerId,
    private val original: BotObservation,
    private val mixture: LocalTokenNeighborMixture?,
) : TokenNeighborQuote {
    init {
        require(original.playerId == seat.value) {
            "A server token quote must retain the original deciding seat."
        }
    }

    private val enabled =
        mixture?.admission == TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS &&
            mixture.departureProbability > 0.0
    private val quotes = mutableMapOf<String, TokenPurchaseOfferView>()

    override fun quote(amount: Int): TokenPurchaseOfferView {
        if (
            !enabled ||
                state.stage != Stage.TOKEN_PURCHASE ||
                original.decision != "buy-tokens" ||
                original.candidates.none {
                    abs(LocalTokenNeighborPolicy.amount(it.command).toLong() - amount) <=
                        requireNotNull(mixture) { "Quoted neighbors require their bound mixture." }
                            .radius
                }
        ) {
            throw ServiceFailure(503, "BOT_POLICY_FAILED")
        }
        val quote = TokenPurchaseProjection.quote(state, seat, amount)
        quotes[Command.BuyTokens(quote.amount).toText()] = quote
        return quote
    }

    fun accepts(command: String): Boolean = enabled && command in quotes

    fun acceptedObservation(view: GameView, command: String): BotObservation {
        if (original.candidates.any { it.command == command }) return original
        val quote =
            requireNotNull(quotes[command]) {
                "An unoffered server proposal requires its exact authority quote."
            }
        return BotObservations.withQuotedTokenPurchase(view, original, quote).second
    }
}
