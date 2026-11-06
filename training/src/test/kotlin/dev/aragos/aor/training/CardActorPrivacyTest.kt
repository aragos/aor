package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test

class CardActorPrivacyTest {
    @Test
    fun changedOpponentHandAndDeckOrderCannotChangeControlledCardInputs() {
        CardCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
            val capitals = listOf("venice", "genoa", "barcelona")
            val actor = players[1].id
            val board =
                players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                    current.placeMarker(ProvinceId(capital), player.id).board
                }
            val state =
                GameState(
                    header = GameHeader(players, players.first().id),
                    stage = Stage.PLAY_CARDS,
                    deck = listOf("stone-1", "wool-1"),
                    turnNumber = 2,
                    board = board,
                    turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
                    players =
                        players
                            .mapIndexed { index, player ->
                                player.id to
                                    PlayerState(
                                        player,
                                        capital = capitals[index],
                                        cash = 100,
                                        hand =
                                            when (index) {
                                                0 ->
                                                    listOf(
                                                        HandCard(
                                                            "private",
                                                            CardData.definition("charlemagne"),
                                                        )
                                                    )
                                                1 ->
                                                    listOf(
                                                        HandCard(
                                                            "own",
                                                            CardData.definition("rashid-ad-din"),
                                                        )
                                                    )
                                                else -> emptyList()
                                            },
                                    )
                            }
                            .toMap(),
                    flow =
                        TurnFlow(
                            actor = actor,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                        ),
                )
            val other = state.playerState(players[0].id)
            val changed =
                state.copy(
                    deck = state.deck.reversed(),
                    players =
                        state.players +
                            (other.player.id to
                                other.copy(
                                    hand =
                                        listOf(
                                            HandCard("private", CardData.definition("marco-polo"))
                                        )
                                )),
                )
            val records = mutableListOf<CardDecisionRecord>()
            val controller =
                CardActorController(
                    input.baseline.model,
                    input.policy,
                    input.frozenPurchasePolicy.model,
                ) {
                    records.add(it)
                }
            fun choose(current: GameState) =
                controller.choose(
                    SimulationDecision(
                        10,
                        1,
                        71,
                        current.viewForPlayer(actor),
                        BotObservations.from(current, actor),
                    )
                )

            val originalCommand = choose(state)
            val changedCommand = choose(changed)

            assertThat(records[1]).isEqualTo(records[0])
            assertThat(changedCommand).isEqualTo(originalCommand)
            assertThat(records[0].observation.stateFeatures)
                .containsEntry("hand.rashid-ad-din", 1.0)
            assertThat(records[0].observation.stateFeatures).doesNotContainKey("hand.charlemagne")
        }
    }
}
