package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class SuspendedDeckReplayTest {
    @Test
    fun replayRestoresBothTransitionRequestsBeforeTheirRecordedShuffles() {
        val completed = completeGame()
        val transitions =
            completed.lines.indices
                .filter { index ->
                    completed.lines[index].startsWith("!shuffle ")
                }
                .drop(1)

        assertThat(transitions).hasSize(2)
        for (index in transitions) {
            val restored = replay(completed.state.header, completed.lines.take(index))

            assertThat(restored.prompts().single()).isInstanceOf(Prompt.System::class.java)
            assertThat((restored.prompts().single() as Prompt.System).need)
                .isInstanceOf(SystemNeed.Shuffle::class.java)
            assertThat(resume(restored, completed.lines.drop(index))).isEqualTo(completed.state)
        }
    }

    @Test
    fun replayRestoresSuspendedBeneficiariesAfterBothRecordedShuffles() {
        val completed = completeGame()
        val transitions =
            completed.lines.indices
                .filter { index ->
                    completed.lines[index].startsWith("!shuffle ")
                }
                .drop(1)

        for (index in transitions) {
            val restored = replay(completed.state.header, completed.lines.take(index + 1))

            assertThat((restored.prompts().single() as Prompt.System).need)
                .isInstanceOf(SystemNeed.Draw::class.java)
            assertThat(resume(restored, completed.lines.drop(index + 1))).isEqualTo(completed.state)
        }
    }

    private fun resume(state: GameState, lines: List<String>): GameState =
        lines.fold(state) { current, line -> current.step(TextCodec.parse(line)) }

    private fun completeGame(): Scenario =
        Scenario.create()
            .play(
                "p1: start",
                "p1: bid-capital 3",
                "p2: bid-capital 2",
                "p3: bid-capital 1",
                "p1: choose-capital venice",
                "p2: choose-capital genoa",
                "p3: choose-capital barcelona",
            )
            .complete()
}
