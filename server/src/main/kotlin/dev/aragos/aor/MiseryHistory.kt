package dev.aragos.aor

enum class HistoryMiseryReason {
    ADVANCE_PURCHASE,
    CATEGORY_COMPLETION,
    STABILIZATION,
    PURCHASED_RELIEF,
    INCOME_RELIEF,
    CARD_EFFECT,
    SURPLUS_DEBT,
    HOLY_INDULGENCE,
}

/**
 * Public cause captured at the entry's position in replay. [spaces], when present, is a signed rule
 * contribution before combined track clamping, not an additional movement or an intermediate box.
 * Ordinary causes leave it null and use the enclosing change's actual endpoints.
 */
data class HistoryMiseryReasonView(
    val kind: HistoryMiseryReason,
    val spaces: Int? = null,
    val advance: AdvanceId? = null,
    val card: String? = null,
)

/** All misery mutation owners are represented here without inspecting private discarded cards. */
internal object MiseryHistory {
    fun changes(
        before: GameState,
        after: GameState,
        entry: Entry? = null,
        playedCard: String? = null,
        automatic: HistoryEventKind? = null,
    ): List<HistoryMiseryChangeView> =
        before.playerIds.mapNotNull { player ->
            val previous = before.playerState(player).misery
            val current = after.playerState(player).misery
            val reasons = reasons(before, entry, playedCard, automatic, player)
            val canceling = reasons.size > 1 && reasons.sumOf { it.spaces ?: 0 } == 0
            if (previous == current && !canceling) return@mapNotNull null
            HistoryMiseryChangeView(
                player,
                current.ordinal - previous.ordinal,
                previous.value,
                current.value,
                reasons,
            )
        }

    private fun reasons(
        before: GameState,
        entry: Entry?,
        playedCard: String?,
        automatic: HistoryEventKind?,
        player: PlayerId,
    ): List<HistoryMiseryReasonView> {
        when (automatic) {
            HistoryEventKind.INCOME ->
                return listOf(
                    HistoryMiseryReasonView(
                        HistoryMiseryReason.INCOME_RELIEF,
                        advance =
                            when (before.rules.incomeRelief) {
                                IncomeReliefRule.NEW_WORLD -> AdvanceId.NEW_WORLD
                                IncomeReliefRule.ENLIGHTENMENT -> AdvanceId.ENLIGHTENMENT
                            },
                    )
                )
            HistoryEventKind.HOLY_INDULGENCE ->
                return listOf(
                    HistoryMiseryReasonView(
                        HistoryMiseryReason.HOLY_INDULGENCE,
                        advance = AdvanceId.HOLY_INDULGENCE,
                    )
                )
            else -> Unit
        }
        if (entry is GameOutcome.Roll) {
            return when (entry.kind) {
                GameRollKind.WAR -> cardReason(EventId.WAR.cardId)
                GameRollKind.SHORTAGE ->
                    listOf(HistoryMiseryReasonView(HistoryMiseryReason.SURPLUS_DEBT))
                else -> emptyList()
            }
        }
        if (entry !is Entry.Action) return emptyList()
        return when (val command = entry.command) {
            is TurnCommand.BuyAdvance -> {
                if (entry.actor != player) return emptyList()
                advanceReasons(before, player, AdvanceId.fromLetter(command.letter))
            }
            is TurnCommand.Stabilize ->
                listOf(HistoryMiseryReasonView(HistoryMiseryReason.STABILIZATION))
            is TurnCommand.RelieveMisery ->
                listOf(HistoryMiseryReasonView(HistoryMiseryReason.PURCHASED_RELIEF))
            is CardCommand.PlayCard -> cardReason(playedCard)
            is CardCommand.CedeWar -> cardReason(EventId.WAR.cardId)
            CardCommand.ForgoMarker,
            is CardCommand.MoveMarker -> markerAftermath(before)
            else -> emptyList()
        }
    }

    private fun advanceReasons(
        before: GameState,
        player: PlayerId,
        advance: AdvanceId,
    ): List<HistoryMiseryReasonView> {
        val turn =
            checkNotNull(before.turnNumber) { "A recorded advance purchase requires a turn." }
        val quote =
            AdvancePurchases.miseryQuote(
                before.playerState(player),
                advance,
                PurchaseContext(turn, rules = before.rules),
            )
        return listOfNotNull(
            HistoryMiseryReasonView(
                    HistoryMiseryReason.ADVANCE_PURCHASE,
                    quote.purchaseSpaces,
                    advance,
                )
                .takeIf { quote.purchaseSpaces != 0 },
            HistoryMiseryReasonView(
                    HistoryMiseryReason.CATEGORY_COMPLETION,
                    -quote.completionRelief,
                )
                .takeIf { quote.completionRelief != 0 },
        )
    }

    private fun markerAftermath(before: GameState): List<HistoryMiseryReasonView> {
        val frame =
            before.flow.decisions.lastOrNull() as? CardDecision.MarkerPlacement
                ?: return emptyList()
        return when (frame.aftermath) {
            is MarkerPlacementAftermath.Crusades -> cardReason(EventId.THE_CRUSADES.cardId)
            is MarkerPlacementAftermath.War -> cardReason(EventId.WAR.cardId)
        }
    }

    private fun cardReason(card: String?): List<HistoryMiseryReasonView> =
        listOfNotNull(
            card?.let { HistoryMiseryReasonView(HistoryMiseryReason.CARD_EFFECT, card = it) }
        )
}
