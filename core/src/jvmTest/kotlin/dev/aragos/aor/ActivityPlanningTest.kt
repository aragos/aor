package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ActivityPlanningTest {
    private val owner = PlayerId("p2")
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }

    private fun state(stage: Stage = Stage.PURCHASE): GameState =
        GameState(
            header = GameHeader(players, players.first().id),
            stage = stage,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            deck = CardData.epochOne.map(CardDefinition::id),
            flow =
                TurnFlow(
                    actor = players.first().id,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
            players =
                players.associate { player ->
                    player.id to
                        PlayerState(
                            player = player,
                            capital =
                                listOf("venice", "genoa", "barcelona")[players.indexOf(player)],
                            cash = 500,
                            hand =
                                listOf(
                                    HandCard(
                                        "hand-${player.id.value.last()}",
                                        CardData.epochOne.first(),
                                    )
                                ),
                        )
                },
        )

    @Test
    fun waitingPlayerCanPreviewPrerequisitesWithoutBecomingTheLiveActor() {
        val initial = state()
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "buy-advance A"),
                PlannedAction("purchase-advances", "buy-advance B"),
            )

        val preview = ActivityPlanning.preview(initial, owner, actions)

        assertThat(preview.effects).hasSize(3)
        assertThat(preview.effects.last().advanceQuote?.advance).isEqualTo(AdvanceId.HUMAN_BODY)
        assertThat(preview.effects.last().advanceQuote?.categoryCredit).isEqualTo(0)
        assertThat(preview.hypothetical).isTrue()
        assertThat(initial.flow.actor).isEqualTo(PlayerId("p1"))
        assertThat(initial.playerState(owner).advances.owned).isEmpty()
    }

    @Test
    fun immediateAbilitiesAffectLaterPricesInTheSamePlan() {
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "buy-advance X"),
                PlannedAction("purchase-advances", "buy-advance A"),
            )

        val preview = ActivityPlanning.preview(state(), owner, actions)

        assertThat(preview.effects.last().advanceQuote?.institutionalResearchCredit).isEqualTo(10)
    }

    @Test
    fun aPassCannotBeReinterpretedAsAnotherStepsPass() {
        val preview =
            ActivityPlanning.preview(
                state(),
                owner,
                listOf(PlannedAction("purchase-advances", "pass")),
            )

        assertThat(preview.effects).isEmpty()
        assertThat(preview.diagnostic?.code).isEqualTo("PLAN_DECISION_CHANGED")
    }

    @Test
    fun drawPreviewIsIndependentOfTheActualDeckOrder() {
        val initial =
            state(Stage.BUY_CARD).updatePlayer(owner) { player ->
                player.copy(advances = player.advances.acquire(AdvanceId.URBAN_ASCENDANCY, 1))
            }
        val actions =
            listOf(
                PlannedAction("buy-card", "buy-card"),
                PlannedAction("buy-card", "pass"),
            )

        val first = ActivityPlanning.preview(initial, owner, actions)
        val reversed =
            ActivityPlanning.preview(initial.copy(deck = initial.deck.reversed()), owner, actions)

        assertThat(first).isEqualTo(reversed)
        assertThat(first.boundary).isEqualTo(PlanBoundary.INFORMATION)
        assertThat(first.effects).hasSize(1)
        assertThat(first.game.hand).isEqualTo(initial.playerState(owner).hand)
    }

    @Test
    fun immediateMasterArtDiscardUsesItsOwnDecisionAndChangesStabilization() {
        val initial =
            state().copy(rules = RuleOptions(immediateMasterArtDiscard = true)).updatePlayer(
                owner
            ) { player ->
                player.copy(
                    advances = PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.PRINTED_WORD, 1)))
                )
            }
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "buy-advance P"),
                PlannedAction("immediate-master-art-discard", "discard-card hand-2"),
                PlannedAction("purchase-advances", "pass"),
            )

        val preview = ActivityPlanning.preview(initial, owner, actions)

        assertThat(preview.diagnostic).isNull()
        assertThat(preview.effects).hasSize(4)
        assertThat(preview.effects[2].after.hand).isEmpty()
        assertThat(preview.boundary).isEqualTo(PlanBoundary.FINISHED)
    }

    @Test
    fun finishingTheActivityCannotConsumeANextPlayersPass() {
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "pass"),
                PlannedAction("purchase-stabilization", "stabilize cash"),
                PlannedAction("purchase-shipping", "pass"),
            )

        val preview = ActivityPlanning.preview(state(), owner, actions)

        assertThat(preview.effects).hasSize(3)
        assertThat(preview.boundary).isEqualTo(PlanBoundary.FINISHED)
        assertThat(preview.choices.commands).isEmpty()
    }

    @Test
    fun ordinaryMasterArtCanPlanAKnownDiscardWithoutBuyingACard() {
        val initial =
            state(Stage.BUY_CARD).copy(turnNumber = 2).updatePlayer(owner) { player ->
                player.copy(
                    advances =
                        PlayerAdvances(
                            listOf(
                                    AdvanceId.WRITTEN_RECORD,
                                    AdvanceId.PRINTED_WORD,
                                    AdvanceId.MASTER_ART,
                                )
                                .map { advance -> AdvanceAcquisition(advance, turnNumber = 1) }
                        )
                )
            }

        val preview =
            ActivityPlanning.preview(
                initial,
                owner,
                listOf(PlannedAction("buy-card", "discard-card hand-2")),
            )

        assertThat(preview.effects).hasSize(1)
        assertThat(preview.game.hand).isEmpty()
        assertThat(preview.boundary).isEqualTo(PlanBoundary.FINISHED)
        assertThat(initial.playerState(owner).hand).hasSize(1)
    }
}
