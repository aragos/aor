package dev.aragos.aor

enum class HistoryEventKind {
    START,
    BID_CAPITAL,
    CHOOSE_CAPITAL,
    BUY_TOKENS,
    CHOOSE_TURN_POSITION,
    RETURN_CARD,
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
    WRITTEN_RECORD,
    OFFER_CARD,
    PLAY_CARD,
    ADJUST_MARKET,
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
    /** One replay entry may also announce a public epoch or ending transition. */
    fun projectAll(
        id: String,
        before: GameState,
        after: GameState,
        entry: Entry,
        viewer: PlayerId,
    ): List<HistoryEventView> = buildList {
        val incomeCollected = incomeCollected(before, after)
        val projected = project(id, before, after, entry, viewer)
        if (projected != null) add(projected)
        val changes =
            if (incomeCollected) cashChanges(before, after)
            else projected?.cashChanges ?: cashChanges(before, after)
        if ((projected == null || incomeCollected) && changes.isNotEmpty()) {
            add(
                HistoryEventView(
                    if (projected == null) id else "$id:income",
                    if (after.stage == Stage.EXPANSION) HistoryEventKind.HOLY_INDULGENCE
                    else HistoryEventKind.INCOME,
                    null,
                    before.turnNumber,
                    after.stage,
                    cashChanges = changes,
                )
            )
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
    ): HistoryEventView? =
        projectEntry(id, before, after, entry, viewer)
            ?.copy(
                cashChanges =
                    if (incomeCollected(before, after)) emptyList() else cashChanges(before, after)
            )
            ?.withParticipants(before)

    private fun HistoryEventView.withParticipants(state: GameState): HistoryEventView {
        val ids =
            listOfNotNull(actor, target, war?.initiator, war?.opponent, war?.winner) +
                dice.mapNotNull(HistoryDieView::player) +
                cashChanges.map(HistoryCashChangeView::player)
        return copy(
            participants =
                ids.distinct().map { id ->
                    val player = state.playerState(id)
                    HistoryParticipantView(id, player.player.name, player.capital)
                }
        )
    }

    private fun incomeCollected(before: GameState, after: GameState): Boolean =
        after.stage == Stage.INCOME &&
            after.flow.phaseBookkeepingApplied &&
            (before.stage != Stage.INCOME || !before.flow.phaseBookkeepingApplied)

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
            is SetupCommand.ReturnCard ->
                event(HistoryEventKind.RETURN_CARD)
                    .copy(
                        card =
                            if (entry.actor == viewer)
                                cardId(before, entry.actor, command.reference)
                            else null
                    )
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
                event(HistoryEventKind.RELIEVE_MISERY).copy(amount = command.spaces)
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
            is BoardCommand.WrittenRecord ->
                event(HistoryEventKind.WRITTEN_RECORD).copy(target = command.victim)
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
            is CardCommand.AdjustMarket ->
                event(HistoryEventKind.ADJUST_MARKET)
                    .copy(
                        commodity = command.commodity,
                        detail = command.adjustment?.name ?: "NEUTRAL",
                    )
            is CardCommand.ChooseCivilWarLoss ->
                event(HistoryEventKind.CIVIL_WAR_LOSS).copy(detail = command.loss.name)
            is CardCommand.ReplaceEventToken ->
                event(HistoryEventKind.REPLACE_EVENT_TOKEN)
                    .copy(detail = if (command.usePool) "POOL" else "NONE")
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
