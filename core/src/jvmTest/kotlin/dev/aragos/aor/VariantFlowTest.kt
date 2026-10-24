package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

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

    @Test
    fun secondEditionCommandsUseSelectedCommerceLettersAndPrices() {
        val initial =
            purchase(RuleOptions(commerce = CommerceRule.SECOND_EDITION))
                .withAdvances(p1, AdvanceId.CARAVAN)

        val purchased = initial.play("p1: buy-advance J", "p1: buy-advance K")

        assertThat(purchased.playerState(p1).advances.owned)
            .containsExactly(
                AdvanceId.CARAVAN,
                AdvanceId.IMPROVED_AGRICULTURE,
                AdvanceId.WIND_WATERMILL,
            )
        assertThat(purchased.playerState(p1).cash).isEqualTo(930)
        assertThat(purchased.playerState(p1).advances.score(purchased.rules)).isEqualTo(110)
    }

    @Test
    fun secondEditionWindWatermillRequiresSelectedAgricultureFirst() {
        val initial =
            purchase(RuleOptions(commerce = CommerceRule.SECOND_EDITION))
                .withAdvances(p1, AdvanceId.CARAVAN)

        val failure = assertFailsWith<RuleViolation> { initial.play("p1: buy-advance K") }

        assertThat(failure.code).isEqualTo(ViolationCode.MISSING_ADVANCE_PREREQUISITE)
        assertThat(failure.entityIds).containsExactly("J")
    }

    @Test
    fun completionReliefIsAppliedInThePurchaseCommand() {
        val initial =
            purchase(RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION))
                .withAdvances(
                    p1,
                    AdvanceId.THE_HEAVENS,
                    AdvanceId.HUMAN_BODY,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                    AdvanceId.WRITTEN_RECORD,
                )
                .updatePlayer(p1) { owner -> owner.copy(misery = MiseryStep.HUNDRED) }

        val purchased = initial.play("p1: buy-advance O")

        assertThat(purchased.playerState(p1).misery).isEqualTo(MiseryStep.NINETY)
        assertThat(purchased.flow.purchaseStep).isEqualTo(PurchaseStep.ADVANCES)
    }

    @Test
    fun completionReliefDoesNotPermitTheCashReliefCommand() {
        val initial =
            purchase(RuleOptions(miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION))
                .copy(flow = purchaseFlow(PurchaseStep.RELIEF))

        val failure = assertFailsWith<RuleViolation> { initial.play("p1: relieve-misery 1") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_MISERY_RELIEF)
    }

    @Test
    fun lastWindWatermillOwnerActsAfterFirstPlayersBuyoff() {
        val initial =
            marketDraw()
                .withAdvances(p1, AdvanceId.WIND_WATERMILL)
                .withAdvances(p2, AdvanceId.WIND_WATERMILL)
                .copy(adjustments = mapOf(Commodity.GRAIN to listOf(CommodityAdjustment.SHORTAGE)))
                .normalise()

        val adjusted = initial.play("p1: cancel-adjustment grain shortage")

        assertThat(adjusted.prompts()).containsExactly(Prompt.SinglePlayer(p2, "adjust-market"))
        assertThat(adjusted.flow.drawn).isEmpty()
        assertThat(adjusted.legalChoices(p2).commands).contains("adjust-market grain surplus")
    }

    @Test
    fun marketAdjustmentResumesTheFirstPlayersRecordedDraw() {
        val initial = marketDraw().withAdvances(p2, AdvanceId.WIND_WATERMILL).normalise()

        val adjusted = initial.play("p2: adjust-market wine shortage")

        assertThat(adjusted.adjustments[Commodity.WINE])
            .containsExactly(CommodityAdjustment.SHORTAGE)
        assertThat(adjusted.prompts().single()).isInstanceOf(Prompt.System::class.java)
        val need = (adjusted.prompts().single() as Prompt.System).need as SystemNeed.Draw
        assertThat(need.player).isEqualTo(p1)
    }

    @Test
    fun marketAdjustmentCannotDirectlyFlipShortageToSurplus() {
        val initial =
            marketDraw()
                .withAdvances(p2, AdvanceId.WIND_WATERMILL)
                .copy(adjustments = mapOf(Commodity.GRAIN to listOf(CommodityAdjustment.SHORTAGE)))
                .normalise()
                .play("p1: pass")

        val failure =
            assertFailsWith<CardRuleViolation> {
                initial.play("p2: adjust-market grain surplus")
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_CHOICE)
    }

    @Test
    fun chaosOwnerIsSkippedForTheMarketAdjustment() {
        val initial =
            marketDraw()
                .withAdvances(p1, AdvanceId.WIND_WATERMILL)
                .withAdvances(p2, AdvanceId.WIND_WATERMILL)
                .updatePlayer(p2) { owner -> owner.copy(misery = MiseryStep.CHAOS) }

        val adjusted = initial.normalise()

        assertThat(adjusted.prompts()).containsExactly(Prompt.SinglePlayer(p1, "adjust-market"))
    }

    @Test
    fun writtenRecordTradesWithoutDiceOrExpansionPayment() {
        val victory = takeover().play("p1: attack florence", "!roll competition 1 6 6")
        val expansionTokens = victory.playerState(p1).poolTokens

        val traded =
            victory.play(
                "p1: written-record",
                "p1: offer-card hand-1",
                "!trade p2 p1 hand-2",
            )

        assertThat(traded.playerState(p1).hand.map(HandCard::reference)).containsExactly("hand-2")
        assertThat(traded.playerState(p2).hand.map(HandCard::reference)).containsExactly("hand-1")
        assertThat(traded.playerState(p1).poolTokens).isEqualTo(expansionTokens)
        assertThat(traded.flow.actor).isEqualTo(p1)
        assertThat(traded.flow.decisions).isEmpty()
    }

    @Test
    fun bothTakeoverAbilitiesResolveInOrderWithoutLosingTheOuterActivity() {
        val initial =
            takeover()
                .copy(rules = RuleOptions(writtenRecord = WrittenRecordRule.TAKEOVER_TRADE))
                .withAdvances(
                    p1,
                    AdvanceId.WRITTEN_RECORD,
                    AdvanceId.CARAVAN,
                    AdvanceId.WIND_WATERMILL,
                )
        val victory = initial.play("p1: attack florence", "!roll competition 1 6 6")
        val opportunities = victory.flow.decisions.map(TurnDecision::choice)
        val references = victory.players.values.flatMap(PlayerState::hand).map(HandCard::reference)

        val tradedTwice =
            victory.play(
                "p1: windmill",
                "!roll windmill 1",
                "p1: offer-card hand-1",
                "!trade p2 p1 hand-2",
                "p1: written-record",
                "p1: offer-card hand-2",
                "!trade p2 p1 hand-1",
            )

        assertThat(opportunities).containsExactly("written-record", "windmill").inOrder()
        assertThat(tradedTwice.flow.windmillUsed).containsExactly(p1)
        assertThat(tradedTwice.flow.writtenRecordUsed).containsExactly(p1)
        assertThat(tradedTwice.flow.decisions).isEmpty()
        assertThat(tradedTwice.flow.actor).isEqualTo(p1)
        assertThat(tradedTwice.players.values.flatMap(PlayerState::hand).map(HandCard::reference))
            .containsExactlyElementsIn(references)
        assertThat(tradedTwice.playerState(p1).hand.single().reference).isEqualTo("hand-1")
        assertThat(tradedTwice.playerState(p2).hand.single().reference).isEqualTo("hand-2")
    }

    @Test
    fun writtenRecordCannotTradeAgainAfterAnotherTakeover() {
        val traded =
            takeover()
                .play(
                    "p1: attack florence",
                    "!roll competition 1 6 6",
                    "p1: written-record",
                    "p1: offer-card hand-1",
                    "!trade p2 p1 hand-2",
                )

        val secondVictory = traded.play("p1: attack rome", "!roll competition 1 6 6")

        assertThat(secondVictory.flow.decisions).isEmpty()
        assertThat(secondVictory.flow.writtenRecordUsed).containsExactly(p1)
    }

    @Test
    fun writtenRecordCannotForceTradeAgainstAnotherOwner() {
        val initial = takeover().withAdvances(p2, AdvanceId.WRITTEN_RECORD)

        val victory = initial.play("p1: attack florence", "!roll competition 1 6 6")

        assertThat(victory.flow.decisions).isEmpty()
        assertThat(victory.flow.writtenRecordUsed).isEmpty()
    }

    @Test
    fun firstAlternateDisplayBoxCannotBePatronized() {
        val initial = leaderDisplay()

        val failure =
            assertFailsWith<CardRuleViolation> {
                initial.play("p1: patronize st-benedict")
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_LEADER_CLAIM)
    }

    @Test
    fun secondAlternateDisplayBoxPermitsOnlyOneClaim() {
        val claimed =
            leaderDisplay()
                .play("p1: patronize charlemagne")
                .copy(flow = purchaseFlow().copy(actor = p2, cursor = 1))

        val failure =
            assertFailsWith<CardRuleViolation> {
                claimed.play("p2: patronize charlemagne")
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_LEADER_CLAIM)
    }

    @Test
    fun thirdAlternateDisplayBoxPermitsTwoFreeClaims() {
        val claimed =
            leaderDisplay()
                .play("p1: patronize walter-the-penniless")
                .copy(flow = purchaseFlow().copy(actor = p2, cursor = 1))

        val twiceClaimed = claimed.play("p2: patronize walter-the-penniless")

        assertThat(twiceClaimed.effects.leaders.last().patronageClaimants).containsExactly(p1, p2)
        assertThat(twiceClaimed.playerState(p1).cash).isEqualTo(1000)
        assertThat(twiceClaimed.playerState(p2).cash).isEqualTo(40)
    }

    private fun marketDraw(): GameState =
        purchase(RuleOptions(windWatermill = WindWatermillRule.MARKET_ADJUSTMENT))
            .copy(stage = Stage.DRAW_CARD, flow = purchaseFlow(), deck = listOf("wool-1"))

    private fun takeover(): GameState =
        purchase(
                RuleOptions(
                    writtenRecord = WrittenRecordRule.TAKEOVER_TRADE,
                    windWatermill = WindWatermillRule.MARKET_ADJUSTMENT,
                )
            )
            .copy(
                stage = Stage.EXPANSION,
                board =
                    BoardState()
                        .placeMarker(ProvinceId("venice"), p1)
                        .board
                        .placeMarker(ProvinceId("florence"), p2)
                        .board
                        .placeMarker(ProvinceId("rome"), p2)
                        .board,
            )
            .withAdvances(p1, AdvanceId.WRITTEN_RECORD, AdvanceId.CARAVAN)
            .withCard(p1, "wool-1", "hand-1")
            .withCard(p2, "stone-1", "hand-2")
            .updatePlayer(p1) { owner -> owner.copy(poolTokens = 20, stockTokens = 16) }

    private fun leaderDisplay(): GameState =
        purchase(RuleOptions(patronage = PatronageRule.THREE_BOX_DISPLAY))
            .copy(stage = Stage.PLAY_CARDS, flow = purchaseFlow().copy(actor = p3, cursor = 2))
            .withCard(p3, "st-benedict", "first")
            .withCard(p3, "charlemagne", "second")
            .withCard(p3, "walter-the-penniless", "third")
            .withAdvances(p1, AdvanceId.PATRONAGE)
            .withAdvances(p2, AdvanceId.PATRONAGE)
            .play("p3: play first", "p3: play second", "p3: play third")
            .copy(stage = Stage.PURCHASE, flow = purchaseFlow())

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
