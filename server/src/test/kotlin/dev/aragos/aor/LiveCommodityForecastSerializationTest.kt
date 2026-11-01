package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class LiveCommodityForecastSerializationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun initialSnapshotListsEveryCommodityWithoutForecastOwners() {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val initial = GameState(header = GameHeader(players, p1))

        val view = json.readTree(json.writeValueAsString(initial.viewForPlayer(p1)))
        val payouts = view.path("commodityPayouts").toList()

        assertThat(payouts.map { payout -> payout.path("commodity").asText() })
            .containsExactlyElementsIn(Commodity.entries.map(Commodity::name))
            .inOrder()
        assertThat(payouts.flatMap { payout -> payout.path("owners").toList() }).isEmpty()
        assertThat(payouts.flatMap { payout -> payout.path("adjustments").toList() }).isEmpty()
    }

    @Test
    fun competitionSnapshotPublishesTheNewWhiteOwnerBeforeIncome() {
        val before = state()
        val oldView = json.readTree(json.writeValueAsString(before.viewForPlayer(p3)))
        val pending = before.step(TextCodec.parse("p1: attack florence"))

        val won =
            pending.step(
                GameOutcome.Roll(
                    GameRollKind.COMPETITION,
                    listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
                )
            )
        val refreshed = json.readTree(json.writeValueAsString(won.viewForPlayer(p3)))
        val owner =
            refreshed
                .path("commodityPayouts")
                .single { it.path("commodity").asText() == "CLOTH" }
                .path("owners")
                .single()

        assertThat(
                oldView
                    .path("commodityPayouts")
                    .single { it.path("commodity").asText() == "CLOTH" }
                    .path("owners")
                    .single()
                    .path("player")
                    .asText()
            )
            .isEqualTo("p2")
        assertThat(refreshed.path("stage").asText()).isEqualTo("EXPANSION")
        assertThat(
                refreshed
                    .path("board")
                    .single { it.path("id").asText() == "florence" }
                    .path("marker")
                    .path("side")
                    .asText()
            )
            .isEqualTo("WHITE")
        assertThat(owner.path("player").asText()).isEqualTo("p1")
        assertThat(owner.path("marketCount").asInt()).isEqualTo(1)
        assertThat(owner.path("effectiveBox").asInt()).isEqualTo(1)
        assertThat(owner.path("payout").asInt()).isEqualTo(5)
    }

    private fun state(): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 1,
            board = BoardState().placeMarker(ProvinceId("florence"), p2).board,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                poolTokens = 20,
                                stockTokens = 16,
                            )
                    }
                    .toMap(),
        )
    }
}
