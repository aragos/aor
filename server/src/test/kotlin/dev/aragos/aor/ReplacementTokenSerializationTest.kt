package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ReplacementTokenSerializationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun civilWarReplacementSerializesTheVictimsProvinceCauseAndCurrentMisery() {
        val before = game("civil-war", BoardState().placeMarker(ProvinceId("genoa"), p2).board)
        val awaiting = CardGame.play(before, p1, "event", CardChoice.PlayerChoice(p2))

        val serialized = json.writeValueAsString(awaiting.viewForPlayer(p3))
        val context = json.readTree(serialized).path("replacementToken")

        assertThat(context.path("player").asText()).isEqualTo("p2")
        assertThat(context.path("province").asText()).isEqualTo("genoa")
        assertThat(context.path("event").asText()).isEqualTo("CIVIL_WAR")
        assertThat(context.path("poolTokens").asInt()).isEqualTo(5)
        assertThat(context.path("poolCost").asInt()).isEqualTo(1)
        assertThat(context.path("poolAfter").asInt()).isEqualTo(4)
        assertThat(context.path("stockTokens").asInt()).isEqualTo(0)
        assertThat(context.path("misery").asInt()).isEqualTo(10)
        assertThat(context.path("civilWarLossPending").asBoolean()).isTrue()
        assertThat(serialized).doesNotContain("secret-opponent-card")
    }

    @Test
    fun queuedReplacementWithoutPoolSerializesNoPoolQuoteAndAnActionableDecline() {
        val rome = ProvinceId("rome")
        val board =
            BoardState().placeMarker(rome, p2).board.placeMarker(ProvinceId("florence"), p2).board
        val before = game("black-death", board, pool = 1)
        val area = checkNotNull(ProvinceData.definition(rome).area) { "Rome requires an area." }
        val awaiting = CardGame.play(before, p1, "event", CardChoice.AreaChoice(area))
        val next = CardGame.replaceEventToken(awaiting, p2, usePool = true)

        val context =
            json.readTree(json.writeValueAsString(next.viewForPlayer(p2))).path("replacementToken")
        val choices = json.readTree(json.writeValueAsString(next.legalChoices(p2))).path("commands")

        assertThat(context.path("event").asText()).isEqualTo("BLACK_DEATH")
        assertThat(context.path("poolTokens").asInt()).isEqualTo(0)
        assertThat(context.path("poolAfter").isNull).isTrue()
        assertThat(choices.map { it.asText() }).containsExactly("replace-event-token none")
    }

    private fun game(card: String, board: BoardState, pool: Int = 5): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            board = board,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                stockTokens = if (player.id == p1) 31 else 0,
                                poolTokens = pool,
                                hand =
                                    when (player.id) {
                                        p1 -> listOf(HandCard("event", CardData.definition(card)))
                                        p2 ->
                                            listOf(
                                                HandCard(
                                                    "secret-opponent-card",
                                                    CardData.definition("charlemagne"),
                                                )
                                            )
                                        else -> emptyList()
                                    },
                            )
                    }
                    .toMap(),
        )
    }
}
