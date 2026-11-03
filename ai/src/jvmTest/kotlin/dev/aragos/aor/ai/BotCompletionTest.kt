package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.DieRoll
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TextCodec
import kotlin.random.Random
import kotlin.test.Test

class BotCompletionTest {
    @Test
    fun supportedSeatCountsAndMapsCompleteWithAllPolicyDifficulties() {
        val finished =
            MapRule.entries.flatMap { map ->
                (3..6).map { count ->
                    complete(
                        count,
                        RuleOptions(
                            map = map,
                            initialCard = InitialCardRule.entries[count - 3],
                            deckModification = count % 2 == 0,
                            immediateMasterArtDiscard = count % 2 == 1,
                        ),
                        count * 17 + map.ordinal,
                    )
                }
            }

        assertThat(finished.map { it.stage }.toSet()).containsExactly(Stage.FINISHED)
        assertThat(finished.map { it.players.size }).containsAtLeast(3, 4, 5, 6)
    }

    @Test
    fun recordedBotCommandsAndAuthorityOutcomesReplayWithoutRunningThePolicy() {
        val header = header(3)
        val entries = mutableListOf<Entry>()
        val finished = complete(3, RuleOptions(), 923, entries)

        val replayed = entries.fold(GameState(header)) { state, entry -> state.step(entry) }

        assertThat(replayed).isEqualTo(finished)
    }

    private fun complete(
        count: Int,
        rules: RuleOptions,
        seed: Int,
        entries: MutableList<Entry> = mutableListOf(),
    ): GameState {
        val random = Random(seed)
        var state = GameState(header(count))
        for (command in rules.configurationCommands()) {
            val entry = Entry.Action(state.header.creatorId, command)
            state = state.step(entry)
            entries.add(entry)
        }
        repeat(8000) { step ->
            if (state.stage == Stage.FINISHED) {
                assertThat(
                        entries.filterIsInstance<Entry.Action>().any {
                            it.command is BoardCommand.Place
                        }
                    )
                    .isTrue()
                return state
            }
            val entry =
                when (val prompt = state.prompts().single()) {
                    is Prompt.System -> outcome(state, prompt.need, random)
                    else -> {
                        val seat =
                            when (prompt) {
                                is Prompt.SinglePlayer -> prompt.player
                                is Prompt.MultiPlayer -> prompt.players.first()
                                else -> error("The player prompt must identify its seat.")
                            }
                        val observation = BotObservations.from(state, seat)
                        val difficulty = BotDifficulty.entries[state.playerIds.indexOf(seat) % 3]
                        Entry.Action(
                            seat,
                            TextCodec.parseCommand(
                                BotPolicy.choose(observation, difficulty, seed + step)
                            ),
                        )
                    }
                }
            try {
                state = state.step(entry)
            } catch (failure: Exception) {
                throw AssertionError("Rejected ${entry.toText()} in ${state.stage}", failure)
            }
            entries.add(entry)
        }
        error("A bot game must finish within 8000 recorded entries.")
    }

    private fun outcome(state: GameState, need: SystemNeed, random: Random): Entry =
        when (need) {
            is SystemNeed.Shuffle -> Entry.Shuffle(need.cards.shuffled(random))
            is SystemNeed.Draw -> GameOutcome.Draw(need.player, need.reference, need.card)
            is SystemNeed.GameRoll ->
                GameOutcome.Roll(
                    need.kind,
                    List(need.diceCount) { DieRoll(random.nextInt(1, 7)) },
                    need.players,
                )
            is SystemNeed.Trade ->
                GameOutcome.Trade(
                    need.owner,
                    need.recipient,
                    state.players[need.owner]?.hand?.random(random)?.reference
                        ?: error("A random trade requires the authoritative owner's held card."),
                )
        }

    private fun header(count: Int): GameHeader {
        val players = (1..count).map { Player(PlayerId("p$it"), "Player $it") }
        return GameHeader(players, players.first().id)
    }
}
