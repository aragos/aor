package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import dev.aragos.aor.ai.TokenNeighborAdmission
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented authority snapshots; no game execution, inference or scientific random draws. */
class SimulationTokenQuotesTest {
    @Test
    fun constructedAuthorityAdmissionExportsTheExactOwnerOnlyNativeWire() {
        val state = state()
        val scope = scope(state)
        scope.capability.quote(3)
        val diagnostics = RobustTokenDiagnostics(0, recordTokenAdmissions = true)
        diagnostics.chosen(
            SimulationDecision(
                0,
                0,
                0,
                state.viewForPlayer(actor),
                BotObservations.from(state, actor),
            ),
            "buy-tokens 3",
        )
        diagnostics.admitted(scope.admit("buy-tokens 3"))
        val value = diagnostics.result()
        assertThat(value["controlledUnofferedTokenBidCounts"]).isEqualTo(mapOf("3" to 1))
        val target = Path.of("build/test-fixtures/local-token-quote-admission.json")
        Files.createDirectories(target.parent)
        PurchaseJson.mapper.writeValue(target.toFile(), value)
    }

    @Test
    fun aNegativeQuotedNeighborPaysCashWithoutReceivingTokens() {
        val scope = scope(state())
        scope.capability.quote(-3)
        val accepted = scope.admit("buy-tokens -3")
        assertThat(accepted.quote.cost).isEqualTo(3)
        assertThat(accepted.quote.tokensReceived).isEqualTo(0)
        assertThat(accepted.quote.cashAfter).isEqualTo(7)
        assertThat(accepted.quote.stockAfter).isEqualTo(2)
        assertThat(accepted.quote.poolAfter).isEqualTo(0)
    }

    private val actor = PlayerId("p1")
    private val quoted =
        LocalTokenNeighborMixture(.5, 1, TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS)

    private fun state(otherCommitment: Int? = null): GameState {
        val ids = (1..4).map { PlayerId("p$it") }
        val capitals = listOf("paris", "genoa", "barcelona", "venice")
        val board =
            ids.zip(capitals).fold(BoardState()) { previous, (id, capital) ->
                previous.placeMarker(ProvinceId(capital), id).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, actor),
            stage = Stage.TOKEN_PURCHASE,
            turnNumber = 1,
            board = board,
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 10,
                                stockTokens = 2,
                                poolTokens = 0,
                                tokenPurchase = if (index == 1) otherCommitment else null,
                            )
                    }
                    .toMap(),
            flow = TurnFlow(phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }

    private fun scope(state: GameState, mixture: LocalTokenNeighborMixture = quoted) =
        checkNotNull(
            SimulationTokenQuotes.create(
                state,
                actor,
                state.viewForPlayer(actor),
                BotObservations.from(state, actor),
                mixture,
            )
        ) {
            "A constructed quoted token scope must be available."
        }

    @Test
    fun exactQuotedCommandRetainsOriginalInputAndAcceptedOwnerTrace() {
        val state = state()
        val original = BotObservations.from(state, actor)
        val scope = scope(state)
        val quote = scope.capability.quote(3)

        val accepted = scope.admit("buy-tokens 3")

        assertThat(original.candidates.map { it.command }).doesNotContain("buy-tokens 3")
        assertThat(accepted.original).isEqualTo(original)
        assertThat(accepted.accepted.candidates.dropLast(1)).isEqualTo(original.candidates)
        assertThat(accepted.accepted.candidates.last()).isEqualTo(accepted.candidate)
        assertThat(accepted.candidate.command).isEqualTo("buy-tokens 3")
        assertThat(accepted.candidate.cashCost).isEqualTo(3)
        assertThat(accepted.acceptedView.tokenPurchaseOffers.last()).isEqualTo(quote)
        assertThat(quote.tokensReceived).isEqualTo(2)
        assertThat(quote.cashAfter).isEqualTo(7)
        val next = state.step(TextCodec.parseAction(accepted.command, state, actor))
        assertThat(next.playerState(actor).tokenPurchase).isEqualTo(3)
    }

    @Test
    fun anUnofferedCommandWithoutItsExactRequestedQuoteIsRejected() {
        val scope = scope(state())
        scope.capability.quote(3)
        assertFailsWith<IllegalArgumentException> { scope.admit("buy-tokens 4") }
    }

    @Test
    fun theQuoteCapabilityCannotAuthorizeNonlocalLegalBids() {
        assertFailsWith<IllegalArgumentException> { scope(state()).capability.quote(6) }
    }

    @Test
    fun theQuoteCapabilityRejectsUnaffordableNeighborsAtTheCashEndpoint() {
        assertFailsWith<dev.aragos.aor.RuleViolation> { scope(state()).capability.quote(11) }
    }

    @Test
    fun offeredOnlyAndZeroDepartureLeaveTheOldAdmissionGuardInPlace() {
        val state = state()
        val observation = BotObservations.from(state, actor)
        val view = state.viewForPlayer(actor)
        assertThat(
                SimulationTokenQuotes.create(
                    state,
                    actor,
                    view,
                    observation,
                    quoted.copy(admission = TokenNeighborAdmission.OFFERED_ONLY),
                )
            )
            .isNull()
        assertThat(
                SimulationTokenQuotes.create(
                    state,
                    actor,
                    view,
                    observation,
                    quoted.copy(departureProbability = 0.0),
                )
            )
            .isNull()
    }

    @Test
    fun opponentSealedAmountsCannotInfluenceNeighborQuotesOrMixtureProbabilities() {
        val worlds = listOf(state(-7), state(9))
        val observations = worlds.map { BotObservations.from(it, actor) }
        val distributions =
            worlds.zip(observations).map { (state, observation) ->
                LocalTokenNeighborPolicy.distribution(
                    observation,
                    observation.candidates.map {
                        if (it.command == "buy-tokens 2") 1.0 else 0.0
                    },
                    quoted,
                    scope(state).capability,
                )
            }
        assertThat(observations[0]).isEqualTo(observations[1])
        assertThat(distributions[0]).isEqualTo(distributions[1])
    }
}
