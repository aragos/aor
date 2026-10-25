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
}

/** A die may be unassigned, or explicitly attributed to a player during a tie. */
data class HistoryDieView(val player: PlayerId?, val value: Int)

/**
 * Viewer-safe activity derived from committed entries. [id] is `revision:entryIndex`, with a
 * zero-based index in that revision's delta. [card] contains a catalog identity only when public or
 * visible to this viewer; hand references and unrevealed submissions are never included. [detail]
 * contains an enum name for a choice or roll kind, not formatted prose.
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
)

/** Projects structured activity from typed entries, using the same audiences as command history. */
internal object HistoryEvents {
    fun project(
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

        return when (entry) {
            is Entry.Shuffle -> null
            is GameOutcome.Draw ->
                event(HistoryEventKind.DRAW_CARD)
                    .copy(card = entry.card.takeIf { entry.player == viewer })
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
                                HistoryDieView(entry.players.getOrNull(index), value.value)
                            },
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
            TurnCommand.Pass -> event(HistoryEventKind.PASS)
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
                    .copy(advance = before.rules.advance(command.letter))
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
                playedCard(event(HistoryEventKind.PLAY_CARD), before, entry.actor, command)
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
        actor: PlayerId,
        command: CardCommand.PlayCard,
    ): HistoryEventView {
        val played = event.copy(card = cardId(before, actor, command.reference))
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

    private fun cardId(state: GameState, owner: PlayerId, reference: String): String =
        checkNotNull(
                state.playerState(owner).hand.singleOrNull { card -> card.reference == reference }
            ) {
                "A committed card action must refer to a card in its owner's preceding hand."
            }
            .definition
            .id
}
