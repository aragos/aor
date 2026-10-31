package dev.aragos.aor

enum class HistoryEventKind {
    START,
    BID_CAPITAL,
    CHOOSE_CAPITAL,
    BUY_TOKENS,
    TOKEN_PURCHASES,
    CHOOSE_TURN_POSITION,
    PASS,
    SWAP_ORDER,
    BUY_CARD,
    DISCARD_CARD,
    CANCEL_ADJUSTMENT,
    UPGRADE_SHIPS,
    BUY_ADVANCE,
    STABILIZE,
    RELIEVE_MISERY,
    PLACE,
    ESTABLISH_MARKER,
    ATTACK,
    COMPETITION_RESULT,
    WAR_RESULT,
    REDUCE_MARKER,
    BUY_EXPANSION_CARD,
    CATHEDRAL,
    WINDMILL,
    OFFER_CARD,
    PLAY_CARD,
    CIVIL_WAR_LOSS,
    REPLACE_EVENT_TOKEN,
    CEDE_WAR,
    FORGO_MARKER,
    MOVE_MARKER,
    PATRONIZE,
    DRAW_CARD,
    TRADE_CARD,
    ROLL,
    NEXT_EPOCH,
    ENDING_TRIGGERED,
    GAME_FINISHED,
    INCOME,
    HOLY_INDULGENCE,
    ORDER_CHANGED,
}

enum class HistoryDieColor {
    WHITE,
    BLACK,
    GREEN,
}

/** War uses white for the initiator and black for the opponent; tie rolls use white. */
data class HistoryDieView(
    val player: PlayerId?,
    val value: Int,
    val color: HistoryDieColor = HistoryDieColor.WHITE,
)

enum class CardDrawReason {
    INITIAL_DEAL,
    TURN_DRAW,
    URBAN_ASCENDANCY,
    EXPANSION_PURCHASE,
    EXPANSION_BONUS,
    SHORTAGE,
}

/** Signed public cash movement caused by this entry, including automatic phase bookkeeping. */
data class HistoryCashChangeView(val player: PlayerId, val amount: Int)

/** Signed changes to a player's own pool and stock; never a physical transfer between players. */
data class HistoryTokenChangeView(val player: PlayerId, val poolDelta: Int, val stockDelta: Int)

/** One-based positions recorded when a public order change takes effect. */
data class HistoryPositionChangeView(val player: PlayerId, val before: Int, val after: Int)

/** A revealed signed bid, its cash expense, and the actual stock-funded token allocation. */
data class HistoryTokenPurchaseView(
    val player: PlayerId,
    val amount: Int,
    val cashCost: Int,
    val tokensReceived: Int,
)

/** MR earned from one publicly purchased advance this turn, before pooling and expenditure. */
data class HistoryReliefRatingSourceView(val advance: AdvanceId, val amount: Int)

/**
 * The authoritative relief quote and the unused current-turn MR pool immediately before payment.
 */
data class HistoryReliefPaymentView(
    val cashCost: Int,
    val ratingSpent: Int,
    val ratingAvailableBefore: Int,
    val ratingSources: List<HistoryReliefRatingSourceView>,
)

/**
 * Signed movement in track spaces; [fromValue] and [toValue] are the recorded printed box values. A
 * null value denotes Chaos rather than a numeric box.
 */
data class HistoryMiseryChangeView(
    val player: PlayerId,
    val spaces: Int,
    val fromValue: Int?,
    val toValue: Int?,
    val reasons: List<HistoryMiseryReasonView> = emptyList(),
)

/** White plus black selects the commodities; green selects the adjustment. */
data class HistoryMarketRollView(
    val total: Int,
    val commodities: List<Commodity>,
    val adjustment: CommodityAdjustment,
    val cancelled: List<Commodity>,
)

/** Public identity at the entry's position in effective history, before later capital selection. */
data class HistoryParticipantView(val id: PlayerId, val name: String, val capital: String?)

/**
 * Viewer-safe activity derived from committed entries. [id] is `revision:entryIndex`, with a
 * zero-based index in that revision's delta; synthetic transitions append `:kind`. [card] contains
 * a catalog identity only when public or visible to this viewer; hand references and unrevealed
 * submissions are never included. [detail] contains an enum name for a choice or roll kind, not
 * formatted prose.
 *
 * All values, including [participants], are derived at this entry's position in replay. Appending
 * history must not change an existing event. An explicit history edit may replace affected events.
 */
