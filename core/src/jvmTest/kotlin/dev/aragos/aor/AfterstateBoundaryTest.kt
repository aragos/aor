package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/** The independent reference uses real authority, never ActivityPlanning's sanitized copy. */
class AfterstateBoundaryTest {
    private val owner = PlayerId("p1")
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val tier =
        listOf(
            AdvanceId.THE_HEAVENS,
            AdvanceId.PATRONAGE,
            AdvanceId.CARAVAN,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.OVERLAND_EAST,
            AdvanceId.URBAN_ASCENDANCY,
        )

    private fun state(step: PurchaseStep, chaos: Boolean = false): GameState =
        GameState(
            header = GameHeader(players, owner),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            deck = CardData.epochOne.map(CardDefinition::id),
            flow =
                TurnFlow(
                    actor = owner,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = step,
                ),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = if (chaos && index == 0) 0 else 500,
                                misery =
                                    if (chaos && index == 0)
                                        MiseryStep.entries[MiseryStep.entries.size - 2]
                                    else MiseryStep.entries[5],
                                advances =
                                    if (index == 0)
                                        PlayerAdvances(tier.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                                hand =
                                    List(3) { HandCard("held-$index-$it", CardData.epochOne[it]) },
                            )
                    }
                    .toMap(),
        )

    private fun compare(
        initial: GameState,
        decision: String,
        command: String,
    ): ActivityPlanPreview {
        val actual =
            TurnEngine.normalise(
                TextCodec.parseCommand(command).applyTo(initial, owner),
                planningActor = owner,
            )
        val preview =
            ActivityPlanning.preview(initial, owner, listOf(PlannedAction(decision, command)))
        assertThat(preview.hypothetical).isFalse()
        assertThat(preview.game).isEqualTo(actual.viewForPlayer(owner))
        return preview
    }

    @Test
    fun shippingEndpointEqualsActualAdvancesView() {
        assertThat(compare(state(PurchaseStep.SHIPPING), "purchase-shipping", "pass").decision)
            .isEqualTo("purchase-advances")
    }

    @Test
    fun advancesEndpointEqualsActualStabilizationView() {
        assertThat(compare(state(PurchaseStep.ADVANCES), "purchase-advances", "pass").decision)
            .isEqualTo("purchase-stabilization")
    }

    @Test
    fun stabilizationEndpointEqualsActualReliefView() {
        assertThat(
                compare(
                        state(PurchaseStep.STABILIZATION),
                        "purchase-stabilization",
                        "stabilize cash",
                    )
                    .decision
            )
            .isEqualTo("purchase-relief")
    }

    @Test
    fun reliefEndpointStopsBeforeNextActor() {
        val preview = compare(state(PurchaseStep.RELIEF), "purchase-relief", "relieve-misery 1")
        assertThat(preview.boundary).isEqualTo(PlanBoundary.FINISHED)
        assertThat(preview.decision).isNull()
    }

    @Test
    fun chaosIsRetainedAsNaturalOwnerFinish() {
        val preview =
            compare(
                state(PurchaseStep.STABILIZATION, chaos = true),
                "purchase-stabilization",
                "stabilize misery",
            )
        assertThat(preview.boundary).isEqualTo(PlanBoundary.FINISHED)
        assertThat(preview.game.players.single { it.id == owner }.misery)
            .isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun informationBoundaryDoesNotResolveUnknownCard() {
        val initial = state(PurchaseStep.ADVANCES).copy(stage = Stage.BUY_CARD)
        val preview = compare(initial, "buy-card", "buy-card")
        assertThat(preview.boundary).isEqualTo(PlanBoundary.INFORMATION)
        assertThat(preview.game.hand).isEqualTo(initial.viewForPlayer(owner).hand)
    }
}
