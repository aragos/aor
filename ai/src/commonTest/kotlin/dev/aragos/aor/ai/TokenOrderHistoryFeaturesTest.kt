package dev.aragos.aor.ai

import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenOrderHistoryFeaturesTest {
    private val seats = (1..4).map { PlayerId("p$it") }
    private val seat = seats[1]
    private val view =
        GameState(
                GameHeader(seats.map { Player(it, it.value) }, creatorId = seat),
                turnNumber = 3,
                stage = Stage.TOKEN_PURCHASE,
            )
            .let { state ->
                val capitals = seats.zip(state.catalog.capitals).toMap()
                state.copy(
                    players =
                        state.players.mapValues { (player, current) ->
                            current.copy(capital = capitals[player])
                        }
                )
            }
            .viewForDecision(seat)

    @Test
    fun candidateFeaturesUseSignedRevealRowsAndKeepTieBounds() {
        val history = listOf(seats.zip(listOf(9, 3, 5, 11)).toMap())
        val projected = TokenOrderHistoryFeatures(view.copy(pastTokenPurchases = history), seat)

        assertEquals(
            mapOf(
                "bidOrder.role.1.tie" to 1.0,
                "bidOrder.role.2.before" to 1.0,
                "bidOrder.role.3.before" to 1.0,
                "bidOrder.bestBox" to 1.0 / 6.0,
                "bidOrder.worstBox" to 3.0 / 6.0,
            ),
            projected.action(5),
        )
    }

    @Test
    fun extensionPreservesBaseCommandsAndSeparatesDifferentPublicHistories() {
        val base =
            TerritoryObservation(
                TerritoryEncoder.TOKEN_2.version,
                mapOf("role.0.cash" to 0.2),
                listOf(
                    TerritoryAction("buy-tokens 10", mapOf("amount" to 10 / 36.0)),
                    TerritoryAction("buy-tokens 9", mapOf("amount" to 9 / 36.0)),
                ),
                baselineIndex = 0,
            )
        fun encoded(rivalBid: Int): TerritoryObservation {
            val history = listOf(seats.associateWith { rivalBid })
            return TokenObservationEncoder.withHistory(
                view.copy(pastTokenPurchases = history),
                seat,
                base,
            )
        }
        val followed = encoded(10)
        val tied = encoded(9)

        assertEquals(
            base.actions.map(TerritoryAction::command),
            followed.actions.map(TerritoryAction::command),
        )
        assertEquals(base.baselineIndex, followed.baselineIndex)
        assertEquals(9 / 36.0, followed.actions[1].features["amount"])
        assertEquals(1.0, followed.actions[1].features["bidOrder.role.1.before"])
        assertEquals(1.0, tied.actions[1].features["bidOrder.role.1.tie"])
    }
}
