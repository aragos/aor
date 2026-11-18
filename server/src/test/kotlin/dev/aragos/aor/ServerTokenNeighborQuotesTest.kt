package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.TokenNeighborAdmission
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed authority quotes only; no policy inference, chance streams or complete games. */
class ServerTokenNeighborQuotesTest {
    @Test
    fun aLegalUnquotedCommandCannotBypassOfferedAdmission() {
        val quotes = ServerTokenNeighborQuotes(state(), actor, observation(), mixture())

        assertThat(quotes.accepts("buy-tokens 2")).isFalse()
    }

    @Test
    fun anAuthoritativeQuoteAdmitsOnlyItsExactCanonicalCommand() {
        val quotes = ServerTokenNeighborQuotes(state(), actor, observation(), mixture())

        val quote = quotes.quote(2)

        assertThat(quote).isEqualTo(TokenPurchaseOfferView(2, 2, 1, 3, 0, 1))
        assertThat(quotes.accepts("buy-tokens 2")).isTrue()
        assertThat(quotes.accepts("buy-tokens +2")).isFalse()
        assertThat(quotes.accepts("buy-tokens 3")).isFalse()
    }

    @Test
    fun quotedAdmissionCannotExceedTheActorsOwnBudget() {
        val quotes = ServerTokenNeighborQuotes(state(), actor, observation(), mixture())

        assertFailsWith<RuleViolation> { quotes.quote(6) }

        assertThat(quotes.accepts("buy-tokens 6")).isFalse()
    }

    @Test
    fun anOfferedOnlyPolicyCannotRequestQuotedAdmission() {
        val quotes =
            ServerTokenNeighborQuotes(
                state(),
                actor,
                observation(),
                mixture(TokenNeighborAdmission.OFFERED_ONLY),
            )

        val failure = assertFailsWith<ServiceFailure> { quotes.quote(2) }

        assertThat(failure.code).isEqualTo("BOT_POLICY_FAILED")
    }

    @Test
    fun aLegacyPolicyCannotRequestQuotedAdmission() {
        val quotes = ServerTokenNeighborQuotes(state(), actor, observation(), null)

        assertFailsWith<ServiceFailure> { quotes.quote(2) }
    }

    @Test
    fun aZeroDepartureMixtureCannotBroadenOfferedAdmission() {
        val quotes =
            ServerTokenNeighborQuotes(
                state(),
                actor,
                observation(),
                mixture().copy(departureProbability = 0.0),
            )

        assertFailsWith<ServiceFailure> { quotes.quote(2) }
    }

    @Test
    fun anAffordableNonNeighborCannotBeQuoted() {
        val quotes = ServerTokenNeighborQuotes(state(), actor, observation(), mixture())

        assertFailsWith<ServiceFailure> { quotes.quote(3) }
    }

    @Test
    fun theCapabilityCannotBindAnotherSeatsObservation() {
        assertFailsWith<IllegalArgumentException> {
            ServerTokenNeighborQuotes(
                state(),
                actor,
                observation().copy(playerId = "p2"),
                mixture(),
            )
        }
    }

    @Test
    fun anExplicitMixtureCannotQuoteOutsideItsTokenWindow() {
        val quotes =
            ServerTokenNeighborQuotes(
                state().copy(stage = Stage.CONFIGURATION),
                actor,
                observation(),
                mixture(),
            )

        assertFailsWith<ServiceFailure> { quotes.quote(2) }
    }

    @Test
    fun anAlreadyCommittedActorCannotObtainANewPurchaseQuote() {
        val before = state()
        val committed =
            before.copy(
                players =
                    before.players.mapValues { (id, player) ->
                        if (id == actor) player.copy(tokenPurchase = 1) else player
                    }
            )
        val quotes = ServerTokenNeighborQuotes(committed, actor, observation(), mixture())

        assertFailsWith<RuleViolation> { quotes.quote(2) }
    }

    private fun mixture(
        admission: TokenNeighborAdmission = TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS
    ): LocalTokenNeighborMixture =
        LocalTokenNeighborMixture(departureProbability = .25, radius = 1, admission = admission)

    private fun observation(): BotObservation =
        BotObservation(
            playerId = actor.value,
            decision = "buy-tokens",
            context = emptyMap(),
            availableCash = 5,
            availableTokens = 0,
            candidates = listOf(0, 1, 5).map { BotCandidate("buy-tokens $it", emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun state(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val header = GameHeader(players, actor)
        return GameState(
            header,
            stage = Stage.TOKEN_PURCHASE,
            players = players.associate { it.id to PlayerState(it, cash = 5, stockTokens = 1) },
        )
    }

    companion object {
        private val actor = PlayerId("p1")
    }
}
