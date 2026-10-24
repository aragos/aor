package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardCommandTest {
    @Test
    fun allCardCommandChoicesHaveCanonicalRoundTrips() {
        val commands =
            listOf(
                "play-card hand-3",
                "play-card hand-3 commodity cloth",
                "play-card hand-3 player p2",
                "play-card hand-3 province tunis",
                "play-card hand-3 category religion",
                "play-card hand-3 category none",
                "play-card hand-3 protect 5",
                "civil-war-loss cash",
                "civil-war-loss tokens",
                "replace-event-token pool",
                "replace-event-token none",
                "resolve-war",
                "forgo-marker",
                "move-marker venice",
                "cede-war acre tunis",
                "patronize-leader rashid-ad-din",
            )

        assertThat(commands.map { command -> TextCodec.parseCommand(command).toText() })
            .containsExactlyElementsIn(commands)
            .inOrder()
    }

    @Test
    fun duplicateWarSurrenderProvincesAreRejected() {
        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseCommand("cede-war tunis tunis") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun negativeLeaderProtectionIsRejected() {
        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseCommand("play-card hand-3 protect -2") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }
}
