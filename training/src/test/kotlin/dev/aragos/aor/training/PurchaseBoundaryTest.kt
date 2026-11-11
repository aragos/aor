package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test

class PurchaseBoundaryTest {
    @Test
    fun currentTurnPurchasesAreNotTheValueBoundary() {
        val boundary = PurchaseBoundary.project(view(turn = 4), actor, observation(), rootTurn = 4)

        assertThat(boundary).isNull()
    }

    @Test
    fun laterOwnPurchaseUsesTheOriginalEncoderUnits() {
        val boundary =
            requireNotNull(
                PurchaseBoundary.project(view(turn = 5), actor, observation(), rootTurn = 4)
            ) {
                "A later purchase should qualify."
            }

        assertThat(boundary.stateFeatures["turn"]).isEqualTo(0.25)
        assertThat(boundary.stateFeatures["epoch"]).isEqualTo(2.0 / 3.0)
    }

    @Test
    fun anotherPlayersPurchaseIsNotTheControlledBoundary() {
        val boundary =
            PurchaseBoundary.project(
                view(turn = 5),
                actor,
                observation().copy(playerId = "p2"),
                rootTurn = 4,
            )

        assertThat(boundary).isNull()
    }

    @Test
    fun stabilizationDoesNotReplaceTheRequestedPurchaseBoundary() {
        val boundary =
            PurchaseBoundary.project(
                view(turn = 5),
                actor,
                observation().copy(decision = "purchase-stabilization"),
                rootTurn = 4,
            )

        assertThat(boundary).isNull()
    }

    @Test
    fun authoredCandidateScoresAndContextCannotLeakIntoStateFeatures() {
        val original =
            requireNotNull(
                PurchaseBoundary.project(view(turn = 5), actor, observation(), rootTurn = 4)
            ) {
                "Fixture boundary exists."
            }
        val changed =
            observation()
                .copy(
                    context = mapOf("reward" to 999.0),
                    candidates = listOf(BotCandidate("pass", mapOf("reward" to 999.0))),
                )

        val projected =
            requireNotNull(PurchaseBoundary.project(view(turn = 5), actor, changed, rootTurn = 4)) {
                "The same actor boundary exists."
            }

        assertThat(projected.stateFeatures).isEqualTo(original.stateFeatures)
    }

    private val actor = PlayerId("p1")

    private fun view(turn: Int) =
        GameState(GameHeader((1..4).map { Player(PlayerId("p$it"), "Fixture $it") }, actor))
            .viewForPlayer(actor)
            .copy(stage = Stage.PURCHASE, turnNumber = turn, currentEpoch = 2)

    private fun observation() =
        BotObservation(
            playerId = actor.value,
            decision = "purchase-shipping",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 0,
            candidates = listOf(BotCandidate("pass", emptyMap())),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
