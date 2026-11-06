package dev.aragos.aor

/**
 * Public occupancy removed by an area effect. Losing marker control is certain even when its
 * owner's replacement token requires a later decision. [tokensRemoved] counts existing square
 * tokens returned to stock, excluding any replacements. Each row describes one owner's pieces.
 */
data class CardBoardLossView(
    val province: ProvinceId,
    val owner: PlayerId,
    val markerControlLost: Boolean,
    val tokensRemoved: Int,
) {
    init {
        require(
            markerControlLost && tokensRemoved == 0 || !markerControlLost && tokensRemoved > 0
        ) {
            "A board loss describes either one marker's control or a positive existing token count."
        }
    }
}

internal object CardBoardProjection {
    /** Area targets come from the resolved rule outcome, never a second copy of targeting rules. */
    fun losses(board: BoardState, resolution: CardResolution): List<CardBoardLossView> {
        if (resolution !is CardResolution.BlackDeath) return emptyList()
        val markers =
            resolution.reductions.map { reduction ->
                CardBoardLossView(
                    province = reduction.province,
                    owner = reduction.owner,
                    markerControlLost = true,
                    tokensRemoved = 0,
                )
            }
        val tokens =
            resolution.tokenProvinces.flatMap { province ->
                val occupancy =
                    checkNotNull(board.pieces[province] as? ProvincePieces.Tokens) {
                        "A resolved existing-token loss requires tokens in its public province."
                    }
                occupancy.holdings.map { (owner, holding) ->
                    CardBoardLossView(
                        province = province,
                        owner = owner,
                        markerControlLost = false,
                        tokensRemoved = holding.total,
                    )
                }
            }
        return markers + tokens
    }
}
