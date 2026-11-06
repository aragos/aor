package dev.aragos.aor

/** Public per-turn usage for a Cathedral owner against a player without Cathedral. */
data class CathedralPairView(val owner: PlayerId, val opponent: PlayerId, val used: Boolean) {
    companion object {
        internal fun from(state: GameState): List<CathedralPairView> {
            val (owners, opponents) =
                state.playerIds.partition { player ->
                    state.playerState(player).advances.owns(AdvanceId.CATHEDRAL)
                }
            return owners.flatMap { owner ->
                opponents.map { opponent ->
                    CathedralPairView(
                        owner,
                        opponent,
                        (owner to opponent) in state.flow.cathedralUses,
                    )
                }
            }
        }
    }
}
