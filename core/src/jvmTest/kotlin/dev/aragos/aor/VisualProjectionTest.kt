package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class VisualProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val florence = ProvinceId("florence")

    @Test
    fun anotherPlayerCanSeeRecordedWrittenCashIndependentlyOfCurrentCash() {
        val initial = state()
        val player = initial.playerState(p1).copy(cash = 23, writtenCash = 72)
        val changed = initial.copy(players = initial.players + (p1 to player))

        val view = changed.viewForPlayer(p2).players.single { it.id == p1 }

        assertThat(view.cash).isEqualTo(23)
        assertThat(view.writtenCash).isEqualTo(72)
    }

    @Test
    fun inspectingCardsOutsideTheHandUsesPublicCommodityFacts() {
        val state = state()
        val inspection =
            state.viewForPlayer(p1).cardInspections.single {
                it.definition.id == "cloth-wine"
            }

        assertThat(state.playerState(p1).hand).isEmpty()
        assertThat(inspection.commodities).containsExactly(Commodity.CLOTH, Commodity.WINE)
        assertThat(state.viewForPlayer(p2).cardInspections.map { it.definition })
            .containsExactlyElementsIn(
                state.viewForPlayer(p1).cardInspections.map { it.definition }
            )
    }

    @Test
    fun inspectingAHistoricalLeaderIncludesTheViewersWrittenRecordBonus() {
        val state = state(advances = setOf(AdvanceId.WRITTEN_RECORD))
        val discount =
            state
                .viewForPlayer(p1)
                .cardInspections
                .single {
                    it.definition.id == "charlemagne"
                }
                .leaderDiscounts
                .single()

        assertThat(discount.conditionalAmount).isEqualTo(20)
        assertThat(discount.writtenRecordBonus).isEqualTo(10)
        assertThat(discount.effectiveDiscount).isEqualTo(30)
    }

    @Test
    fun inspectionRetainsAPlayedLeadersOriginalEnhancement() {
        val state =
            state()
                .copy(
                    voids = CardVoids(crusades = true),
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.MARCO_POLO,
                                        p1,
                                        CardLeaderRole.DISPLAY,
                                        mongolEnhanced = false,
                                    )
                                )
                        ),
                )

        assertThat(
                state
                    .viewForPlayer(p1)
                    .cardInspections
                    .single {
                        it.definition.id == "marco-polo"
                    }
                    .leaderDiscounts
            )
            .isEqualTo(state.viewForPlayer(p1).playedLeaderDiscounts.single().discounts)
    }

    @Test
    fun anotherPlacementPreservesTheDecisionIdentity() {
        val before = state()
        val after = before.step(Entry.Action(p1, TextCodec.parseCommand("place 1 florence")))

        assertThat(after.viewForPlayer(p1).activity?.decisionEntry)
            .isEqualTo(before.viewForPlayer(p1).activity?.decisionEntry)
    }

    @Test
    fun successiveReplacementDecisionsForTheSamePlayerHaveDifferentIdentities() {
        val before =
            state()
                .copy(
                    flow =
                        state()
                            .flow
                            .copy(
                                decisions =
                                    listOf(
                                        CardDecision.ReplacementToken(
                                            p1,
                                            ProvinceId("rome"),
                                            EventId.BLACK_DEATH,
                                        ),
                                        CardDecision.ReplacementToken(
                                            p1,
                                            florence,
                                            EventId.BLACK_DEATH,
                                        ),
                                    )
                            )
                )
        val after =
            before.step(Entry.Action(p1, TextCodec.parseCommand("replace-event-token none")))

        assertThat(after.viewForPlayer(p1).activity?.decisionEntry)
            .isGreaterThan(before.decisionStart)
        assertThat(after.viewForPlayer(p1).activity?.choice).isEqualTo("replace-event-token")
        assertThat(after.viewForPlayer(p1).activity?.decisionPlayer).isEqualTo(p1)
        assertThat(after.viewForPlayer(p1).activity?.startEntry).isEqualTo(before.activityStart)
    }

    @Test
    fun markerQuoteAgreesWithExecutionAndDoesNotChangeTheSource() {
        val state = state()
        val offer = offer(state)
        val placed =
            state.step(
                Entry.Action(p1, TextCodec.parseCommand("place ${offer.requiredTokens} florence"))
            )

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.PLACE)
        assertThat(offer.requiredTokens).isEqualTo(4)
        assertThat(offer.resultingPool).isEqualTo(placed.playerState(p1).poolTokens)
        assertThat(offer.incomeAfter)
            .isEqualTo(placed.viewForPlayer(p1).players.first().income?.ordinary)
        assertThat(state.board.pieces).isEmpty()
        assertThat(state.playerState(p1).poolTokens).isEqualTo(20)
        assertThat(placed.board.pieces[florence])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
    }

    @Test
    fun partialOccupationReducesRequiredNewTokens() {
        val board =
            BoardState(
                persistentMapOf(
                    florence to
                        ProvincePieces.Tokens(persistentMapOf(p1 to TokenHolding(coloured = 2)))
                )
            )
        val offer = offer(state(board))

        assertThat(offer.requiredTokens).isEqualTo(2)
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1)
    }

    @Test
    fun insufficientPoolBlocksMarkerButKeepsPartialPlacement() {
        val offer = offer(state(pool = 2))

        assertThat(offer.blockers).containsExactly(ViolationCode.INSUFFICIENT_EXPANSION_TOKENS)
        assertThat(offer.resultingPool).isNull()
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1, 2)
    }

    @Test
    fun contestedMarketUsesExactAdditionalCommitmentWithoutResolvingCompetition() {
        val board =
            BoardState(
                persistentMapOf(
                    florence to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )
        val state = state(board)
        val offer = offer(state)
        val pending = state.step(Entry.Action(p1, TextCodec.parseCommand("attack florence")))

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.ATTACK)
        assertThat(offer.requiredTokens).isEqualTo(4)
        assertThat(offer.resultingPool).isEqualTo(pending.playerState(p1).poolTokens)
        assertThat(offer.incomeAfter).isNull()
        assertThat(offer.uncertain).isTrue()
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1, 2)
        assertThat(state.flow.decisions).isEmpty()
        assertThat(pending.flow.decisions).isNotEmpty()
    }

    @Test
    fun friendlyWhiteMarkerIsInspectionOnly() {
        val offer = offer(state(BoardState().placeMarker(florence, p1, PieceSide.WHITE).board))

        assertThat(offer.primaryAction).isEqualTo(ExpansionAction.INSPECT)
        assertThat(offer.partialPlacements).isEmpty()
    }

    @Test
    fun otherPlayerGetsNoExpansionActions() {
        assertThat(state().viewForPlayer(p2).expansionOffers).isEmpty()
    }

    @Test
    fun accessRequirementsAreTypedBlockers() {
        val offer =
            state().viewForPlayer(p1).expansionOffers.single {
                it.destination == ProvinceId("china")
            }

        assertThat(offer.blockers).containsExactly(ViolationCode.PROVINCE_ACCESS_DENIED)
    }

    @Test
    fun whiteMarkersCountInLiveValuesWithoutChangingActualCommodityPayments() {
        val board = BoardState().placeMarker(florence, p1, PieceSide.WHITE).board
        val view = state(board).viewForPlayer(p1)
        val commodities = ProvinceData.definition(florence).commodities

        assertThat(
                view.commodityPayouts
                    .filter { it.commodity in commodities }
                    .flatMap(CommodityPayoutView::owners)
            )
            .isNotEmpty()
        assertThat(
                CardRules.commodityPayment(state(board).cardContext(), commodities.first())
                    .payments[p1]
            )
            .isEqualTo(0)
        assertThat(view.players.first().income?.ordinary)
            .isGreaterThan(state().viewForPlayer(p1).players.first().income?.ordinary ?: -1)
    }

    @Test
    fun zeroPayingOwnersRemainAndOnlyOneQueuedAdjustmentApplies() {
        val commodity = ProvinceData.definition(florence).commodities.first()
        val board = BoardState().placeMarker(florence, p1).board
        val state =
            state(board)
                .copy(
                    adjustments =
                        mapOf(
                            commodity to
                                listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS)
                        )
                )
        val payout = state.viewForPlayer(p1).commodityPayouts.single { it.commodity == commodity }

        assertThat(payout.owners).hasSize(1)
        assertThat(payout.owners.single().payout).isEqualTo(0)
        assertThat(payout.owners.single().marketCount).isEqualTo(1)
        assertThat(payout.adjustments).hasSize(2)
    }

    @Test
    fun industryAndFirstShortageAgreeWithTheActualPayment() {
        val commodity = ProvinceData.definition(florence).commodities.first()
        val board = BoardState().placeMarker(florence, p1).board
        val state =
            state(board, advances = setOf(AdvanceId.INDUSTRY))
                .copy(
                    adjustments =
                        mapOf(
                            commodity to
                                listOf(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SHORTAGE)
                        )
                )
        val owner =
            state
                .viewForPlayer(p1)
                .commodityPayouts
                .single { it.commodity == commodity }
                .owners
                .single()

        assertThat(owner.effectiveBox).isEqualTo(3)
        assertThat(owner.payout)
            .isEqualTo(CardRules.commodityPayment(state.cardContext(), commodity).payments[p1])
    }

    @Test
    fun incomeSeparatesConditionalProfitFromOrdinaryIncome() {
        val state = state(advances = setOf(AdvanceId.MIDDLE_CLASS, AdvanceId.INTEREST_PROFIT))
        val player = state.playerState(p1)
        val quote = PlayerEconomy.incomeQuote(player, 3, 0)
        val income = state.viewForPlayer(p1).players.first().income

        assertThat(income?.ordinary).isEqualTo(quote.income)
        assertThat(income?.middleClassBonus).isEqualTo(10)
        assertThat(income?.interestProfit).isEqualTo(quote.interestProfit)
    }

    @Test
    fun setupIncomeIsExplicitlyUnavailable() {
        val setup = GameState(state().header)

        assertThat(setup.viewForPlayer(p1).players.map(PlayerView::income))
            .containsExactly(null, null, null)
    }

    @Test
    fun chaosIncomeIsExplicitlyUnavailable() {
        val state = state()
        val chaos =
            state.copy(
                players =
                    state.players + (p2 to state.playerState(p2).copy(misery = MiseryStep.CHAOS))
            )

        assertThat(chaos.viewForPlayer(p1).players.single { it.id == p2 }.income).isNull()
    }

    @Test
    fun potentialLeaderDiscountDoesNotReduceAnUnplayedAdvanceOffer() {
        val state = withHand(state(advances = setOf(AdvanceId.WRITTEN_RECORD)), "charlemagne")
        val view = state.viewForPlayer(p1)
        val discount = view.cardOffers.single().leaderDiscounts.single()
        val advance = view.advanceOffers.single { it.advance == AdvanceId.NATIONALISM }

        assertThat(discount.printedAmount).isEqualTo(20)
        assertThat(discount.effectiveDiscount).isEqualTo(30)
        assertThat(advance.price)
            .isEqualTo(
                AdvancePurchases.quote(
                        state.playerState(p1),
                        AdvanceId.NATIONALISM,
                        state.purchaseContext(p1),
                    )
                    .price
            )
        assertThat(view.cardOffers.single().playable).isFalse()
    }

    @Test
    fun dualCommodityCardOffersBothPrintedCommodities() {
        val view = withHand(state().copy(stage = Stage.PLAY_CARDS), "cloth-wine").viewForPlayer(p1)

        assertThat(view.cardOffers.single().commodities)
            .containsExactly(Commodity.CLOTH, Commodity.WINE)
        assertThat(view.cardOffers.single().choices.map(CardChoiceView::kind))
            .containsExactly(CardChoiceKind.COMMODITY, CardChoiceKind.COMMODITY)
        assertThat(view.cardOffers.single().playable).isTrue()
    }

    @Test
    fun anotherPlayerCannotUseHandChoicesOutsideTheirDecision() {
        val state =
            withHand(
                state().copy(stage = Stage.PLAY_CARDS, flow = TurnFlow(actor = p2)),
                "cloth-wine",
            )
        val offer = state.viewForPlayer(p1).cardOffers.single()

        assertThat(offer.choices).isEmpty()
        assertThat(offer.blockers).containsExactly(CardOfferBlocker.WRONG_PLAYER)
    }

    @Test
    fun arbitraryOverStockTokenPurchaseUsesFullCostWithoutEnumeratingCash() {
        val state = state().copy(stage = Stage.TOKEN_PURCHASE)
        val buyer = state.playerState(p1).copy(cash = 1_000_000)
        val rich = state.copy(players = state.players + (p1 to buyer))
        val quote = TokenPurchaseProjection.quote(rich, p1, 500_000)

        assertThat(quote.cost).isEqualTo(500_000)
        assertThat(quote.tokensReceived).isEqualTo(buyer.stockTokens)
        assertThat(quote.cashAfter).isEqualTo(500_000)
        assertThat(rich.viewForPlayer(p1).tokenPurchaseOffers.size).isAtMost(75)
    }

    @Test
    fun negativeTokenPurchaseSpendsCashWithoutReceivingTokens() {
        val quote =
            TokenPurchaseProjection.quote(state().copy(stage = Stage.TOKEN_PURCHASE), p1, -7)

        assertThat(quote.cost).isEqualTo(7)
        assertThat(quote.tokensReceived).isEqualTo(0)
        assertThat(quote.poolAfter).isEqualTo(20)
    }

    @Test
    fun tokenQuoteRejectsWrongPhase() {
        val failure =
            assertFailsWith<RuleViolation> { TokenPurchaseProjection.quote(state(), p1, 1) }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun missingMarkerStockKeepsLegalPartialPlacement() {
        val board =
            CatalogConfiguration(3)
                .provinces
                .filter { province -> !province.isSatellite && province.id != florence }
                .take(25)
                .fold(BoardState()) { current, province ->
                    current.placeMarker(province.id, p1).board
                }
        val offer = offer(state(board))

        assertThat(offer.blockers).containsExactly(ViolationCode.NO_DOMINATION_MARKERS)
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens))
            .containsExactly(1, 2, 3)
    }

    @Test
    fun satelliteUsesTokenPlacementWithoutMarkerIncome() {
        val initial = state(BoardState().replaceWithToken(ProvinceId("acre"), p1).board)
        val offer =
            initial.viewForPlayer(p1).expansionOffers.first { candidate ->
                ProvinceData.definition(candidate.destination).isSatellite &&
                    candidate.blockers.isEmpty()
            }
        val placed =
            initial.step(
                Entry.Action(
                    p1,
                    TextCodec.parseCommand("place ${offer.requiredTokens} ${offer.destination}"),
                )
            )

        assertThat(offer.requiredTokens).isEqualTo(1)
        assertThat(offer.establishesMarker).isFalse()
        assertThat(offer.incomeAfter).isEqualTo(offer.incomeBefore)
        assertThat(placed.board.pieces[offer.destination])
            .isInstanceOf(ProvincePieces.Tokens::class.java)
    }

    @Test
    fun shippingCapacityBelongsToTheSelectedDestination() {
        val initial = state()
        val player = initial.playerState(p1).copy(ships = ShippingCapability.GALLEY_4)
        val state =
            initial.copy(
                players = initial.players + (p1 to player),
                flow = initial.flow.copy(shipped = mapOf(p1 to mapOf(ProvinceId("rome") to 3))),
            )
        val offer =
            state.viewForPlayer(p1).expansionOffers.single { it.destination == ProvinceId("rome") }

        assertThat(offer.blockers).containsExactly(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(offer.shipping?.used).isEqualTo(3)
        assertThat(offer.shipping?.capacity).isEqualTo(4)
        assertThat(offer.partialPlacements.map(PartialPlacementView::tokens)).containsExactly(1)
    }

    @Test
    fun competitionDestinationIsOmittedWhenThePoolCannotMeetItsMinimum() {
        val board = BoardState().placeMarker(florence, p2).board
        val state = state(board, pool = 1)

        assertThat(state.viewForPlayer(p1).expansionOffers.map(ExpansionOfferView::destination))
            .doesNotContain(florence)
    }

    @Test
    fun competitionDestinationIsOmittedWhenShippingCannotMeetItsMinimum() {
        val rome = ProvinceId("rome")
        val initial = state(BoardState().placeMarker(rome, p2).board)
        val state =
            initial.copy(
                players =
                    initial.players +
                        (p1 to initial.playerState(p1).copy(ships = ShippingCapability.GALLEY_4)),
                flow = initial.flow.copy(shipped = mapOf(p1 to mapOf(rome to 3))),
            )

        assertThat(state.viewForPlayer(p1).expansionOffers.map(ExpansionOfferView::destination))
            .doesNotContain(rome)
    }

    @Test
    fun exhaustedDestinationShippingOmitsPeacefulPlacements() {
        val rome = ProvinceId("rome")
        val initial = state()
        val state =
            initial.copy(
                players =
                    initial.players +
                        (p1 to initial.playerState(p1).copy(ships = ShippingCapability.GALLEY_4)),
                flow = initial.flow.copy(shipped = mapOf(p1 to mapOf(rome to 4))),
            )

        assertThat(state.viewForPlayer(p1).expansionOffers.map(ExpansionOfferView::destination))
            .doesNotContain(rome)
    }

    @Test
    fun caravanRouteDoesNotChargeExhaustedShipping() {
        val initial = state(advances = setOf(AdvanceId.CARAVAN))
        val player = initial.playerState(p1).copy(ships = ShippingCapability.GALLEY_4)
        val state =
            initial.copy(
                players = initial.players + (p1 to player),
                flow = initial.flow.copy(shipped = mapOf(p1 to mapOf(ProvinceId("rome") to 4))),
            )
        val offer =
            state.viewForPlayer(p1).expansionOffers.single { it.destination == ProvinceId("rome") }

        assertThat(offer.blockers).isEmpty()
        assertThat(offer.shipping).isNull()
    }

    @Test
    fun conditionalLeaderCreditIncludesEventAndWrittenRecordBonuses() {
        val state =
            withHand(state(advances = setOf(AdvanceId.WRITTEN_RECORD)), "walter-the-penniless")
                .copy(effects = CardTurnEffects(crusadesPlayed = true))
        val discount = state.viewForPlayer(p1).cardOffers.single().leaderDiscounts.single()

        assertThat(discount.printedAmount).isEqualTo(20)
        assertThat(discount.conditionalAmount).isEqualTo(30)
        assertThat(discount.writtenRecordBonus).isEqualTo(10)
        assertThat(discount.effectiveDiscount).isEqualTo(40)
    }

    @Test
    fun noTargetEventRetainsItsLegalNoEffectCommand() {
        val offer =
            withHand(state().copy(stage = Stage.PLAY_CARDS), "rebellion")
                .viewForPlayer(p1)
                .cardOffers
                .single()

        assertThat(offer.playable).isTrue()
        assertThat(offer.choices.single().kind).isEqualTo(CardChoiceKind.NONE)
        assertThat(offer.choices.single().command).isEqualTo("play hand-1")
    }

    @Test
    fun pirateTargetsStayCompactAndRequireTheCurrentEpochCount() {
        val initial = state()
        val board =
            CatalogConfiguration(3)
                .provinces
                .filter { province -> province.coastal && !province.isSatellite }
                .take(6)
                .fold(BoardState()) { current, province ->
                    current.placeMarker(province.id, p2).board
                }
        val offer =
            withHand(
                    initial.copy(stage = Stage.PLAY_CARDS, board = board, currentEpoch = Epoch.TWO),
                    "pirates-vikings",
                )
                .viewForPlayer(p1)
                .cardOffers
                .single()

        assertThat(offer.provinceTargets).hasSize(6)
        assertThat(offer.requiredTargetCount).isEqualTo(2)
        assertThat(offer.choices)
            .containsExactly(CardChoiceView(CardChoiceKind.PROVINCES, null, null))
    }

    @Test
    fun exposedLeaderProtectionIncludesImmediatePrintedWordRebate() {
        val state =
            withHand(
                    state(advances = setOf(AdvanceId.PRINTED_WORD, AdvanceId.NATIONALISM)),
                    "charlemagne",
                )
                .copy(
                    stage = Stage.PLAY_CARDS,
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.ST_BENEDICT,
                                        p2,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        ),
                )
        val offer = state.viewForPlayer(p1).cardOffers.single()

        assertThat(offer.protectionMax).isEqualTo(120)
        assertThat(offer.choices.map(CardChoiceView::kind))
            .containsExactly(CardChoiceKind.NONE, CardChoiceKind.PROTECTION)
    }

    @Test
    fun shippingOfferAgreesWithPurchase() {
        val state =
            state()
                .copy(
                    stage = Stage.PURCHASE,
                    flow = TurnFlow(actor = p1, purchaseStep = PurchaseStep.SHIPPING),
                )
        val offer = state.viewForPlayer(p1).shippingOffer
        val after = ShippingPurchases.upgrade(state.playerState(p1), 1).player

        assertThat(offer?.ships).isEqualTo(after.ships)
        assertThat(offer?.cashAfter).isEqualTo(after.cash)
        assertThat(offer?.blockers).isEmpty()
    }

    @Test
    fun shippingOfferIsAbsentForWaitingPlayer() {
        val state =
            state()
                .copy(
                    stage = Stage.PURCHASE,
                    flow = TurnFlow(actor = p1, purchaseStep = PurchaseStep.SHIPPING),
                )

        assertThat(state.viewForPlayer(p2).shippingOffer).isNull()
    }

    @Test
    fun militaryPreparationOffersTheSameCardThatExecutionAccepts() {
        val initial =
            withHand(state(), "armor")
                .copy(
                    stage = Stage.PLAY_CARDS,
                    activeWar = WarState(p1, p2, 1),
                    flow =
                        TurnFlow(actor = p1, decisions = listOf(CardDecision.WarPreparation(p1))),
                )
        val offer = initial.viewForPlayer(p1).cardOffers.single()
        val played = CardGame.play(initial, p1, "hand-1", CardChoice.None)

        assertThat(offer.playable).isTrue()
        assertThat(offer.choices.single().command).isEqualTo("play hand-1")
        assertThat(played.effects.military).contains(CardMilitaryPlay(p1, EventId.ARMOR))
    }

    @Test
    fun victimInterruptionDoesNotAuthorizeOuterActorsCards() {
        val initial =
            withHand(state(), "armor")
                .copy(
                    stage = Stage.PLAY_CARDS,
                    flow = TurnFlow(actor = p1, decisions = listOf(CardDecision.CivilWarLoss(p2))),
                )
        val offer = initial.viewForPlayer(p1).cardOffers.single()

        assertThat(offer.playable).isFalse()
        assertThat(offer.choices).isEmpty()
    }

    @Test
    fun leaderAlreadyPlayedProducesBlockedOfferRatherThanFailingView() {
        val initial =
            withHand(state(), "charlemagne")
                .copy(
                    stage = Stage.PLAY_CARDS,
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        p1,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        ),
                )
        val offer = initial.viewForPlayer(p1).cardOffers.single()

        assertThat(offer.playable).isFalse()
        assertThat(offer.protectionMax).isNull()
        assertThat(offer.blockers).containsExactly(CardOfferBlocker.INVALID_CHOICE)
    }

    @Test
    fun noPoolOmitsDestinationsWithoutAnyPossiblePlacement() {
        val offers = state(pool = 0).viewForPlayer(p1).expansionOffers

        assertThat(offers.map(ExpansionOfferView::destination)).doesNotContain(florence)
    }

    @Test
    fun inaccessibleProvinceIsNotReachable() {
        val offer =
            state().viewForPlayer(p1).expansionOffers.single {
                it.destination == ProvinceId("china")
            }

        assertThat(offer.reachable).isFalse()
    }

    @Test
    fun playedLeaderCreditsAreIncludedInTheAdvancePrice() {
        val state =
            state(advances = setOf(AdvanceId.WRITTEN_RECORD))
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        p1,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        )
                )
        val offer =
            state.viewForPlayer(p1).advanceOffers.single {
                it.advance == AdvanceId.NATIONALISM
            }
        val quote =
            AdvancePurchases.quote(
                state.playerState(p1),
                AdvanceId.NATIONALISM,
                state.purchaseContext(p1),
            )

        assertThat(offer.leaderDiscounts).isEqualTo(quote.leaders)
        assertThat(offer.leaderDiscounts.single().amount).isEqualTo(30L)
        assertThat(offer.price).isEqualTo(quote.price)
    }

    @Test
    fun playerWithoutATierHasNoReliefPurchaseOffers() {
        assertThat(state().viewForPlayer(p1).reliefOffers).isEmpty()
    }

    @Test
    fun expansionPassProjectsCompletionWithoutHidingOtherPowers() {
        val after = state().step(Entry.Action(p1, TurnCommand.Pass))
        val players = after.viewForPlayer(p1).players

        assertThat(players.single { it.id == p1 }.poolAvailable).isFalse()
        assertThat(players.single { it.id == p2 }.poolAvailable).isTrue()
    }

    @Test
    fun privatePurchaseDoesNotExposeAnExpansionPool() {
        val beforeReveal = state().copy(stage = Stage.TOKEN_PURCHASE)

        assertThat(beforeReveal.viewForPlayer(p1).players.map { it.poolAvailable })
            .containsExactly(false, false, false)
    }

    @Test
    fun revealedEmptyPoolIsAvailableBeforeExpansion() {
        val afterReveal = state(pool = 0).copy(stage = Stage.TURN_ORDER_TIE)

        assertThat(PlayerView.from(afterReveal.playerState(p1), afterReveal).poolAvailable).isTrue()
    }

    @Test
    fun incomeDoesNotExposeAnExpansionPool() {
        val income = state().copy(stage = Stage.INCOME)

        assertThat(income.viewForPlayer(p1).players.map { it.poolAvailable })
            .containsExactly(false, false, false)
    }

    @Test
    fun playedLeaderDiscountKeepsItsPlayTimeEnhancement() {
        val state =
            state(advances = setOf(AdvanceId.WRITTEN_RECORD))
                .copy(
                    voids = CardVoids(crusades = true),
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.MARCO_POLO,
                                        p1,
                                        CardLeaderRole.DISPLAY,
                                        mongolEnhanced = false,
                                    )
                                )
                        ),
                )
        val credits = state.viewForPlayer(p1).playedLeaderDiscounts.single()

        assertThat(credits.usable).isTrue()
        assertThat(credits.discounts.map(LeaderDiscountView::conditionalAmount))
            .containsExactlyElementsIn(
                LeaderData.definition(LeaderId.MARCO_POLO).discounts.map {
                    it.amountFor(false, false)
                }
            )
        assertThat(credits.discounts.map(LeaderDiscountView::writtenRecordBonus)).contains(10)
    }

    @Test
    fun anotherPowersUnclaimedLeaderIsNotAnAuthorizedDiscount() {
        val state =
            state()
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        p2,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        )
                )

        val credits = state.viewForPlayer(p1).playedLeaderDiscounts.single()

        assertThat(credits.usable).isFalse()
        assertThat(credits.discounts.single().conditionalAmount).isEqualTo(20)
    }

    private fun offer(state: GameState): ExpansionOfferView =
        state.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

    private fun withHand(state: GameState, card: String): GameState =
        state.copy(
            players =
                state.players +
                    (p1 to
                        state
                            .playerState(p1)
                            .copy(hand = listOf(HandCard("hand-1", CardData.definition(card)))))
        )

    private fun state(
        board: BoardState = BoardState(),
        pool: Int = 20,
        advances: Set<AdvanceId> = emptySet(),
    ): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 100,
                                poolTokens = if (player.id == p1) pool else 0,
                                stockTokens =
                                    36 -
                                        (if (player.id == p1) pool else 0) -
                                        board.tokenCount(player.id),
                                advances =
                                    PlayerAdvances(
                                        (if (player.id == p1) advances else emptySet()).map {
                                            AdvanceAcquisition(it, 1)
                                        }
                                    ),
                            )
                    }
                    .toMap(),
        )
    }
}
