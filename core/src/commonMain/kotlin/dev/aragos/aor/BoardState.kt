package dev.aragos.aor

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

enum class PieceSide {
    COLOURED,
    WHITE,
}

/** Physical square tokens owned by one player in one province, separated by expansion age. */
data class TokenHolding(val coloured: Int = 0, val white: Int = 0) {
    val total: Int
        get() = coloured + white

    init {
        require(coloured >= 0 && white >= 0 && coloured.toLong() + white in 1L..36L) {
            "A token holding requires one through 36 nonnegative coloured and white tokens."
        }
    }

    fun add(count: Int, side: PieceSide): TokenHolding {
        require(count > 0) { "Adding tokens requires a positive count." }
        return when (side) {
            PieceSide.COLOURED -> copy(coloured = coloured + count)
            PieceSide.WHITE -> copy(white = white + count)
        }
    }
}

/** A province contains square tokens or a single domination marker, never both. */
sealed interface ProvincePieces {
    /** Preserve ownership and physical counts while making every piece available as a base. */
    fun flipWhitePieces(): ProvincePieces

    data class Tokens(val holdings: PersistentMap<PlayerId, TokenHolding>) : ProvincePieces {
        init {
            require(holdings.isNotEmpty()) { "An occupied province requires at least one holding." }
        }

        val total: Int
            get() = holdings.values.sumOf(TokenHolding::total)

        override fun flipWhitePieces(): Tokens =
            Tokens(
                holdings
                    .mapValues { holding -> TokenHolding(coloured = holding.value.total) }
                    .toPersistentMap()
            )
    }

    /**
     * [retainsColouredBase] preserves the owner's existing coloured-token reach after conversion to
     * a white marker. It belongs to this occupancy and disappears if the marker is replaced. It
     * does not make the new marker coloured for Caravan control or commodity payments.
     */
    data class Marker(
        val owner: PlayerId,
        val side: PieceSide,
        val retainsColouredBase: Boolean = false,
    ) : ProvincePieces {
        init {
            require(!retainsColouredBase || side == PieceSide.WHITE) {
                "Only a white marker needs to retain a converted coloured-token base."
            }
        }

        override fun flipWhitePieces(): Marker =
            copy(side = PieceSide.COLOURED, retainsColouredBase = false)
    }
}

/**
 * Resource consequences of a board replacement. Counts name physical pieces, not combat strength.
 * The driver chooses whether required square tokens come from stock or the expansion pool, and
 * returns removed square tokens to stock and removed markers to their owner's mat.
 */
data class BoardChange(
    val board: BoardState,
    val tokensReturned: Map<PlayerId, Int> = emptyMap(),
    val tokensRequired: Map<PlayerId, Int> = emptyMap(),
    val markersReturned: Map<PlayerId, Int> = emptyMap(),
    val markersRequired: Map<PlayerId, Int> = emptyMap(),
)

/**
 * Immutable board occupancy; absent entries are empty provinces. Pending attack commitments are
 * stored by the driver outside this map, so its stacking invariants hold even during competition.
 * [definition] owns the province identities and market limits used to validate every replacement.
 */
