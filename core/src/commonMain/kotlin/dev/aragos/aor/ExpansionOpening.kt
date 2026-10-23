package dev.aragos.aor

/** Holy Indulgence applies only between active players; Chaos pieces are neutral. */
internal fun GameState.applyHolyIndulgence(): GameState {
    val active = playerIds.filter { id -> playerState(id).misery != MiseryStep.CHAOS }
    val owners = active.filter { id -> playerState(id).advances.owns(AdvanceId.HOLY_INDULGENCE) }
    val nonOwners = active - owners.toSet()
    return active.fold(this) { state, id ->
        val player = state.playerState(id)
        if (id in owners) {
            val required = nonOwners.size * 2
            val moved = minOf(player.stockTokens, required)
            state.replacePlayer(
                player.copy(
                    stockTokens = player.stockTokens - moved,
                    poolTokens = player.poolTokens + moved,
                    cash = player.cash + required - moved,
                )
            )
        } else {
            val required = owners.size * 2
            val moved = minOf(player.poolTokens, required)
            val cost = required - moved
            val unpaid = (cost - player.cash).coerceAtLeast(0)
            state.replacePlayer(
                player.copy(
                    stockTokens = player.stockTokens + moved,
                    poolTokens = player.poolTokens - moved,
                    cash = (player.cash - cost).coerceAtLeast(0),
                    misery = MiseryRules.payDebt(player.misery, unpaid.toLong()),
                )
            )
        }
    }
}
