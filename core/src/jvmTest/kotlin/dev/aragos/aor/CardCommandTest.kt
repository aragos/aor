package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardCommandTest {
    @Test
    fun allCardCommandChoicesHaveCanonicalRoundTrips() {
        val commands =
            listOf(
                "play hand-3",
                "play hand-3 commodity cloth",
                "play hand-3 player p2",
                "play hand-3 province tunis",
                "play hand-3 category religion",
                "play hand-3 category none",
                "play hand-3 protect 5",
                "civil-war-loss cash",
                "civil-war-loss tokens",
                "replace-event-tokens genoa rome",
                "replace-event-tokens none",
                "pass",
                "forgo-marker",
                "move-marker venice",
                "cede-war acre tunis",
                "patronize rashid-ad-din",
            )

        assertThat(commands.map { command -> TextCodec.parseCommand(command).toText() })
            .containsExactlyElementsIn(commands)
            .inOrder()
    }

    @Test
    fun supersededCardCommandNamesAreRejected() {
        val commands =
            listOf("play-card hand-3", "patronize-leader rashid-ad-din", "replace-event-token pool")

        val failures = commands.map { text ->
            assertFailsWith<RuleViolation> { TextCodec.parseCommand(text) }.code
        }

        assertThat(failures)
            .containsExactly(
                ViolationCode.INVALID_COMMAND,
                ViolationCode.INVALID_COMMAND,
                ViolationCode.INVALID_COMMAND,
            )
    }

    @Test
    fun duplicateWarSurrenderProvincesAreRejected() {
        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseCommand("cede-war tunis tunis") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun duplicateReplacementProvincesAreRejected() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseCommand("replace-event-tokens rome rome")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun emptyReplacementCannotBeMixedWithProvinces() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseCommand("replace-event-tokens none rome")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun negativeLeaderProtectionIsRejected() {
        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseCommand("play hand-3 protect -2") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }
}