data class BoardState(
    val pieces: PersistentMap<ProvinceId, ProvincePieces> = persistentMapOf(),
    val definition: BoardMapDefinition = StandardMapData.definition,
) {
    init {
        for ((province, occupants) in pieces) {
            val provinceDefinition =
                requireNotNull(definition.byId[province]) {
                    "The board contains an unknown province: $province."
                }
            when (occupants) {
                is ProvincePieces.Tokens ->
                    require(occupants.total <= provinceDefinition.marketNumber) {
                        "Tokens cannot exceed the province's market number outside competition: $province."
                    }
                is ProvincePieces.Marker ->
                    require(!provinceDefinition.isSatellite) {
                        "A satellite cannot contain a domination marker: $province."
                    }
            }
        }
        val markers =
            pieces.values
                .filterIsInstance<ProvincePieces.Marker>()
                .groupingBy(ProvincePieces.Marker::owner)
                .eachCount()
        require(markers.values.all { count -> count <= 25 }) {
            "A player has only 25 domination markers."
        }
    }

    fun markerCount(player: PlayerId): Int =
        pieces.values.count { occupants ->
            occupants is ProvincePieces.Marker && occupants.owner == player
        }

    fun tokenCount(player: PlayerId): Int =
        pieces.values.sumOf { occupants ->
            (occupants as? ProvincePieces.Tokens)?.holdings.orEmpty()[player]?.total ?: 0
        }

    /** Any owned piece counts as presence, including white pieces and partial token holdings. */
    fun hasPresence(player: PlayerId, province: ProvinceId): Boolean =
        when (val occupants = pieces[province]) {
            is ProvincePieces.Marker -> occupants.owner == player
            is ProvincePieces.Tokens -> player in occupants.holdings
            null -> false
        }

    fun commodityCount(player: PlayerId, commodity: Commodity): Int = pieces.count { entry ->
        val marker = entry.value as? ProvincePieces.Marker
        marker?.owner == player && commodity in definition.province(entry.key).commodities
    }

    /**
     * Any coloured presence is a base, including a partial holding converted to a white marker. The
     * marker retains that reach only while the same occupancy remains on the board.
     */
    fun colouredPresence(player: PlayerId): Set<ProvinceId> =
        pieces
            .filterValues { occupants ->
                when (occupants) {
                    is ProvincePieces.Marker ->
                        occupants.owner == player &&
                            (occupants.side == PieceSide.COLOURED || occupants.retainsColouredBase)
                    is ProvincePieces.Tokens -> (occupants.holdings[player]?.coloured ?: 0) > 0
                }
            }
            .keys

    /** Caravan control requires a coloured marker or enough coloured square tokens to fill it. */
    fun controller(province: ProvinceId): PlayerId? =
        when (val occupants = pieces[province]) {
            is ProvincePieces.Marker ->
                occupants.owner.takeIf { occupants.side == PieceSide.COLOURED }
            is ProvincePieces.Tokens ->
                occupants.holdings.entries
                    .singleOrNull { holding ->
                        holding.value.coloured >= definition.province(province).marketNumber
                    }
                    ?.key
            null -> null
        }

    /**
     * Card placement uses the coloured side. White expansion markers retain the owner's existing
     * coloured-token base, without retaining a defeated owner's reach or changing the marker side.
     */
    fun placeMarker(
        province: ProvinceId,
        owner: PlayerId,
        side: PieceSide = PieceSide.COLOURED,
    ): BoardChange {
        val colouredTokens =
            (pieces[province] as? ProvincePieces.Tokens)?.holdings.orEmpty()[owner]?.coloured ?: 0
        return replace(
            province,
            ProvincePieces.Marker(
                owner,
                side,
                retainsColouredBase = side == PieceSide.WHITE && colouredTokens > 0,
            ),
        )
    }

    /** Event reductions and the voluntary swap use a single coloured square token. */
    fun replaceWithToken(
        province: ProvinceId,
        owner: PlayerId,
        side: PieceSide = PieceSide.COLOURED,
    ): BoardChange =
        replace(province, ProvincePieces.Tokens(persistentMapOf(owner to holding(1, side))))

    fun remove(province: ProvinceId): BoardChange = replace(province, null)

    /** War cession replaces a marker; square token presence cannot be ceded as a marker. */
    fun transferMarker(province: ProvinceId, owner: PlayerId): BoardChange {
        checkRule(pieces[province] is ProvincePieces.Marker, ViolationCode.INVALID_BOARD_MUTATION)
        return placeMarker(province, owner)
    }

    fun flipWhitePieces(): BoardState =
        copy(pieces = pieces.mapValues { entry -> entry.value.flipWhitePieces() }.toPersistentMap())

    internal fun replace(province: ProvinceId, replacement: ProvincePieces?): BoardChange {
        checkRule(
            province in definition.byId,
            ViolationCode.UNKNOWN_PROVINCE,
            listOf(province.value),
        )
        val previous = pieces[province]
        val board =
            copy(
                pieces =
                    if (replacement == null) {
                        pieces.removing(province)
                    } else {
                        pieces.putting(province, replacement)
                    }
            )
        return BoardChange(
            board = board,
            tokensReturned =
                (previous as? ProvincePieces.Tokens)
                    ?.holdings
                    ?.mapValues { holding -> holding.value.total }
                    .orEmpty(),
            tokensRequired =
                (replacement as? ProvincePieces.Tokens)
                    ?.holdings
                    ?.mapValues { holding -> holding.value.total }
                    .orEmpty(),
            markersReturned =
                (previous as? ProvincePieces.Marker)
                    ?.let { marker ->
                        mapOf(marker.owner to 1)
                    }
                    .orEmpty(),
            markersRequired =
                (replacement as? ProvincePieces.Marker)
                    ?.let { marker ->
                        mapOf(marker.owner to 1)
                    }
                    .orEmpty(),
        )
    }

    internal companion object {
        fun holding(count: Int, side: PieceSide): TokenHolding =
            when (side) {
                PieceSide.COLOURED -> TokenHolding(coloured = count)
                PieceSide.WHITE -> TokenHolding(white = count)
            }
    }
}
