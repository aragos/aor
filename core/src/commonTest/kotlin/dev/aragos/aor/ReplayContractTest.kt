package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Shared corpus is executed on JVM and JavaScript; no client replay is implied. */
class ReplayContractTest {
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
    private val header = GameHeader(players, players.first().id)

    @Test
    fun invalidOptionFragmentsAreNeverTrustedEntityReferences() {
        for (command in
            listOf(
                "set-option venice standard",
                "set-option map venice",
                "set-option deck-modification venice",
            )) {
            val failure = assertFailsWith<RuleViolation> { TextCodec.parseCommand(command) }

            assertEquals("UNSUPPORTED_OPTION", failure.diagnostic.code)
            assertEquals(emptyList(), failure.diagnostic.entityIds)
            assertEquals(emptyList(), failure.diagnostic.entities)
        }
    }

    @Test
    fun canonicalCommandsRoundTripOnEveryTarget() {
        val commands =
            listOf(
                "p1: bid-capital 17",
                "p2: buy-tokens -3",
                "p3: place 2 granada",
                "p1: set-option initial-card commodity-only",
                "p2: swap-order p1",
            )
        assertEquals(commands, commands.map { TextCodec.parse(it).toText() })
    }

    @Test
    fun recordedOpeningReplaysToTheSameOrderingAndDecision() {
        val configuration =
            RuleOptions().configurationCommands().map { Entry.Action(players.first().id, it) }
        val entries = mutableListOf<Entry>()
        var state = GameState(header)
        fun accept(entry: Entry) {
            entries.add(entry)
            state = state.step(entry)
        }
        configuration.forEach(::accept)
        accept(Entry.Action(players.first().id, Command.Start))
        repeat(10) {
            when (val need = (state.prompts().singleOrNull() as? Prompt.System)?.need) {
                is SystemNeed.Shuffle -> accept(Entry.Shuffle(need.cards))
                is SystemNeed.Draw ->
                    accept(GameOutcome.Draw(need.player, need.reference, need.card))
                else -> Unit
            }
        }
        players.forEachIndexed { index, player ->
            accept(Entry.Action(player.id, Command.BidCapital(3 - index)))
        }
        val restored = replay(header, entries.map(Entry::toText))
        assertEquals(Stage.CAPITAL_CHOICE, restored.stage)
        assertEquals(players.map(Player::id), restored.capitalOrder)
        assertEquals(state, restored)
        assertEquals(
            state.legalChoices(players.first().id),
            restored.legalChoices(players.first().id),
        )
    }
}