data class HistoryEventView(
    val id: String,
    val kind: HistoryEventKind,
    val actor: PlayerId?,
    val turnNumber: Int?,
    val stage: Stage,
    val target: PlayerId? = null,
    val provinces: List<ProvinceId> = emptyList(),
    val card: String? = null,
    val commodity: Commodity? = null,
    val advance: AdvanceId? = null,
    val amount: Int? = null,
    val detail: String? = null,
    val dice: List<HistoryDieView> = emptyList(),
    val competition: CompetitionResultView? = null,
    val war: WarOutcomeView? = null,
    val cashChanges: List<HistoryCashChangeView> = emptyList(),
    val miseryChanges: List<HistoryMiseryChangeView> = emptyList(),
    val tokenChanges: List<HistoryTokenChangeView> = emptyList(),
    val positionChanges: List<HistoryPositionChangeView> = emptyList(),
    val tokenPurchases: List<HistoryTokenPurchaseView> = emptyList(),
    val reliefPayment: HistoryReliefPaymentView? = null,
    val drawReason: CardDrawReason? = null,
    val protectionCash: Int? = null,
    val leaderRebate: Int? = null,
    val marketRoll: HistoryMarketRollView? = null,
    val participants: List<HistoryParticipantView> = emptyList(),
)

/** Public outcome from the committed board change, including Cathedral resolutions. */
data class CompetitionResultView(val attackerWon: Boolean, val defenders: Set<PlayerId>)

/** Projects structured activity from typed entries, using the same audiences as command history. */
internal object HistoryEvents {
    /** Called only after all active participants' purchases have been committed and revealed. */
    fun tokenPurchases(
        firstEntryId: String,
        before: GameState,
        after: GameState,
        amounts: Map<PlayerId, Int>,
    ): HistoryEventView {
        val purchases = amounts.map { (player, amount) ->
            val previous = before.playerState(player)
            val paid = after.playerState(player)
            HistoryTokenPurchaseView(
                player = player,
                amount = amount,
                cashCost = previous.cash - paid.cash,
                tokensReceived = previous.stockTokens - paid.stockTokens,
            )
        }
        return HistoryEventView(
                id = "$firstEntryId:token_purchases",
                kind = HistoryEventKind.TOKEN_PURCHASES,
                actor = null,
                turnNumber = before.turnNumber,
                stage = before.stage,
                cashChanges =
                    purchases.mapNotNull { purchase ->
                        HistoryCashChangeView(purchase.player, -purchase.cashCost).takeIf {
                            purchase.cashCost != 0
                        }
                    },
                tokenPurchases = purchases,
                positionChanges = positionChanges(before.turnOrder, after.turnOrder),
            )
            .withParticipants(before)
    }

