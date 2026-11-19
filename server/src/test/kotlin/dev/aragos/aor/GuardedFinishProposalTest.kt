package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class GuardedFinishProposalTest {
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

        val guard = FinalAdvanceGuard(currentLead = 100, projectedLead = 250)
        val prerequisite =
            checkNotNull(guard.choose(state, state.viewForPlayer(actor), actor, "pass")) {
                "A clearly leading player may buy the prerequisite while both advances are affordable."
            }
        val afterPrerequisite = state.step(TextCodec.parseAction(prerequisite, state, actor))
        val finalAdvance =
            checkNotNull(
                guard.choose(
                    afterPrerequisite,
                    afterPrerequisite.viewForPlayer(actor),
                    actor,
                    "pass",
                )
            ) {
                "The second quote must still offer the legal game-ending advance."
            }
        val bought =
            afterPrerequisite.step(TextCodec.parseAction(finalAdvance, afterPrerequisite, actor))

        assertThat(prerequisite).isEqualTo("buy-advance V")
        assertThat(finalAdvance).isEqualTo("buy-advance U")
        assertThat(bought.endingTriggered).isTrue()
        assertThat(bought.stage).isEqualTo(Stage.PURCHASE)
    }
}
