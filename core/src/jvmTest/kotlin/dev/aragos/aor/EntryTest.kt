package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EntryTest {
    private val header =
        GameHeader(
            creatorId = PlayerId("p1"),
            players =
                (1..3).map { number -> Player(id = PlayerId("p$number"), name = "Player $number") },
        )

    @Test
    fun shuffleRejectsAnAdditionalDuplicateEvenIfEveryCardIsPresent() {
        val state = TextCodec.parse("p1: start").applyTo(GameState(header))
        val cards = CardData.epochOne.map(CardDefinition::id)

        val failure =
            assertFailsWith<RuleViolation> {
                Entry.Shuffle(cards + cards.first()).applyTo(state)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun shuffleRejectsAMissingCardReplacedByADuplicate() {
        val state = TextCodec.parse("p1: start").applyTo(GameState(header))
        val cards = CardData.epochOne.map(CardDefinition::id)

        val failure =
            assertFailsWith<RuleViolation> {
                Entry.Shuffle(cards.dropLast(1) + cards.first()).applyTo(state)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun shuffleRequiresTheShuffleStage() {
        val failure =
            assertFailsWith<RuleViolation> {
                Entry.Shuffle(CardData.epochOne.map(CardDefinition::id)).applyTo(GameState(header))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun actionRejectsAnUnregisteredPlayerBeforeRunningTheCommand() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parse("p6: start").applyTo(GameState(header))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNKNOWN_PLAYER)
    }

    @Test
    fun capitalRollCanonicalTextSortsByPlayerIdentity() {
        val entry = TextCodec.parse("!roll capital-tie p2=6 p1=2")

        assertThat(entry.toText()).isEqualTo("!roll capital-tie p1=2 p2=6")
    }

    @Test
    fun malformedOutcomeArgumentsHaveCommandDiagnostics() {
        for (text in
            listOf(
                "!deal p1 hand-1",
                "!shuffle stone-1",
                "!roll other p1=1 p2=2",
                "!deal p0 hand-1 stone-1",
                "!roll capital-tie p1=one p2=2",
            )) {
            val failure = assertFailsWith<RuleViolation> { TextCodec.parse(text) }

            assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
        }
    }
}
