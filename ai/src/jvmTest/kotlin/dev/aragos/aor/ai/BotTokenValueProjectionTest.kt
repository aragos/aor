package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.ContestedExpansionProspectView
import dev.aragos.aor.ExpansionForecastView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.PeacefulExpansionProspectView
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import kotlin.test.Test

class BotTokenValueProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val ids = listOf(p1, p2, PlayerId("p3"))

    @Test
    fun realTokenObservationsContainTheCompleteVersionedFeatureSet() {
        val observation = BotObservations.from(fixture(), p1)

        assertThat(BotTokenValue.available(observation)).isTrue()
        assertThat(observation.candidates.single { it.command == "buy-tokens 0" }.features)
            .containsEntry(key("cost"), 0.0)
        assertThat(observation.candidates.single { it.command == "buy-tokens -4" }.features)
            .containsEntry(key("signedAmount"), -0.125)
    }

    @Test
    fun extremeLegalCashAndSignedQuotesKeepEveryFeatureBounded() {
        val original = fixture()
        val state =
            original.copy(
                players =
                    original.players + (p1 to original.playerState(p1).copy(cash = Int.MAX_VALUE))
            )
        val view = state.viewForDecision(p1)
        val projection = BotTokenValueProjection(view, p1, 0.0)
        val quotes = view.tokenPurchaseOffers.filter { it.cost == Int.MAX_VALUE }

        val features = quotes.map(projection::features)

        assertThat(features).hasSize(2)
        assertThat(features.flatMap { it.values }.all { it.isFinite() && it in -16.0..16.0 })
            .isTrue()
        assertThat(features.map { it[key("signedAmount")] }).containsExactly(-16.0, 16.0)
    }

    @Test
    fun peacefulOpportunitySharesOverseasCapacity() {
        val original = fixture().viewForDecision(p1)
        val view =
            original.copy(
                expansionForecast =
                    ExpansionForecastView(
                        peacefulMarkets =
                            listOf(
                                peaceful("china", 5, overseas = true),
                                peaceful("india", 5, overseas = true),
                            ),
                        overseasSlots = 1,
                    )
            )
        val quote = view.tokenPurchaseOffers.single { it.amount == 10 }

        val features = BotTokenValueProjection(view, p1, 0.0).features(quote)

        assertThat(features[key("affordablePeaceful")]).isEqualTo(0.1)
        assertThat(features[key("peacefulCommitment")]).isEqualTo(5.0 / 32)
    }

    @Test
    fun peacefulOpportunitySharesMarkerStock() {
        val original = fixture().viewForDecision(p1)
        val view =
            original.copy(
                players =
                    original.players.map { player ->
                        if (player.id == p1) player.copy(boardMarkers = 24) else player
                    },
                expansionForecast =
                    ExpansionForecastView(
                        peacefulMarkets = listOf(peaceful("rome", 2), peaceful("naples", 3)),
                        overseasSlots = 0,
                    ),
            )
        val quote = view.tokenPurchaseOffers.single { it.amount == 10 }

        val features = BotTokenValueProjection(view, p1, 0.0).features(quote)

        assertThat(features[key("affordablePeaceful")]).isEqualTo(0.1)
        assertThat(features[key("peacefulCommitment")]).isEqualTo(2.0 / 32)
    }

    @Test
    fun contestedAccessIsDistinctFromAnEmptyExpansionForecast() {
        val original = fixture().viewForDecision(p1)
        val view =
            original.copy(
                expansionForecast =
                    ExpansionForecastView(
                        peacefulMarkets = emptyList(),
                        overseasSlots = 0,
                        contestedMarkets =
                            listOf(
                                ContestedExpansionProspectView(
                                    ProvinceId("rome"),
                                    requiredTokens = 5,
                                    shipping = false,
                                    usesOverseasSlot = false,
                                )
                            ),
                    )
            )
        val quote = view.tokenPurchaseOffers.single { it.amount == 10 }

        val features = BotTokenValueProjection(view, p1, 0.0).features(quote)

        assertThat(features[key("affordablePeaceful")]).isEqualTo(0.0)
        assertThat(features[key("affordableContested")]).isEqualTo(0.1)
    }

    @Test
    fun hiddenOpponentCardsAndDeckOrderCannotChangeTokenFeatures() {
        val original = fixture()
        val opponent = original.playerState(p2)
        val changed =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players +
                        (p2 to
                            opponent.copy(
                                hand = listOf(HandCard("private", CardData.definition("spice-3")))
                            )),
            )

        val before = BotObservations.from(original, p1)
        val after = BotObservations.from(changed, p1)

        assertThat(after.candidates.map { BotTokenValue.features(after, it) })
            .containsExactlyElementsIn(before.candidates.map { BotTokenValue.features(before, it) })
            .inOrder()
    }

    @Test
    fun aPendingSealedOpponentBidCannotChangeTokenFeatures() {
        val original = fixture()
        val changed =
            original.copy(
                players =
                    original.players + (p2 to original.playerState(p2).copy(tokenPurchase = -9))
            )

        val before = BotObservations.from(original, p1)
        val after = BotObservations.from(changed, p1)

        assertThat(after.candidates.map { BotTokenValue.features(after, it) })
            .containsExactlyElementsIn(before.candidates.map { BotTokenValue.features(before, it) })
            .inOrder()
    }

    @Test
    fun anExistingModelKeepsItsChoicesWhenTokenFeaturesAreAdded() {
        val observation = BotObservations.from(fixture(), p1)
        val withoutHead =
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(
                            features = candidate.features.filterKeys { !BotTokenValue.reserved(it) }
                        )
                    }
            )

        val choices = BotDifficulty.entries.map { BotPolicy.choose(observation, it, 17) }

        assertThat(choices)
            .containsExactlyElementsIn(
                BotDifficulty.entries.map { BotPolicy.choose(withoutHead, it, 17) }
            )
            .inOrder()
    }

    private fun key(name: String): String = "planning.tokenValue.v1.$name"

    private fun peaceful(
        province: String,
        tokens: Int,
        overseas: Boolean = false,
    ): PeacefulExpansionProspectView =
        PeacefulExpansionProspectView(
            ProvinceId(province),
            requiredTokens = tokens,
            shipping = overseas,
            usesOverseasSlot = overseas,
        )

    private fun fixture(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.TOKEN_PURCHASE,
            turnNumber = 2,
            board =
                ids.zip(capitals).fold(BoardState()) { board, (id, capital) ->
                    board.placeMarker(ProvinceId(capital), id).board
                },
            deck = listOf("cloth-wine", "spice-3"),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 100,
                                hand =
                                    if (id == p2) {
                                        listOf(
                                            HandCard("private", CardData.definition("cloth-wine"))
                                        )
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
