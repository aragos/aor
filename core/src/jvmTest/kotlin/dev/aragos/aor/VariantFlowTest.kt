package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class VariantFlowTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun purchasingMasterArtOffersAnImmediateDiscardBeforeStabilization() {
        val initial =
            purchase(RuleOptions(immediateMasterArtDiscard = true))
                .withAdvances(p1, AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD)
                .withCard(p1, "armor", "burden")

        val purchased = initial.play("p1: buy-advance P")

        assertThat(purchased.flow.decisions.last())
            .isInstanceOf(ImmediateMasterArtDecision::class.java)
        assertThat(purchased.legalChoices(p1).commands)
            .containsExactly("pass", "discard-card burden")
        assertThat(purchased.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    @Test
    fun immediateMasterArtDiscardRemovesBurdenAndResumesPurchases() {
        val initial =
            purchase(RuleOptions(immediateMasterArtDiscard = true))
                .withAdvances(p1, AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD)
                .withCard(p1, "armor", "burden")
                .copy(voids = CardVoids(armorAndStirrups = true))

        val discarded = initial.play("p1: buy-advance P", "p1: discard-card burden")

        assertThat(discarded.playerState(p1).hand).isEmpty()
        assertThat(discarded.flow.decisions).isEmpty()
        assertThat(discarded.flow.actor).isEqualTo(p1)
        assertThat(discarded.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    @Test
    fun immediateMasterArtDiscardMayBeDeclined() {
        val initial =
            purchase(RuleOptions(immediateMasterArtDiscard = true))
                .withAdvances(p1, AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD)
                .withCard(p1, "armor", "held")

        val declined = initial.play("p1: buy-advance P", "p1: pass")

        assertThat(declined.playerState(p1).hand.map(HandCard::reference)).containsExactly("held")
        assertThat(declined.flow.decisions).isEmpty()
        assertThat(declined.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    @Test
    fun emptyHandDoesNotInterruptImmediateMasterArtPurchase() {
        val initial =
            purchase(RuleOptions(immediateMasterArtDiscard = true))
                .withAdvances(p1, AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD)

        val purchased = initial.play("p1: buy-advance P")

        assertThat(purchased.flow.decisions).isEmpty()
        assertThat(purchased.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    private fun purchase(rules: RuleOptions): GameState {
        val ids = listOf(p1, p2, p3)
        return GameState(
                header = GameHeader(ids.map { player -> Player(player, player.value) }, p1),
                rules = rules,
                stage = Stage.PURCHASE,
                turnNumber = 2,
                turnOrderSelection = TurnOrderSelection(ids),
                flow = purchaseFlow(),
            )
            .copy(
                players =
                    ids.mapIndexed { index, player ->
                            player to
                                PlayerState(
                                    player = Player(player, player.value),
                                    capital = listOf("venice", "genoa", "barcelona")[index],
                                    cash = if (player == p1) 1000 else 40,
                                )
                        }
                        .toMap()
            )
    }

    private fun purchaseFlow(step: PurchaseStep = PurchaseStep.ADVANCES): TurnFlow =
        TurnFlow(
            actor = p1,
            phaseStarted = true,
            phaseBookkeepingApplied = true,
            purchaseStep = step,
        )

    private fun GameState.withAdvances(player: PlayerId, vararg advances: AdvanceId): GameState =
        updatePlayer(player) { owner ->
            owner.copy(
                advances =
                    PlayerAdvances(advances.map { advance -> AdvanceAcquisition(advance, 1) })
            )
        }

    private fun GameState.withCard(player: PlayerId, card: String, reference: String): GameState =
        updatePlayer(player) { owner ->
            owner.copy(hand = owner.hand + HandCard(reference, CardData.definition(card)))
        }

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line ->
            state.step(TextCodec.parse(line)).normalise()
        }
}
