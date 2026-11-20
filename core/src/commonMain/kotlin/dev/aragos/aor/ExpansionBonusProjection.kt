package dev.aragos.aor

/** Current public eligible white markers, in actual turn order; no future bonus is promised. */
data class ExpansionBonusPlayerView(val player: PlayerId, val eligibleMarkers: Int)

/**
 * Public research projection. [leader] is who would earn the bonus at this board position, not a
 * prediction of later expansion. It is absent when no active player has an eligible marker.
 * Placement origins distinguish ordinary expansion, event rebuilding and voluntary rebuilding.
 */
data class ExpansionBonusView(val players: List<ExpansionBonusPlayerView>) {
    val leader: PlayerId?
        get() =
            players.maxByOrNull { it.eligibleMarkers }?.takeIf { it.eligibleMarkers > 0 }?.player
}

/** One counting owner for the actual bonus and its public-only research quote. */
object ExpansionBonusProjection {
    fun from(state: GameState): ExpansionBonusView =
        ExpansionBonusView(
            state.turnOrder
                .filter { id -> state.playerState(id).misery != MiseryStep.CHAOS }
                .map { id ->
                    val count =
                        state.board.pieces.count { (province, pieces) ->
                            pieces is ProvincePieces.Marker &&
                                pieces.owner == id &&
                                pieces.side == PieceSide.WHITE &&
                                state.rebuiltMarkerBonus.counts(
                                    state.flow.markerOrigins[province]
                                        ?: MarkerPlacementOrigin.EXPANSION
                                )
                        }
                    ExpansionBonusPlayerView(id, count)
                }
        )
}
