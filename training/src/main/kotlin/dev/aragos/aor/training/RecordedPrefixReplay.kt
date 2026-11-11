package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardCommand
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnCommand

/** Descriptive root-turn receipts only; these projections never feed proposal or arm selection. */
internal object RecordedPrefixReplay {
    fun verify(
        root: GameState,
        actor: PlayerId,
        entries: List<Entry>,
        terminal: GameState,
        deadlineNanos: Long,
    ): Map<String, Any?> {
        val ownLeaders = mutableSetOf<String>()
        val leaderPlays = mutableListOf<Map<String, Any>>()
        val purchases = mutableListOf<Map<String, Any>>()
        val patronage = mutableListOf<Map<String, Any>>()
        var boundary: Map<String, Any?>? = null
        var state = root
        for (entry in entries) {
            checkTokenValueDeadline(deadlineNanos)
            val next = state.step(entry)
            if (state.turnNumber == root.turnNumber && entry is Entry.Action) {
                when (val command = entry.command) {
                    is CardCommand.PlayCard ->
                        if (entry.actor == actor) {
                            val view = state.viewForPlayer(actor)
                            val offer = view.cardOffers.single { it.reference == command.reference }
                            if (offer.leaderDiscounts.isNotEmpty()) {
                                val card =
                                    view.hand
                                        .single { it.reference == command.reference }
                                        .definition
                                        .id
                                ownLeaders.add(card)
                                leaderPlays.add(
                                    mapOf(
                                        "card" to card,
                                        "command" to command.toText(),
                                        "projectedPrintedWordRebate" to
                                            offer.leaderDiscounts.sumOf { it.rebate },
                                        "actualPlayCashChange" to
                                            (next.playerState(actor).cash -
                                                state.playerState(actor).cash),
                                    )
                                )
                            }
                        }
                    is TurnCommand.BuyAdvance ->
                        if (entry.actor == actor) {
                            val offer =
                                state.viewForPlayer(actor).advanceOffers.single {
                                    it.advance == AdvanceId.fromLetter(command.letter)
                                }
                            val quote =
                                requireNotNull(offer.priceBreakdown) {
                                    "An applied advance has an authoritative price quote."
                                }
                            // Reuse the authoritative price floor, removing only this suffix's own
                            // leaders.
                            val withoutOwn =
                                quote.copy(
                                    leaders =
                                        quote.leaders.filter { it.leaderCardId !in ownLeaders }
                                )
                            purchases.add(
                                mapOf(
                                    "advance" to offer.advance.name,
                                    "price" to quote.price,
                                    "leaderCredits" to quote.leaders,
                                    "ownNewLeaderEffectiveSaving" to
                                        (withoutOwn.price - quote.price),
                                )
                            )
                        }
                    is CardCommand.PatronizeLeader ->
                        if (command.leader.cardId in ownLeaders) {
                            patronage.add(
                                mapOf(
                                    "card" to command.leader.cardId,
                                    "claimant" to entry.actor.value,
                                    "actualOwnerCashChange" to
                                        (next.playerState(actor).cash -
                                            state.playerState(actor).cash),
                                )
                            )
                        }
                    else -> Unit
                }
            }
            if (
                boundary == null &&
                    (next.turnNumber != root.turnNumber || next.stage == Stage.FINISHED)
            ) {
                boundary =
                    snapshot(next.publicView(), actor) +
                        ("kind" to
                            if (next.stage == Stage.FINISHED) "terminal" else "next-turn-boundary")
            }
            state = next
        }
        check(state == terminal) { "A recorded counterfactual tail must replay exactly." }
        return mapOf(
            "beforeRoot" to snapshot(root.publicView(), actor),
            "nextBoundary" to
                requireNotNull(boundary) { "A complete tail reaches a turn boundary or terminal." },
            "rootTurnLeaderPlays" to leaderPlays,
            "rootTurnPurchases" to purchases,
            "rootTurnOwnLeaderPatronage" to patronage,
        )
    }

    private fun snapshot(view: GameView, actor: PlayerId): Map<String, Any?> {
        val player = view.players.single { it.id == actor }
        return mapOf(
            "cash" to player.cash,
            "markers" to player.boardMarkers,
            "income" to player.income,
            "commodityMarkets" to
                view.commodityPayouts
                    .mapNotNull { commodity ->
                        commodity.owners
                            .singleOrNull { it.player == actor }
                            ?.let { commodity.commodity.name to it }
                    }
                    .toMap(),
        )
    }
}
