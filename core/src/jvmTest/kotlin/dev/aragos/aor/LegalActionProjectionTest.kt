package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class LegalActionProjectionTest {
    private val p1 = PlayerId("p1")

    @Test
    fun configurationChoicesIncludeAllFunctionalOptions() {
        val state = Scenario.create().state

        val choices = state.legalChoices(p1).commands

        assertThat(choices)
            .containsAtLeast(
                "set-option initial-card no-events",
                "set-option deck-modification enabled",
                "set-option immediate-master-art-discard enabled",
            )
    }

    @Test
    fun everySuggestedConfigurationCommandIsAccepted() {
        val state = Scenario.create().state

        state.legalChoices(p1).commands.forEach { command ->
            state.step(TextCodec.parse("p1: $command"))
        }
    }

    @Test
    fun configurationChoicesBelongOnlyToTheCreator() {
        val state = Scenario.create().state

        assertThat(state.legalChoices(PlayerId("p2")).commands).isEmpty()
    }

    @Test
    fun shippingChoicesOmitAnUnaffordableUpgrade() {
        val state = purchasing(PurchaseStep.SHIPPING, cash = 9)
        assertThat(state.legalChoices(p1).commands).containsExactly("pass")
    }

    @Test
    fun advanceChoicesRespectPrerequisitesCashAndDecree() {
        val state =
            purchasing(PurchaseStep.ADVANCES, cash = 40)
                .copy(effects = CardTurnEffects(papalCategory = AdvanceCategory.SCIENCE))
        assertThat(state.legalChoices(p1).commands)
            .containsExactly(
                "pass",
                "buy-advance E",
                "buy-advance I",
                "buy-advance N",
                "buy-advance R",
                "buy-advance V",
            )
    }

    @Test
    fun reliefChoicesIncludeAConcreteAffordableSpaceCount() {
        val advances =
            listOf(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.PATRONAGE,
                    AdvanceId.CARAVAN,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.OVERLAND_EAST,
                    AdvanceId.URBAN_ASCENDANCY,
                )
                .fold(PlayerAdvances()) { owned, advance -> owned.acquire(advance, 1) }
        val state =
            purchasing(PurchaseStep.RELIEF, cash = 10).updatePlayer(p1) { player ->
                player.copy(advances = advances, misery = MiseryStep.TEN)
            }
        assertThat(state.legalChoices(p1).commands).containsExactly("pass", "relieve-misery 1")
    }

    @Test
    fun finalCardPlayDoesNotOfferAnIllegalPass() {
        val state =
            purchasing(PurchaseStep.SHIPPING, cash = 10)
                .copy(stage = Stage.FINAL_CARD_PLAY)
                .updatePlayer(p1) { player ->
                    player.copy(hand = listOf(HandCard("hand-1", CardData.epochOne.first())))
                }
        assertThat(state.legalChoices(p1).commands).doesNotContain("pass")
        assertThat(state.legalChoices(p1).commands).contains("play hand-1 commodity stone")
    }

    @Test
    fun stabilizationOffersMiseryWhenCashIsInsufficient() {
        val state =
            purchasing(PurchaseStep.STABILIZATION, cash = 0).updatePlayer(p1) { player ->
                player.copy(hand = listOf(HandCard("hand-1", CardData.epochOne.first())))
            }
        assertThat(state.legalChoices(p1).commands).containsExactly("stabilize misery")
    }

    @Test
    fun unaffordableReliefRemainsAnExplicitPassDecision() {
        val tier =
            listOf(
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.PATRONAGE,
                    AdvanceId.CARAVAN,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.OVERLAND_EAST,
                    AdvanceId.URBAN_ASCENDANCY,
                )
                .fold(PlayerAdvances()) { owned, advance -> owned.acquire(advance, 1) }
        val initial =
            purchasing(PurchaseStep.RELIEF, cash = 0).updatePlayer(p1) { player ->
                player.copy(advances = tier, misery = MiseryStep.TWENTY)
            }

        val state = TurnEngine.normalise(initial)
        val offers = state.viewForPlayer(p1).reliefOffers

        assertThat(state.flow.purchaseStep).isEqualTo(PurchaseStep.RELIEF)
        assertThat(state.flow.actor).isEqualTo(p1)
        assertThat(state.legalChoices(p1).commands).containsExactly("pass")
        assertThat(offers).isNotEmpty()
        assertThat(offers.all { offer -> !offer.affordable }).isTrue()
    }

    @Test
    fun stabilizationPreviewUsesTheDestinationPrintedBox() {
        val state =
            purchasing(PurchaseStep.STABILIZATION, cash = 0).updatePlayer(p1) { player ->
                player.copy(
                    misery = MiseryStep.HUNDRED,
                    hand = listOf(HandCard("hand-1", CardData.epochOne.first())),
                )
            }

        val preview = state.viewForPlayer(p1).stabilizationMisery
        val stabilized = state.step(TextCodec.parse("p1: stabilize misery"))

        assertThat(preview).isEqualTo(125)
        assertThat(stabilized.playerState(p1).misery.value).isEqualTo(preview)
    }

    private fun purchasing(step: PurchaseStep, cash: Int): GameState {
        val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
        val header = GameHeader(players, p1)
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header,
            stage = Stage.PURCHASE,
            turnNumber = 2,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to PlayerState(player, capital = capitals[index], cash = cash)
                    }
                    .toMap(),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow =
                TurnFlow(
                    actor = p1,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = step,
                ),
        )
    }
}
