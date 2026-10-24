package dev.aragos.aor

/**
 * An equal-purchase group in capital-choice order, with the earliest chooser placed automatically.
 * Later capital choosers insert into [placed] and therefore make the final relative-order choices.
 */
data class TurnOrderTie(
    val players: List<PlayerId>,
    val placed: List<PlayerId> = players.take(1),
) {
    init {
        require(players.size >= 2) { "A turn-order tie requires at least two players." }
        require(players.distinct().size == players.size) {
            "A turn-order tie cannot repeat a player."
        }
        require(placed.isNotEmpty() && placed.size < players.size) {
            "An unresolved turn-order tie must have placed players and a pending chooser."
        }
        require(placed.toSet() == players.take(placed.size).toSet()) {
            "Placed players must be the earliest capital choosers in the tie."
        }
        require(placed.distinct().size == placed.size) { "Placed players cannot repeat." }
    }

    val choosingPlayer: PlayerId
        get() = players[placed.size]
}

/** Signed purchases ranked ascending, with unresolved groups processed from lowest purchase up. */
data class TurnOrderSelection(
    val order: List<PlayerId> = emptyList(),
    val ties: List<TurnOrderTie> = emptyList(),
) {
    init {
        require(order.distinct().size == order.size) { "Turn order cannot repeat a player." }
        val tiedPlayers = ties.flatMap(TurnOrderTie::players)
        require(tiedPlayers.distinct().size == tiedPlayers.size) {
            "A player cannot belong to more than one unresolved turn-order tie."
        }
        require(
            ties.all { tie ->
                val positions = tie.players.map(order::indexOf).sorted()
                positions.first() >= 0 &&
                    positions == (positions.first()..positions.last()).toList()
            }
        ) {
            "Each turn-order tie must occupy consecutive positions in the order."
        }
    }

    val isResolved: Boolean
        get() = order.isNotEmpty() && ties.isEmpty()

    /**
     * Insert the next capital chooser into the first pending tie.
     *
     * @param actor the next unplaced player in that group's capital-choice order.
     * @param slot one-based insertion position, from 1 through the current placed count plus 1.
     */
    fun choosePosition(actor: PlayerId, slot: Int): TurnOrderSelection {
        val tie = ties.firstOrNull() ?: throw RuleViolation(ViolationCode.WRONG_PHASE)
        checkRule(actor == tie.choosingPlayer, ViolationCode.WRONG_PLAYER)
        checkRule(slot in 1..tie.placed.size + 1, ViolationCode.INVALID_TURN_POSITION)
        val inserted = tie.placed.take(slot - 1) + actor + tie.placed.drop(slot - 1)
        val start = tie.players.minOf(order::indexOf)
        val pending = tie.players.drop(inserted.size)
        return copy(
            order = order.take(start) + inserted + pending + order.drop(start + tie.players.size),
            ties =
                if (pending.isEmpty()) {
                    ties.drop(1)
                } else {
                    listOf(tie.copy(placed = inserted)) + ties.drop(1)
                },
        )
    }

    companion object {
        /**
         * Rank [purchases] by their signed amounts, using [capitalOrder] to sequence tie choices.
         */
        fun fromPurchases(
            purchases: Map<PlayerId, Int>,
            capitalOrder: List<PlayerId>,
        ): TurnOrderSelection {
            require(capitalOrder.distinct().size == capitalOrder.size) {
                "Capital-choice order cannot repeat a player."
            }
            require(purchases.keys == capitalOrder.toSet()) {
                "Every capital chooser must have exactly one token purchase."
            }
            val ranked = capitalOrder.sortedBy(purchases::getValue)
            return TurnOrderSelection(
                order = ranked,
                ties =
                    ranked
                        .groupBy(purchases::getValue)
                        .values
                        .filter { players -> players.size > 1 }
                        .map(::TurnOrderTie),
            )
        }
    }
}
