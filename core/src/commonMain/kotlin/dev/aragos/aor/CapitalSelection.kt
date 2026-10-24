package dev.aragos.aor

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Players with equal bids or equal tie rolls who still need to establish their relative order. */
data class CapitalTie(val players: List<PlayerId>) {
    init {
        require(players.size >= 2) { "A capital tie requires at least two players." }
        require(players.distinct().size == players.size) { "A capital tie cannot repeat a player." }
    }
}

/**
 * Capital-choice ranking and the explicit ties still awaiting dice.
 *
 * [order] keeps tied players adjacent in their prior order until a roll ranks them. [ties] contains
 * only unresolved groups, processed from the highest rank down. With no ties, the order is final.
 */
data class CapitalSelection(
    val order: PersistentList<PlayerId> = persistentListOf(),
    val ties: List<CapitalTie> = emptyList(),
) {
    init {
        require(order.distinct().size == order.size) { "Capital order cannot repeat a player." }
        val tiedPlayers = ties.flatMap(CapitalTie::players)
        require(tiedPlayers.distinct().size == tiedPlayers.size) {
            "A player cannot belong to more than one unresolved capital tie."
        }
        require(
            ties.all { tie ->
                val start = order.indexOf(tie.players.first())
                start >= 0 && order.drop(start).take(tie.players.size) == tie.players
            }
        ) {
            "Each unresolved capital tie must occupy consecutive places in the capital order."
        }
    }

    /** True once bids establish a nonempty order and all ties have been broken. */
    val isResolved: Boolean
        get() = order.isNotEmpty() && ties.isEmpty()

    /**
     * Rank the highest unresolved tie by descending die result, retaining any tied subgroups.
     *
     * @param rolls exactly one six-sided die result for each player in the first unresolved tie.
     *   Equal results preserve prior order provisionally and require another roll for that
     *   subgroup.
     * @throws RuleViolation if there is no pending tie or the results name a different player set.
     */
    fun resolveNextTie(rolls: Map<PlayerId, DieRoll>): CapitalSelection {
        val tie = ties.firstOrNull() ?: throw RuleViolation(ViolationCode.INVALID_OUTCOME)
        checkRule(rolls.keys == tie.players.toSet(), ViolationCode.INVALID_OUTCOME)
        val rankedPlayers = tie.players.sortedByDescending { player -> rolls[player] }
        val remainingTies =
            rankedPlayers
                .groupBy { player -> rolls[player] }
                .values
                .filter { players -> players.size > 1 }
                .map(::CapitalTie)
        val start = order.indexOf(tie.players.first())
        return copy(
            order =
                (order.take(start) + rankedPlayers + order.drop(start + tie.players.size))
                    .toPersistentList(),
            ties = remainingTies + ties.drop(1),
        )
    }

    companion object {
        /**
         * Rank capital bids from highest to lowest, recording equal-bid groups for dice resolution.
         *
         * @param bids amounts from 0 through 40, iterating in registration order to provide a
         *   stable provisional ranking for players whose bids are equal.
         */
        fun fromBids(bids: Map<PlayerId, Int>): CapitalSelection {
            require(bids.values.all { bid -> bid in 0..40 }) {
                "Capital bids must be between 0 and 40."
            }
            val rankedBids = bids.entries.sortedByDescending(Map.Entry<PlayerId, Int>::value)
            return CapitalSelection(
                order = rankedBids.map(Map.Entry<PlayerId, Int>::key).toPersistentList(),
                ties =
                    rankedBids
                        .groupBy(Map.Entry<PlayerId, Int>::value)
                        .values
                        .filter { tiedBids -> tiedBids.size > 1 }
                        .map { tiedBids ->
                            CapitalTie(tiedBids.map(Map.Entry<PlayerId, Int>::key))
                        },
            )
        }
    }
}