    /** One replay entry may also announce a public epoch or ending transition. */
    fun projectAll(
        id: String,
        before: GameState,
        after: GameState,
        entry: Entry,
        viewer: PlayerId,
        resolved: GameTransition = before.transition(entry),
    ): List<HistoryEventView> = buildList {
        val automatic = resolved.receipts
        val projected =
            projectDirect(
                id,
                before,
                if (automatic.isEmpty()) after else resolved.applied,
                entry,
                viewer,
            )
        if (projected != null) add(projected)
        for (receipt in automatic) {
            if (receipt.cause == AutomaticCause.CIVIL_WAR) {
                add(
                    HistoryEventView(
                        id = "$id:civil_war_order",
                        kind = HistoryEventKind.ORDER_CHANGED,
                        actor = null,
                        turnNumber = receipt.turnNumber,
                        stage = receipt.stage,
                        card = "civil-war",
                        positionChanges = positionChanges(receipt.orderBefore, receipt.orderAfter),
                    )
                )
                continue
            }
            val kind =
                when (receipt.cause) {
                    AutomaticCause.INCOME -> HistoryEventKind.INCOME
                    AutomaticCause.HOLY_INDULGENCE -> HistoryEventKind.HOLY_INDULGENCE
                    AutomaticCause.CIVIL_WAR ->
                        error("Civil War order receipt was already handled.")
                }
            val cash =
                receipt.resources.mapNotNull { endpoints ->
                    HistoryCashChangeView(
                            endpoints.player,
                            endpoints.cashAfter - endpoints.cashBefore,
                        )
                        .takeIf { it.amount != 0 }
                }
            val misery =
                receipt.resources.mapNotNull { endpoints ->
                    if (endpoints.miseryBefore == endpoints.miseryAfter) null
                    else {
                        val reason =
                            when (receipt.cause) {
                                AutomaticCause.CIVIL_WAR ->
                                    error("Civil War has no resource receipt.")
                                AutomaticCause.INCOME -> HistoryMiseryReason.INCOME_RELIEF
                                AutomaticCause.HOLY_INDULGENCE ->
                                    HistoryMiseryReason.HOLY_INDULGENCE
                            }
                        HistoryMiseryChangeView(
                            endpoints.player,
                            endpoints.miseryAfter.ordinal - endpoints.miseryBefore.ordinal,
                            endpoints.miseryBefore.value,
                            endpoints.miseryAfter.value,
                            listOf(HistoryMiseryReasonView(reason, advance = receipt.advance)),
                        )
                    }
                }
            val tokens =
                if (receipt.cause == AutomaticCause.HOLY_INDULGENCE)
                    receipt.resources.mapNotNull { endpoints ->
                        HistoryTokenChangeView(
                                endpoints.player,
                                endpoints.poolAfter - endpoints.poolBefore,
                                endpoints.stockAfter - endpoints.stockBefore,
                            )
                            .takeIf { it.poolDelta != 0 || it.stockDelta != 0 }
                    }
                else emptyList()
            if (cash.isNotEmpty() || misery.isNotEmpty() || tokens.isNotEmpty()) {
                add(
                    HistoryEventView(
                        if (projected == null) id else "$id:${kind.name.lowercase()}",
                        kind,
                        null,
                        before.turnNumber,
                        receipt.stage,
                        cashChanges = cash,
                        miseryChanges = misery,
                        tokenChanges = tokens,
                    )
                )
            }
        }
        fun transition(kind: HistoryEventKind, amount: Int? = null) =
            HistoryEventView(
                "$id:${kind.name.lowercase()}",
                kind,
                null,
                after.turnNumber,
                after.stage,
                amount = amount,
            )
        if (before.currentEpoch != after.currentEpoch) {
            add(transition(HistoryEventKind.NEXT_EPOCH, after.currentEpoch.number))
        }
        if (!before.endingTriggered && after.endingTriggered) {
            add(transition(HistoryEventKind.ENDING_TRIGGERED))
        }
        if (before.stage != Stage.FINISHED && after.stage == Stage.FINISHED) {
            add(transition(HistoryEventKind.GAME_FINISHED))
        }
    }
        .map { event -> event.withParticipants(before) }

    fun project(
        id: String,
        before: GameState,
        after: GameState,
        entry: Entry,
        viewer: PlayerId,
    ): HistoryEventView? {
        return projectDirect(id, before, after, entry, viewer)
    }

    private fun projectDirect(
        id: String,
        before: GameState,
        after: GameState,
        entry: Entry,
        viewer: PlayerId,
    ): HistoryEventView? =
        projectEntry(id, before, after, entry, viewer)
            ?.let { event ->
                event.copy(
                    cashChanges = cashChanges(before, after),
                    positionChanges = positionChanges(before.turnOrder, after.turnOrder),
                    miseryChanges =
                        MiseryHistory.changes(before, after, entry, playedCard = event.card),
                )
            }
            ?.withParticipants(before)

    private fun HistoryEventView.withParticipants(state: GameState): HistoryEventView {
        val ids =
            listOfNotNull(actor, target, war?.initiator, war?.opponent, war?.winner) +
                dice.mapNotNull(HistoryDieView::player) +
                cashChanges.map(HistoryCashChangeView::player) +
                miseryChanges.map(HistoryMiseryChangeView::player) +
                tokenChanges.map(HistoryTokenChangeView::player) +
                positionChanges.map(HistoryPositionChangeView::player) +
                tokenPurchases.map(HistoryTokenPurchaseView::player)
        return copy(
            participants =
                ids.distinct().map { id ->
                    val player = state.playerState(id)
                    HistoryParticipantView(id, player.player.name, player.capital)
                }
        )
    }

