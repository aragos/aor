package dev.aragos.aor

import kotlinx.collections.immutable.toPersistentMap

/**
 * Public, derived inputs for one ready Expansion decision. This is not persisted and contains no
 * hands, deck, pending answers or phase driver. [retainedColouredBases] preserves converted-token
 * reach omitted from the ordinary display board; [shipped] includes failed deliveries this phase.
 * Optional victory continuations and marker-bonus bookkeeping remain outside this projection.
 */
data class ExpansionPositionView(
    val playerCount: Int,
    val rules: RuleOptions,
    val catalogVersion: CatalogVersion,
    val board: List<ProvinceView>,
    val retainedColouredBases: Set<ProvinceId>,
    val players: List<BoardPlayerProfile>,
    val shipped: Map<PlayerId, Map<ProvinceId, Int>>,
    val militaryPlays: List<MilitaryPlay>,
    val actor: PlayerId,
    val poolTokens: Int,
    val stockTokens: Map<PlayerId, Int>,
    val orderBox: Int,
    val turnOrder: List<PlayerId>,
    val cathedralUsedAgainst: Map<PlayerId, Set<PlayerId>>,
) {
    /**
     * Restore only typed public rule inputs; this never constructs a game state or action offer.
     */
    fun toPosition(): ExpansionPosition {
        require(board.map { it.id }.distinct().size == board.size) {
            "Public expansion provinces must be unique."
        }
        require(players.map { it.player }.distinct().size == players.size) {
            "Public expansion player profiles must be unique."
        }
        require(
            retainedColouredBases.all { id ->
                board.any { it.id == id && it.marker?.side == PieceSide.WHITE }
            }
        ) {
            "Retained expansion bases require a white marker."
        }
        val configuration = CatalogConfiguration(playerCount, rules, catalogVersion)
        val pieces = board.associate { province ->
            val marker = province.marker
            val occupants =
                if (marker != null) {
                    require(province.tokens.isEmpty()) {
                        "A public expansion province cannot contain both a marker and tokens."
                    }
                    ProvincePieces.Marker(
                        marker.owner,
                        marker.side,
                        province.id in retainedColouredBases,
                    )
                } else {
                    require(
                        province.tokens.map { it.owner }.distinct().size == province.tokens.size
                    ) {
                        "Public expansion token owners must be unique within a province."
                    }
                    ProvincePieces.Tokens(
                        province.tokens
                            .associate {
                                it.owner to TokenHolding(it.coloured, it.white)
                            }
                            .toPersistentMap()
                    )
                }
            province.id to occupants
        }
        return ExpansionPosition(
            context =
                BoardRulesContext(
                    configuration,
                    BoardState(pieces.toPersistentMap(), configuration.map),
                    players.associateBy(BoardPlayerProfile::player),
                    shipped,
                    militaryPlays,
                ),
            actor = actor,
            poolTokens = poolTokens,
            stockTokens = stockTokens,
            orderBox = orderBox,
            turnOrder = turnOrder,
            cathedralUsedAgainst = cathedralUsedAgainst,
        )
    }

    companion object {
        internal fun from(state: GameState, viewer: PlayerState): ExpansionPositionView? {
            if (
                state.stage != Stage.EXPANSION ||
                    state.flow.actor != viewer.player.id ||
                    state.flow.decisions.isNotEmpty() ||
                    viewer.misery == MiseryStep.CHAOS
            ) {
                return null
            }
            val context = state.boardContext
            return ExpansionPositionView(
                playerCount = context.configuration.playerCount,
                rules = context.configuration.rules,
                catalogVersion = context.configuration.version,
                board = context.board.pieces.map { (id, pieces) -> ProvinceView.from(id, pieces) },
                retainedColouredBases =
                    context.board.pieces
                        .filterValues {
                            it is ProvincePieces.Marker && it.retainsColouredBase
                        }
                        .keys,
                players = context.players.values.toList(),
                shipped = context.shipped,
                militaryPlays = context.militaryPlays,
                actor = viewer.player.id,
                poolTokens = viewer.poolTokens,
                stockTokens = state.players.mapValues { (_, player) -> player.stockTokens },
                orderBox =
                    requireNotNull(state.orderBoxes[viewer.player.id]) {
                        "A ready expansion actor requires its printed order box."
                    },
                turnOrder = state.turnOrder,
                cathedralUsedAgainst =
                    state.flow.cathedralUses
                        .groupBy { it.first }
                        .mapValues { (_, pairs) ->
                            pairs.map { it.second }.toSet()
                        },
            )
        }
    }
}
