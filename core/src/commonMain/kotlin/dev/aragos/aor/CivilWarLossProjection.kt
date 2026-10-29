package dev.aragos.aor

/** Authoritative payable losses, offered only after any capital replacement has resolved. */
data class CivilWarLossView(
    val player: PlayerId,
    val cashCost: Int,
    val tokenCost: Int,
    val cashAfter: Int,
    val poolAfter: Int,
) {
    companion object {
        internal fun from(state: GameState): CivilWarLossView? {
            val decision =
                state.flow.decisions.lastOrNull() as? CardDecision.CivilWarLoss ?: return null
            val player = state.playerState(decision.player)
            val losses =
                CivilWarLosses.forResources(player.writtenCash, player.cash, player.poolTokens)
            return CivilWarLossView(
                decision.player,
                losses.cash,
                losses.expansionTokens,
                player.cash - losses.cash,
                player.poolTokens - losses.expansionTokens,
            )
        }
    }
}
