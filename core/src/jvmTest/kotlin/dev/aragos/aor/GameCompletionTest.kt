package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/** Command-level acceptance: no board/cash/hand edits in the complete deck-exhaustion scenario. */
class GameCompletionTest {
    @Test
    fun ordinaryCommandsCompleteAnEpochAfterTheLastDraw() {
        val completed = completedGame()
        assertThat(completed.state.stage).isEqualTo(Stage.FINISHED)
        assertThat(completed.state.deck).isEmpty()
        assertThat(completed.state.viewForPlayer(PlayerId("p1")).scores.any(ScoreView::winner))
            .isTrue()
    }

    @Test
    fun completedGameReplaysToTheIdenticalState() {
        val completed = completedGame()
        assertThat(replay(completed.state.header, completed.lines)).isEqualTo(completed.state)
    }

    @Test
    fun fullGameExercisesExpansionCompetitionPurchasesAndCardEffects() {
        val completed = completedGame()
        assertThat(completed.lines.any { line -> line.contains(": place ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": attack ") }).isTrue()
        assertThat(completed.lines.any { line -> line.startsWith("!roll competition ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": buy-advance ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": play ") }).isTrue()
        assertThat(completed.lines.any { line -> line.startsWith("!roll war ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": civil-war-loss ") }).isTrue()
    }

    private fun completedGame(): Scenario {
        val opening =
            Scenario.create()
                .play(
                    "p1: start",
                    "p1: bid-capital 3",
                    "p2: bid-capital 2",
                    "p3: bid-capital 1",
                    "p1: choose-capital venice",
                    "p2: choose-capital genoa",
                    "p3: choose-capital barcelona",
                    "p1: buy-tokens 8",
                    "p2: buy-tokens 9",
                    "p3: buy-tokens 10",
                )
                .playAllCardRounds()
                .play(
                    "p1: pass",
                    "p1: buy-advance V",
                    "p1: pass",
                    "p1: stabilize cash",
                    "p1: pass",
                    "p2: upgrade-ships",
                    "p2: pass",
                    "p2: stabilize cash",
                    "p2: pass",
                    "p3: pass",
                    "p3: buy-advance I",
                    "p3: pass",
                    "p3: stabilize cash",
                    "p3: pass",
                    "p1: place 4 florence",
                    "p1: buy-expansion-card",
                    "p1: pass",
                    "p2: attack florence",
                    "p2: pass",
                    "p3: place 2 valencia",
                    "p3: place 2 montpellier",
                    "p3: pass",
                )
        return opening.complete()
    }
}