    private fun positionChanges(
        before: List<PlayerId>,
        after: List<PlayerId>,
    ): List<HistoryPositionChangeView> = before.mapIndexedNotNull { index, player ->
        val next = after.indexOf(player)
        if (next < 0 || next == index) null
        else HistoryPositionChangeView(player, index + 1, next + 1)
    }

    private fun cashChanges(before: GameState, after: GameState): List<HistoryCashChangeView> =
        before.playerIds.mapNotNull { player ->
            val change = after.playerState(player).cash - before.playerState(player).cash
            HistoryCashChangeView(player, change).takeIf { change != 0 }
        }

    private fun projectEntry(
        id: String,
        before: GameState,
        after: GameState,
        entry: Entry,
        viewer: PlayerId,
    ): HistoryEventView? {
        val actor =
            when (entry) {
                is Entry.Action -> entry.actor
                is GameOutcome.Draw -> entry.player
                is GameOutcome.Trade -> entry.owner
                else -> null
            }
        fun event(kind: HistoryEventKind) =
            HistoryEventView(id, kind, actor, before.turnNumber, before.stage)

        if (entry is GameOutcome.Roll && entry.kind == GameRollKind.WAR) {
            val war = WarProjection.outcome(before, WarDice(entry.values[0], entry.values[1]))
            if (war != null) {
                return event(HistoryEventKind.WAR_RESULT)
                    .copy(
                        actor = war.initiator,
                        target = war.opponent,
                        war = war,
                        dice =
                            listOf(war.initiator, war.opponent).zip(entry.values).mapIndexed {
                                index,
                                (player, die) ->
                                HistoryDieView(player, die.value, HistoryDieColor.entries[index])
                            },
                    )
            }
        }

        val plan =
            when (val decision = before.flow.decisions.lastOrNull()) {
                is BoardDecision.CompetitionDice ->
                    decision.plan.takeIf { entry is GameOutcome.Roll }
                is BoardDecision.CathedralChoice ->
                    decision.plan.takeIf {
                        entry is Entry.Action && entry.command == BoardCommand.Cathedral
                    }
                else -> null
            }
        if (plan != null) {
            return event(HistoryEventKind.COMPETITION_RESULT)
                .copy(
                    actor = plan.player,
                    provinces = listOf(plan.destination),
                    amount = plan.addedTokens,
                    detail = if (entry is GameOutcome.Roll) entry.kind.name else "CATHEDRAL",
                    dice =
                        (entry as? GameOutcome.Roll)?.values.orEmpty().mapIndexed { index, die ->
                            HistoryDieView(null, die.value, HistoryDieColor.entries[index])
                        },
                    competition =
                        CompetitionResultView(
                            after.board.hasPresence(plan.player, plan.destination),
                            plan.defenders,
                        ),
                )
        }

        return when (entry) {
            is Entry.Shuffle -> null
            is GameOutcome.Draw ->
                event(HistoryEventKind.DRAW_CARD)
                    .copy(
                        card = entry.card.takeIf { entry.player == viewer },
                        drawReason = drawReason(before),
                    )
            is GameOutcome.Trade ->
                event(HistoryEventKind.TRADE_CARD)
                    .copy(
                        target = entry.recipient,
                        card =
                            if (viewer == entry.owner || viewer == entry.recipient) {
                                cardId(before, entry.owner, entry.reference)
                            } else null,
                    )
            is GameOutcome.Roll ->
                event(HistoryEventKind.ROLL)
                    .copy(
                        detail = entry.kind.name,
                        dice =
                            entry.values.mapIndexed { index, value ->
                                HistoryDieView(
                                    entry.players.getOrNull(index),
                                    value.value,
                                    if (entry.kind == GameRollKind.SHORTAGE)
                                        HistoryDieColor.entries[index]
                                    else HistoryDieColor.WHITE,
                                )
                            },
                        marketRoll = marketRoll(before, entry),
                    )
            is Entry.Action -> action(before, after, entry, viewer, ::event)
        }
    }

