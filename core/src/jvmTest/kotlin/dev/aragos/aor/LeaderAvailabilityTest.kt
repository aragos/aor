package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class LeaderAvailabilityTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun matchingLeaderIdentitiesIncludeAllPrintedEpochs() {
        val availability = state().viewForPlayer(p1).unplayedAdvanceLeaders
        val writtenRecord = availability.single { it.advance == AdvanceId.WRITTEN_RECORD }

        assertThat(availability).hasSize(26)
        assertThat(writtenRecord.epochs)
            .containsExactly(
                LeaderEpochView(
                    epoch = 1,
                    leaders =
                        listOf(
                            LeaderId.DIONYSUS_EXIGUUS,
                            LeaderId.RASHID_AD_DIN,
                            LeaderId.ST_BENEDICT,
                        ),
                ),
                LeaderEpochView(epoch = 2, leaders = emptyList()),
                LeaderEpochView(epoch = 3, leaders = emptyList()),
            )
            .inOrder()
    }

    @Test
    fun playingALeaderReducesItsAdvancesUnplayedAvailability() {
        val before = state()

        val after = CardGame.play(before, p1, "hand-1", CardChoice.None)

        assertThat(
                after
                    .viewForPlayer(p2)
                    .unplayedAdvanceLeaders
                    .single {
                        it.advance == AdvanceId.NATIONALISM
                    }
                    .epochs
                    .first()
                    .leaders
                    .size
            )
            .isEqualTo(0)
        assertThat(
                after
                    .viewForPlayer(p2)
                    .unplayedAdvanceLeaders
                    .single {
                        it.advance == AdvanceId.WRITTEN_RECORD
                    }
                    .epochs
                    .first()
                    .leaders
                    .size
            )
            .isEqualTo(3)
    }

    @Test
    fun publicLeaderPlayRemainsKnownAfterTurnEffectsExpire() {
        val played = CardGame.play(state(), p1, "hand-1", CardChoice.None)

        val nextTurn = played.copy(turnNumber = 2, effects = CardTurnEffects())

        assertThat(
                nextTurn
                    .viewForPlayer(p1)
                    .unplayedAdvanceLeaders
                    .single {
                        it.advance == AdvanceId.NATIONALISM
                    }
                    .epochs
                    .first()
                    .leaders
                    .size
            )
            .isEqualTo(0)
    }

    @Test
    fun privatelyDiscardingALeaderDoesNotExposeItsIdentityThroughAvailability() {
        val before = state().copy(stage = Stage.BUY_CARD)
        val owner = before.playerState(p1)
        val discardable =
            before.copy(
                players =
                    before.players +
                        (p1 to
                            owner.copy(
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.MASTER_ART, 1))
                                    )
                            ))
            )

        val discarded = TurnCommand.DiscardCard("hand-1").applyTo(discardable, p1)

        assertThat(discarded.viewForPlayer(p2).unplayedAdvanceLeaders)
            .isEqualTo(before.viewForPlayer(p2).unplayedAdvanceLeaders)
    }

    @Test
    fun movingAnUnplayedLeaderBetweenPrivateLocationsDoesNotChangeAvailability() {
        val held = state()
        val owner = held.playerState(p1)
        val inDeck =
            held.copy(
                deck = listOf("charlemagne"),
                players = held.players + (p1 to owner.copy(hand = emptyList())),
            )

        assertThat(held.viewForPlayer(p2).unplayedAdvanceLeaders)
            .isEqualTo(inDeck.viewForPlayer(p2).unplayedAdvanceLeaders)
    }

    private fun state(): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            turnNumber = 1,
            stage = Stage.PLAY_CARDS,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                hand =
                                    if (player.id == p1) {
                                        listOf(
                                            HandCard("hand-1", CardData.definition("charlemagne"))
                                        )
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
        )
    }
}
