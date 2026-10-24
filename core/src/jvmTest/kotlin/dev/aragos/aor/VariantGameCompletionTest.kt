package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.Test

/** Full preset acceptance uses only ordinary commands and recorded authority outcomes. */
class VariantGameCompletionTest {
    @Test
    fun bothPresetsCompleteAtEverySupportedPlayerCount() {
        for (preset in presets) {
            for (playerCount in 3..6) {
                val completed = completedGame(preset, playerCount)
                val state = completed.state

                assertThat(state.stage).isEqualTo(Stage.FINISHED)
                assertWithMessage(
                        "$preset/$playerCount ended at ${state.currentEpoch}, " +
                            "${state.deck.size} cards left, " +
                            "${state.players.values.count { owner -> owner.misery == MiseryStep.CHAOS }} in Chaos"
                    )
                    .that(
                        (state.currentEpoch == Epoch.THREE && state.deck.isEmpty()) ||
                            state.players.values.any { owner -> owner.advances.owned.size == 26 } ||
                            state.players.values.all { owner -> owner.misery == MiseryStep.CHAOS }
                    )
                    .isTrue()
            }
        }
    }

    @Test
    fun bothPresetHistoriesReplayExactlyForEverySupportedPlayerCount() {
        for (preset in presets) {
            for (playerCount in 3..6) {
                val completed = completedGame(preset, playerCount)

                val replayed = replay(completed.state.header, completed.lines)

                assertThat(replayed).isEqualTo(completed.state)
            }
        }
    }

    @Test
    fun bothPresetsConserveAllSixtyFourPhysicalCardsAtEverySupportedPlayerCount() {
        for (preset in presets) {
            for (playerCount in 3..6) {
                val state = completedGame(preset, playerCount).state
                val locations =
                    state.players.values.flatMap(PlayerState::hand).map { card ->
                        card.definition.id
                    } +
                        state.deck +
                        state.deckHistory.discarded +
                        state.deckHistory.removed +
                        state.deckHistory.setupReserves +
                        state.deckHistory.setupReturns.values.map { card -> card.definition.id } +
                        CardData.all
                            .filter { card ->
                                card.epoch.number > state.currentEpoch.number &&
                                    card.id !in state.deckHistory.movedToFirstDeck
                            }
                            .map(CardDefinition::id)

                assertThat(locations)
                    .containsExactlyElementsIn(CardData.all.map(CardDefinition::id))
            }
        }
    }

    @Test
    fun bothPresetsPublishSelectedAdvanceValuesAndFinalScoresForEveryPlayer() {
        for (preset in presets) {
            for (playerCount in 3..6) {
                val state = completedGame(preset, playerCount).state
                val scores = state.viewForPlayer(PlayerId("p1")).scores

                assertThat(scores.map(ScoreView::player)).containsExactlyElementsIn(state.playerIds)
                for (score in scores) {
                    val owner = state.playerState(score.player)
                    val selectedPoints =
                        owner.advances.owned.sumOf { advance ->
                            state.rules.definition(advance).cost
                        }
                    assertThat(score.advancePoints).isEqualTo(selectedPoints)
                    assertThat(score.total)
                        .isEqualTo(
                            owner.misery.value?.let { misery ->
                                selectedPoints + owner.cash - misery
                            }
                        )
                }
                val highestScore = scores.mapNotNull(ScoreView::total).maxOrNull()
                assertThat(scores.filter(ScoreView::winner).map(ScoreView::player))
                    .containsExactlyElementsIn(
                        scores
                            .filter { score ->
                                score.total != null && score.total == highestScore
                            }
                            .map(ScoreView::player)
                    )
            }
        }
    }

    private fun completedGame(preset: String, playerCount: Int): Scenario =
        Scenario.create(playerCount).play("p1: set-option preset $preset", "p1: start").complete()

    private companion object {
        val presets = listOf("second-edition", "tournament")
    }
}