    private fun action(
        before: GameState,
        after: GameState,
        entry: Entry.Action,
        viewer: PlayerId,
        event: (HistoryEventKind) -> HistoryEventView,
    ): HistoryEventView? =
        when (val command = entry.command) {
            is RuleOptionCommand,
            is Command.SetRebuiltMarkerBonus -> null
            Command.Start -> event(HistoryEventKind.START)
            is Command.BidCapital ->
                event(HistoryEventKind.BID_CAPITAL).copy(amount = command.amount)
            is Command.ChooseCapital ->
                event(HistoryEventKind.CHOOSE_CAPITAL)
                    .copy(provinces = listOf(ProvinceId(command.capital)))
            is Command.BuyTokens -> event(HistoryEventKind.BUY_TOKENS).copy(amount = command.amount)
            is Command.ChooseTurnPosition ->
                event(HistoryEventKind.CHOOSE_TURN_POSITION).copy(amount = command.slot)
            TurnCommand.Pass -> null
            is TurnCommand.SwapOrder ->
                event(HistoryEventKind.SWAP_ORDER).copy(target = command.target)
            TurnCommand.BuyCard -> event(HistoryEventKind.BUY_CARD)
            is TurnCommand.DiscardCard ->
                event(HistoryEventKind.DISCARD_CARD)
                    .copy(
                        card =
                            if (entry.actor == viewer)
                                cardId(before, entry.actor, command.reference)
                            else null
                    )
            is TurnCommand.CancelAdjustment ->
                event(HistoryEventKind.CANCEL_ADJUSTMENT)
                    .copy(
                        commodity = command.commodity,
                        detail = command.adjustment.name,
                    )
            TurnCommand.UpgradeShips -> event(HistoryEventKind.UPGRADE_SHIPS)
            is TurnCommand.BuyAdvance ->
                event(HistoryEventKind.BUY_ADVANCE)
                    .copy(advance = AdvanceId.fromLetter(command.letter))
            is TurnCommand.Stabilize ->
                event(HistoryEventKind.STABILIZE).copy(detail = command.payment.name)
            is TurnCommand.RelieveMisery ->
                event(HistoryEventKind.RELIEVE_MISERY)
                    .copy(
                        amount = command.spaces,
                        reliefPayment = reliefPayment(before, entry.actor, command.spaces),
                    )
            is BoardCommand.Place -> {
                val marker = after.board.pieces[command.province] as? ProvincePieces.Marker
                event(
                        if (marker?.owner == entry.actor) HistoryEventKind.ESTABLISH_MARKER
                        else HistoryEventKind.PLACE
                    )
                    .copy(
                        provinces = listOf(command.province),
                        amount = command.tokens,
                    )
            }
            is BoardCommand.Attack ->
                event(HistoryEventKind.ATTACK).copy(provinces = listOf(command.province))
            is BoardCommand.ReduceMarker ->
                event(HistoryEventKind.REDUCE_MARKER).copy(provinces = listOf(command.province))
            BoardCommand.BuyExpansionCard -> event(HistoryEventKind.BUY_EXPANSION_CARD)
            BoardCommand.Cathedral -> event(HistoryEventKind.CATHEDRAL)
            BoardCommand.Windmill -> event(HistoryEventKind.WINDMILL)
            is BoardCommand.OfferCard ->
                event(HistoryEventKind.OFFER_CARD)
                    .copy(
                        card =
                            if (viewer in BoardGame.tradeParticipants(before)) {
                                cardId(before, entry.actor, command.reference)
                            } else null
                    )
            is CardCommand.PlayCard ->
                playedCard(event(HistoryEventKind.PLAY_CARD), before, after, entry.actor, command)
            is CardCommand.ChooseCivilWarLoss ->
                event(HistoryEventKind.CIVIL_WAR_LOSS).copy(detail = command.loss.name)
            is CardCommand.ReplaceEventTokens ->
                event(HistoryEventKind.REPLACE_EVENT_TOKEN)
                    .copy(
                        provinces = command.provinces.sortedBy(ProvinceId::value),
                        detail = if (command.provinces.isEmpty()) "NONE" else "POOL",
                    )
            is CardCommand.CedeWar ->
                event(HistoryEventKind.CEDE_WAR)
                    .copy(provinces = command.provinces.sortedBy(ProvinceId::value))
            CardCommand.ForgoMarker -> event(HistoryEventKind.FORGO_MARKER)
            is CardCommand.MoveMarker ->
                event(HistoryEventKind.MOVE_MARKER).copy(provinces = listOf(command.source))
            is CardCommand.PatronizeLeader ->
                event(HistoryEventKind.PATRONIZE).copy(card = command.leader.cardId)
        }

