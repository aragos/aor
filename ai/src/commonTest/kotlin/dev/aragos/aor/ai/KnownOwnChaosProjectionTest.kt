package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.MiseryStep
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Constructed public states only; no human snapshots, random worlds or game continuations. */
class KnownOwnChaosProjectionTest {
    private val actor = PlayerId("p3")

    private fun fixture(card: String = "religious-strife", protected: Boolean = false): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { state, (player, capital) ->
                state.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, players.first().id),
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { i, p ->
                        p.id to
                            PlayerState(
                                player = p,
                                capital = capitals[i],
                                cash = 100,
                                misery =
                                    if (p.id == actor) MiseryStep.THOUSAND else MiseryStep.ZERO,
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.PATRONAGE, 1))
                                    ),
                                hand =
                                    if (p.id == actor) {
                                        listOf(
                                            HandCard("danger", CardData.definition(card)),
                                            HandCard("gold", CardData.definition("gold-3")),
                                        )
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            effects =
                CardTurnEffects(enlightenedRulers = if (protected) setOf(actor) else emptySet()),
            flow =
                TurnFlow(
                    actor = actor,
                    cursor = 2,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }

    private fun encode(state: GameState): CardObservation {
        val ordinary = BotObservations.from(state, actor)
        return CardObservationEncoder.from(
            state.viewForPlayer(actor),
            actor,
            ordinary,
            ordinary.candidates.first().command,
        )
    }

    private fun excluded(observation: CardObservation): List<String> {
        val n = observation.actions.size
        val result =
            KnownOwnChaosSupport.card(
                observation,
                CardPolicyEvaluation(List(n) { 0.0 }, List(n) { 1.0 / n }, .5),
                .1,
            )
        return result.excludedIndices.map { observation.actions[it].command }
    }

    @Test
    fun authoritativeStrifeOwnChaosIsExcludedWhileGoldAndPassRemain() {
        val state = fixture()
        val observation = encode(state)
        val after = state.step(TextCodec.parse("p3: play danger"))

        assertEquals(listOf("play danger"), excluded(observation))
        assertTrue(observation.actions.any { it.command == "pass" })
        assertTrue(observation.actions.any { it.command == "play gold commodity gold" })
        assertEquals(MiseryStep.CHAOS, after.playerState(actor).misery)
        assertEquals(
            1.0,
            observation.actions
                .single { it.command == "play danger" }
                .features["effect.role.0.chaos"],
        )
    }

    @Test
    fun existingImmunityPreventsExclusion() {
        val state = fixture(protected = true)

        assertEquals(emptyList(), excluded(encode(state)))
        assertEquals(
            MiseryStep.THOUSAND,
            state.step(TextCodec.parse("p3: play danger")).playerState(actor).misery,
        )
    }

    @Test
    fun warRetainsUncertainOwnOutcome() {
        val observation = encode(fixture(card = "war"))

        assertEquals(emptyList(), excluded(observation))
        assertTrue(
            observation.actions
                .filter { it.command.startsWith("play danger ") }
                .all { it.features["effect.role.0.miseryUncertain"] == 1.0 }
        )
    }

    @Test
    fun compulsorySingleStrifeRemainsPlayable() {
        val initial = fixture()
        val state =
            initial.copy(
                stage = Stage.FINAL_CARD_PLAY,
                endingTriggered = true,
                players =
                    initial.players.mapValues { (id, p) ->
                        if (id == actor) p.copy(hand = p.hand.filter { it.reference == "danger" })
                        else p
                    },
            )
        val observation = encode(state)

        assertEquals(listOf("play danger"), observation.actions.map(CardAction::command))
        assertEquals(emptyList(), excluded(observation))
    }

    @Test
    fun rivalChaosDoesNotBecomeOwnChaosWhenActorIsThirdSeat() {
        val initial = fixture()
        val state =
            initial.copy(
                players =
                    initial.players.mapValues { (id, p) ->
                        p.copy(
                            misery =
                                if (id == PlayerId("p1")) MiseryStep.THOUSAND else MiseryStep.ZERO
                        )
                    }
            )
        val observation = encode(state)

        assertEquals(emptyList(), excluded(observation))
        assertFalse(
            observation.actions
                .single { it.command == "play danger" }
                .features
                .containsKey("effect.role.0.chaos")
        )
        assertTrue(
            observation.actions
                .single { it.command == "play danger" }
                .features
                .any { (key, value) -> key.endsWith(".chaos") && value == 1.0 }
        )
    }
}
