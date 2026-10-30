package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TextCodecTest {
    @Test
    fun retiredSyntaxIsRejectedBeforeItCanBecomeAReplayEntry() {
        for (text in
            listOf(
                "return-card hand-1",
                "written-record",
                "written-record p2",
                "adjust-market grain shortage",
            )) {
            val failure =
                kotlin.test.assertFailsWith<RuleViolation> { TextCodec.parseCommand(text) }
            assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
        }
    }

    @Test
    fun playerCommandsHaveCanonicalText() {
        for (text in
            listOf(
                "set-option map standard",
                "set-option initial-card commodity-only",
                "set-option deck-modification disabled",
                "set-option deck-modification enabled",
                "start",
                "bid-capital 12",
                "choose-capital venice",
                "buy-tokens -2",
                "choose-turn-position 2",
            )) {
            assertThat(TextCodec.parseCommand(text).toText()).isEqualTo(text)
        }
    }

    @Test
    fun acceptedEntriesRoundTripWithoutChanges() {
        entries().forEach { text ->
            val entry = TextCodec.parse(text)

            assertThat(TextCodec.parse(TextCodec.print(entry))).isEqualTo(entry)
        }
    }

    @Test
    fun replayMatchesLiveTransitions() {
        val header =
            GameHeader(
                creatorId = PlayerId("p1"),
                players =
                    listOf(
                        Player(id = PlayerId("p1"), name = "Alice"),
                        Player(id = PlayerId("p2"), name = "Bob"),
                        Player(id = PlayerId("p3"), name = "Carol"),
                    ),
            )
        val entries = entries()
        val live =
            entries.fold(GameState(header)) { state, text ->
                state.step(TextCodec.parse(text))
            }

        assertThat(replay(header, entries)).isEqualTo(live)
    }

    @Test
    fun malformedCommandsAreRejected() {
        val malformed =
            listOf(
                "p2: start",
                "!shuffle anything",
                "start extra",
                "bid-capital 1.5",
                "bid-capital 9999999999999",
                "bid-capital",
                "choose-capital",
                "buy-tokens",
                "buy-tokens 1.5",
                "buy-tokens 2147483648",
                "buy-tokens -2147483649",
                "choose-turn-position",
                "choose-turn-position 999999999999999",
                "set-option rebuilt-marker-bonus",
                "set-option deck-modification enabled extra",
                "",
            )

        malformed.forEach { text ->
            val failure = assertFailsWith<RuleViolation> { TextCodec.parseCommand(text) }

            assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
        }
    }

    @Test
    fun unsupportedOptionsHaveASpecificDiagnostic() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseCommand("set-option epochs 3")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
    }

    @Test
    fun invalidRebuildingPoliciesHaveASpecificDiagnostic() {
        for (value in listOf("yes", "no", "true", "false", "INCLUDE", "EXCLUDE", "1")) {
            val failure =
                assertFailsWith<RuleViolation> {
                    TextCodec.parseCommand("set-option rebuilt-marker-bonus $value")
                }

            assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
        }
    }

    @Test
    fun duplicatePlayerRollsAreRejected() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parse("!roll capital-tie p1=1 p1=2")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun authorityOutcomesAcceptTheSameWhitespaceAsCommands() {
        val entry = TextCodec.parse("!draw\tp1  hand-1\tstone-1  ")

        assertThat(entry.toText()).isEqualTo("!draw p1 hand-1 stone-1")
    }

    @Test
    fun entriesCannotSpanMultipleLines() {
        val entries = listOf("p1: start\n", "!shuffle stone-1\nstone-2")

        val codes = entries.map { entry ->
            assertFailsWith<RuleViolation> { TextCodec.parse(entry) }.code
        }

        assertThat(codes)
            .containsExactly(ViolationCode.INVALID_COMMAND, ViolationCode.INVALID_COMMAND)
    }

    private fun entries() =
        listOf(
            "p1: set-option map standard",
            "p1: set-option deck-modification disabled",
            "p1: start",
            "!shuffle ${CardData.epochOne.joinToString(" ", transform = CardDefinition::id)}",
            "!draw p1 hand-1 stone-1",
            "!draw p2 hand-2 stone-2",
            "!draw p3 hand-3 wool-1",
            "p1: bid-capital 12",
            "p2: bid-capital 12",
            "p3: bid-capital 0",
            "!roll capital-tie p1=3 p2=3",
            "!roll capital-tie p1=2 p2=6",
            "p2: choose-capital venice",
            "p1: choose-capital genoa",
            "p3: choose-capital barcelona",
            "p1: buy-tokens 2",
            "p2: buy-tokens 2",
            "p3: buy-tokens -1",
            "p1: choose-turn-position 1",
        )
}