    private fun playedCard(
        event: HistoryEventView,
        before: GameState,
        after: GameState,
        actor: PlayerId,
        command: CardCommand.PlayCard,
    ): HistoryEventView {
        val card = cardId(before, actor, command.reference)
        val leader = after.effects.leaders.singleOrNull { play -> play.leader.cardId == card }
        val played =
            event.copy(
                card = card,
                protectionCash = leader?.protectionCash,
                leaderRebate =
                    leader?.let {
                        (after.playerState(actor).cash - before.playerState(actor).cash +
                                it.protectionCash)
                            .takeIf { rebate -> rebate > 0 }
                    },
            )
        return when (val choice = command.choice) {
            CardChoice.None -> played
            is CardChoice.CommodityChoice -> played.copy(commodity = choice.commodity)
            is CardChoice.PlayerChoice -> played.copy(target = choice.player)
            is CardChoice.ProvinceChoice -> played.copy(provinces = listOf(choice.province))
            is CardChoice.ProvincesChoice -> played.copy(provinces = choice.provinces)
            is CardChoice.AreaChoice -> played.copy(detail = choice.area.name)
            is CardChoice.CategoryChoice -> played.copy(detail = choice.category?.name ?: "NONE")
            is CardChoice.LeaderProtection -> played.copy(amount = choice.cash)
        }
    }

    private fun reliefPayment(
        before: GameState,
        actor: PlayerId,
        spaces: Int,
    ): HistoryReliefPaymentView {
        val turn = checkNotNull(before.turnNumber) { "A recorded relief purchase requires a turn." }
        val player = before.playerState(actor)
        val quote = PlayerEconomy.reliefQuote(player, spaces, turn)
        val previousUsage = player.miseryReliefUsage?.takeIf { it.turnNumber == turn }
        val sources =
            player.advances.acquisitions
                .filter { it.turnNumber == turn }
                .mapNotNull { acquisition ->
                    val rating = AdvanceData.definition(acquisition.advance).miseryRelief
                    HistoryReliefRatingSourceView(acquisition.advance, rating).takeIf { rating > 0 }
                }
        return HistoryReliefPaymentView(
            cashCost = quote.cashCost,
            ratingSpent = quote.ratingSpent,
            ratingAvailableBefore =
                player.advances.miseryReliefFor(turn) - (previousUsage?.ratingSpent ?: 0),
            ratingSources = sources,
        )
    }

    private fun drawReason(before: GameState): CardDrawReason =
        when (before.stage) {
            Stage.INITIAL_DRAW -> CardDrawReason.INITIAL_DEAL
            Stage.DRAW_CARD -> CardDrawReason.TURN_DRAW
            Stage.BUY_CARD -> CardDrawReason.URBAN_ASCENDANCY
            Stage.EXPANSION -> CardDrawReason.EXPANSION_PURCHASE
            Stage.INCOME ->
                if (before.flow.shortageRolls == 0) CardDrawReason.EXPANSION_BONUS
                else CardDrawReason.SHORTAGE
            else -> error("A committed draw requires a phase that awards cards.")
        }

    private fun marketRoll(before: GameState, roll: GameOutcome.Roll): HistoryMarketRollView? {
        if (roll.kind != GameRollKind.SHORTAGE) return null
        val total = roll.values[0].value + roll.values[1].value
        val commodities = CommodityData.definitions.filter { it.diceTotal == total }.map { it.id }
        val adjustment =
            if (roll.values[2].value <= 3) CommodityAdjustment.SHORTAGE
            else CommodityAdjustment.SURPLUS
        return HistoryMarketRollView(
            total,
            commodities,
            adjustment,
            commodities.filter { commodity ->
                before.adjustments[commodity].orEmpty().any { marker -> marker != adjustment }
            },
        )
    }

    private fun cardId(state: GameState, owner: PlayerId, reference: String): String =
        checkNotNull(
                state.playerState(owner).hand.singleOrNull { card -> card.reference == reference }
            ) {
                "A committed card action must refer to a card in its owner's preceding hand."
            }
            .definition
            .id
}
