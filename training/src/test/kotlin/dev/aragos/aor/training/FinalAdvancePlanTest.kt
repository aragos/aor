package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.CardDefinition
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class FinalAdvancePlanTest {
    private val actor = PlayerId("p1")

    @Test
    fun finishingPlanBuysThePrerequisiteBeforeTheLastAdvance() {
        val advances =
            AdvanceId.entries
                .filterNot { it in setOf(AdvanceId.URBAN_ASCENDANCY, AdvanceId.NEW_WORLD) }
                .fold(PlayerAdvances()) { previous, advance -> previous.acquire(advance, 6) }
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona")
        val state =
            GameState(
                GameHeader(players, actor),
                stage = Stage.PURCHASE,
                turnNumber = 7,
                deck = CardData.epochOne.map(CardDefinition::id),
                players =
                    players
                        .mapIndexed { index, player ->
                            player.id to
                                PlayerState(
                                    player,
                                    capital = capitals[index],
                                    cash = if (player.id == actor) 500 else 100,
                                    advances =
                                        if (player.id == actor) advances else PlayerAdvances(),
                                )
                        }
                        .toMap(),
                turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
                flow =
                    TurnFlow(
                        actor = actor,
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                        purchaseStep = PurchaseStep.ADVANCES,
                    ),
            )

        val plan =
            checkNotNull(FinalAdvancePlan.find(state, actor, "pass")) {
                "The player can afford the prerequisite and the final advance."
            }
        val bought =
            plan.advances.fold(state) { current, advance ->
                current.step(TextCodec.parseAction("buy-advance ${advance.letter}", current, actor))
            }

        assertThat(plan.advances)
            .containsExactly(AdvanceId.URBAN_ASCENDANCY, AdvanceId.NEW_WORLD)
            .inOrder()
        assertThat(plan.projectedMargin).isGreaterThan(0)
        assertThat(bought.endingTriggered).isTrue()
        assertThat(bought.stage).isEqualTo(Stage.PURCHASE)
    }
}
