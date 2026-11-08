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
    fun anotherPlayersPurchasesAndTurnArrivalPreservePlannedConsequences() {
        val initial = state()
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "buy-advance A"),
                PlannedAction("purchase-advances", "pass"),
                PlannedAction("purchase-stabilization", "stabilize cash"),
            )
        val before = ActivityPlanning.preview(initial, owner, actions)
        val arrived =
            listOf("pass", "buy-advance A", "pass", "stabilize cash").fold(initial) {
                current,
                command ->
                TurnEngine.normalise(
                    TextCodec.parseCommand(command).applyTo(current, PlayerId("p1"))
                )
            }

        val after = ActivityPlanning.preview(arrived, owner, actions)

        assertThat(arrived.flow.actor).isEqualTo(owner)
        assertThat(after.effects).isEqualTo(before.effects)
        assertThat(after.boundary).isEqualTo(before.boundary)
        assertThat(after.diagnostic).isNull()
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
    fun newlyPurchasedPatronageCanClaimALeaderAndUseItsDiscount() {
        val initial =
            state()
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.ST_BENEDICT,
                                        PlayerId("p1"),
                                        CardLeaderRole.DISPLAY,
                                    ),
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        PlayerId("p1"),
                                        CardLeaderRole.EXPOSED,
                                        protectionCash = 7,
                                    ),
                                )
                        )
                )
        val preview =
            ActivityPlanning.preview(
                initial,
                owner,
                listOf(
                    PlannedAction("purchase-shipping", "pass"),
                    PlannedAction("purchase-advances", "buy-advance E"),
                    PlannedAction("purchase-advances", "patronize charlemagne"),
                    PlannedAction("purchase-advances", "buy-advance W"),
                ),
            )

        assertThat(preview.diagnostic).isNull()
        assertThat(preview.effects).hasSize(4)
        assertThat(preview.effects[2].cashChanges)
            .containsExactly(
                PlanCashChange(PlayerId("p1"), 500, 507),
                PlanCashChange(owner, 470, 463),
            )
        assertThat(preview.effects.last().advanceQuote?.leaders).isNotEmpty()
        assertThat(preview.effects.last().advanceQuote?.leaders?.single()?.leaderCardId)
            .isEqualTo("charlemagne")
    }

    @Test
    fun reliefRemainsASeparatePlannableDecisionAfterStabilization() {
        val tier =
            listOf(
                AdvanceId.THE_HEAVENS,
                AdvanceId.PATRONAGE,
                AdvanceId.CARAVAN,
                AdvanceId.WRITTEN_RECORD,
                AdvanceId.OVERLAND_EAST,
                AdvanceId.URBAN_ASCENDANCY,
            )
        val initial =
            state().updatePlayer(owner) { player ->
                player.copy(
                    advances = PlayerAdvances(tier.map { AdvanceAcquisition(it, 1) }),
                    misery = MiseryStep.entries[5],
                )
            }
        val prefix =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "pass"),
                PlannedAction("purchase-stabilization", "stabilize cash"),
            )

        val preview = ActivityPlanning.preview(initial, owner, prefix)

        assertThat(preview.boundary).isEqualTo(PlanBoundary.INCOMPLETE)
        assertThat(preview.decision).isEqualTo("purchase-relief")
        assertThat(preview.choices.commands).contains("relieve-misery 1")
        val completed =
            ActivityPlanning.preview(
                initial,
                owner,
                prefix + PlannedAction("purchase-relief", "relieve-misery 1"),
            )
        assertThat(completed.boundary).isEqualTo(PlanBoundary.FINISHED)
        assertThat(completed.effects.last().after.reliefUsage?.spaces).isEqualTo(1)
        assertThat(completed.effects.last().after.misery).isEqualTo(MiseryStep.entries[4])
    }

    @Test
    fun cardPurchaseCanBePlannedWhileAnotherPlayerDecidesRenaissance() {
        val initial =
            state(Stage.BUY_CARD)
                .copy(
                    flow =
                        state(Stage.BUY_CARD)
                            .flow
                            .copy(decisions = listOf(RenaissanceDecision(PlayerId("p1"))))
                )
                .updatePlayer(owner) { player ->
                    player.copy(advances = player.advances.acquire(AdvanceId.URBAN_ASCENDANCY, 1))
                }

        val preview =
            ActivityPlanning.preview(
                initial,
                owner,
                listOf(PlannedAction("buy-card", "buy-card")),
            )

        assertThat(preview.diagnostic).isNull()
        assertThat(preview.effects).hasSize(1)
        assertThat(preview.hypothetical).isTrue()
        assertThat(preview.boundary).isEqualTo(PlanBoundary.INFORMATION)
        assertThat(initial.flow.decisions).hasSize(1)
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
