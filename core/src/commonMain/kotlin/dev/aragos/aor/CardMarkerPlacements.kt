package dev.aragos.aor

/** Card consequences applied once, after every physical marker placement choice is resolved. */
sealed interface MarkerPlacementAftermath {
    val player: PlayerId

    data class Crusades(override val player: PlayerId) : MarkerPlacementAftermath

    data class War(val settlement: WarSettlement) : MarkerPlacementAftermath {
        override val player: PlayerId
            get() = settlement.winner
    }
}

/**
 * A card removes the destination's old pieces even when its recipient forgoes the new marker. At
 * the 25-marker limit the recipient may instead move a coloured marker from another controlled
 * province. Its source becomes empty, with no stock or pool token payment. Each War award is a
 * separate choice; declining it adds no extra misery to the loser.
 */
internal object CardMarkerPlacements {
    fun crusades(state: GameState, placement: CardResolution.CrusadesPlacement): GameState {
        val cleared =
            state
                .copy(effects = placement.effects)
                .withBoardChange(state.board.remove(placement.province))
        return placeRemaining(
            cleared,
            listOf(placement.province),
            MarkerPlacementAftermath.Crusades(placement.player),
        )
    }

    fun war(state: GameState, settlement: WarSettlement): GameState {
        val destinations = settlement.provinces.sortedBy(ProvinceId::value)
        val cleared =
            destinations.fold(state) { current, province ->
                current.withBoardChange(current.board.remove(province))
            }
        return placeRemaining(cleared, destinations, MarkerPlacementAftermath.War(settlement))
    }

    fun sources(state: GameState, player: PlayerId, destination: ProvinceId): List<ProvinceId> =
        state.board.pieces
            .filter { (province, pieces) ->
                province != destination &&
                    pieces == ProvincePieces.Marker(player, PieceSide.COLOURED)
            }
            .keys
            .sortedBy(ProvinceId::value)

    fun choose(state: GameState, actor: PlayerId, source: ProvinceId?): GameState {
        val decision = state.flow.decisions.lastOrNull() as? CardDecision.MarkerPlacement
        checkRule(decision != null, ViolationCode.WRONG_PHASE)
        val frame =
            requireNotNull(decision) { "A validated marker choice requires a pending frame." }
        checkRule(frame.player == actor, ViolationCode.WRONG_PLAYER)
        state.playerState(actor).requireActive()
        val resumed = state.popDecision()
        if (source == null) {
            return placeRemaining(resumed, frame.destinations.drop(1), frame.aftermath)
        }
        cardRule(
            source in sources(state, actor, frame.destination),
            CardRuleCode.ILLEGAL_TARGET,
            "Move a coloured domination marker from another province you control.",
        )
        val freed = resumed.withBoardChange(resumed.board.remove(source))
        val moved = freed.withBoardChange(freed.board.placeMarker(frame.destination, actor))
        val placed =
            moved.copy(
                flow =
                    moved.flow.copy(
                        voluntaryReduced = moved.flow.voluntaryReduced + source,
                        markerOrigins = moved.flow.markerOrigins - source,
                    )
            )
        return placeRemaining(placed, frame.destinations.drop(1), frame.aftermath)
    }

    private fun placeRemaining(
        initial: GameState,
        destinations: List<ProvinceId>,
        aftermath: MarkerPlacementAftermath,
    ): GameState {
        var state = initial
        for ((index, destination) in destinations.withIndex()) {
            if (state.board.markerCount(aftermath.player) == 25) {
                return state.pushDecision(
                    CardDecision.MarkerPlacement(destinations.drop(index), aftermath)
                )
            }
            state = state.withBoardChange(state.board.placeMarker(destination, aftermath.player))
        }
        return CardGame.completeMarkerPlacement(state, aftermath)
    }
}
