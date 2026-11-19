package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test

class RepeatedUndercutBidTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val actor = players.first().id
    private val ordinary = "buy-tokens 18"
    private val earlier = "buy-tokens 16"
    private val observation =
        BotObservation(
            playerId = actor.value,
            decision = "buy-tokens",
            context = emptyMap(),
            availableCash = 40,
            availableTokens = 0,
            candidates =
                listOf(BotCandidate(ordinary, emptyMap()), BotCandidate(earlier, emptyMap())),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    @Test
    fun repeatedUndercuttingMakesAnEarlyCardPurchaseWorthBiddingFor() {
        val view = tokenView(lowBids = listOf(17, 17, 21, 17))

        val chosen = RepeatedUndercutBid.choose(view, observation, seed = 5, ordinary = ordinary)

        assertThat(chosen).isEqualTo(earlier)
    }

    @Test
    fun twoLowBidsFromARivalLeaveTheIncumbentBidAlone() {
        val view = tokenView(lowBids = listOf(17, 21, 17, 21))

        val chosen = RepeatedUndercutBid.choose(view, observation, seed = 5, ordinary = ordinary)

        assertThat(chosen).isEqualTo(ordinary)
    }

    private fun tokenView(lowBids: List<Int>): GameView =
        GameState(GameHeader(players, actor))
            .viewForPlayer(actor)
            .copy(
                stage = Stage.TOKEN_PURCHASE,
                turnNumber = 5,
                deckRemaining = 12,
                pastTokenPurchases =
                    lowBids.map { bid ->
                        players.map(Player::id).zip(listOf(18, bid, 19, 21)).toMap()
                    },
                expansionForecast = ExpansionForecastView(emptyList(), overseasSlots = 0),
                tokenPurchaseOffers =
                    listOf(14, 16, 18).map { bid ->
                        TokenPurchaseOfferView(
                            amount = bid,
                            cost = bid,
                            tokensReceived = bid,
                            cashAfter = 40 - bid,
                            stockAfter = 36 - bid,
                            poolAfter = bid,
                        )
                    },
            )
}
