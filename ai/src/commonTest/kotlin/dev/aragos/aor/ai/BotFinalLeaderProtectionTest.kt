package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Final-window proposal restrictions and authoritative consequences agree on JVM and JavaScript.
 */
class BotFinalLeaderProtectionTest {
    private val actor = PlayerId("p1")
    private val ids = listOf(actor, PlayerId("p2"), PlayerId("p3"))

    @Test
    fun forcedFinalLeaderPlayOffersOnlyFreeCommandsAndCanFinishTheGame() {
        val state = fixture()

        val observation = BotObservations.from(state, actor)
        val played = state.step(TextCodec.parse("p1: ${observation.candidates.first().command}"))

        assertEquals(
            listOf("play held", "play held protect 0"),
            observation.candidates.map { it.command },
        )
        assertEquals(Stage.FINISHED, played.stage)
        assertEquals(40, played.playerState(actor).cash)
    }

    @Test
    fun finalProtectionRemainsLegalInTheEngine() {
        val state = fixture()

        val played = state.step(TextCodec.parse("p1: play held protect 20"))

        assertEquals(20, played.playerState(actor).cash)
        assertEquals(40, state.viewForDecision(actor).cardOffers.single().protectionMax)
    }

    @Test
    fun positiveProtectionHintsCannotReenterTheFinalProposalList() {
        val state = fixture()
        val choices = state.legalChoices(actor)

        val observation =
            BotObservations.from(
                state.viewForDecision(actor),
                choices.copy(commands = choices.commands + "play held protect 20"),
                actor,
                "play-cards",
            )

        assertEquals(
            listOf("play held", "play held protect 0"),
            observation.candidates.map { it.command },
        )
    }

    @Test
    fun ordinaryCardPlayRetainsPositiveProtectionEvenWhenEndingHasTriggered() {
        val state = fixture(stage = Stage.PLAY_CARDS)

        val observation = BotObservations.from(state, actor)

        assertTrue(observation.candidates.any { it.command == "play held protect 20" })
        assertTrue(observation.candidates.any { it.command == "play held protect 40" })
    }

    @Test
    fun freeFinalLeaderPlayRetainsItsImmediatePrintedWordRebate() {
        val state =
            fixture(
                owned =
                    setOf(
                        AdvanceId.WRITTEN_RECORD,
                        AdvanceId.PRINTED_WORD,
                        AdvanceId.HUMAN_BODY,
                        AdvanceId.ENLIGHTENMENT,
                    )
            )

        val observation = BotObservations.from(state, actor)
        val plain = observation.candidates.single { it.command == "play held" }
        val played = state.step(TextCodec.parse("p1: ${plain.command}"))

        assertEquals(0, plain.cashCost)
        assertEquals(31.0, plain.features["cash"])
        assertEquals(70, played.playerState(actor).cash)
        assertEquals(
            listOf("play held", "play held protect 0"),
            observation.candidates.map { it.command },
        )
    }

    private fun fixture(
        stage: Stage = Stage.FINAL_CARD_PLAY,
        owned: Set<AdvanceId> = emptySet(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, actor),
            stage = stage,
            turnNumber = 2,
            endingTriggered = true,
            board = board,
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 40,
                                advances =
                                    if (id == actor) {
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    } else PlayerAdvances(),
                                hand =
                                    if (id == actor) {
                                        listOf(
                                            HandCard("held", CardData.definition("henry-oldenburg"))
                                        )
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            effects =
                CardTurnEffects(
                    leaders =
                        listOf(
                            CardLeaderInPlay(
                                LeaderId.ST_BENEDICT,
                                ids[1],
                                CardLeaderRole.DISPLAY,
                            )
                        )
                ),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
