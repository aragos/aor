package dev.aragos.aor

/** Player-owned capabilities copied into a pure board-rules query by the game driver. */
data class BoardPlayerProfile(
    val player: PlayerId,
    val capital: ProvinceId,
    val advances: Set<AdvanceId> = emptySet(),
    val ships: ShippingCapability = ShippingCapability.NONE,
    val chaos: Boolean = false,
) {
    init {
        require(CapitalData.definitions.any { definition -> definition.id == capital.value }) {
            "A board player requires an original capital from the catalog: $capital."
        }
    }

    val homeArea: GeographicalArea
        get() =
            requireNotNull(ProvinceData.byId.getValue(capital).area) {
                "An original capital must belong to a geographical area."
            }
}

/**
 * Immutable inputs for board operations, separate from phase progression and player resources.
 * [shipped] counts newly delivered physical tokens per destination in this Expansion phase,
 * including failed attacks. Existing tokens already in a province are never charged again.
 */
data class BoardRulesContext(
    val configuration: CatalogConfiguration,
    val board: BoardState,
    val players: Map<PlayerId, BoardPlayerProfile>,
    val shipped: Map<PlayerId, Map<ProvinceId, Int>> = emptyMap(),
    val militaryPlays: List<MilitaryPlay> = emptyList(),
) {
    init {
        require(
            players.size == configuration.playerCount &&
                players.all { entry -> entry.key == entry.value.player }
        ) {
            "Board profiles must identify every player in the configured game exactly once."
        }
        require(
            players.values.map(BoardPlayerProfile::capital).distinct().size == players.size &&
                players.values.all { profile -> profile.capital.value in configuration.capitals }
        ) {
            "Board profiles must use the configured original capitals exactly once."
        }
        require(board.pieces.keys.all { province -> province in provinceIds }) {
            "The board cannot contain pieces in an unavailable geographical area."
        }
        require(
            board.pieces.values.all { occupants ->
                when (occupants) {
                    is ProvincePieces.Marker -> occupants.owner in players
                    is ProvincePieces.Tokens -> occupants.holdings.keys.all(players::containsKey)
                }
            }
        ) {
            "Every board piece requires a registered owner, including neutral owners in chaos."
        }
        require(
            shipped.all { entry ->
                entry.key in players &&
                    entry.value.all { delivery ->
                        delivery.key in provinceIds && delivery.value >= 0
                    }
            }
        ) {
            "Shipping usage requires registered players, available provinces and nonnegative counts."
        }
        require(
            militaryPlays.all { play -> play.player in players } &&
                militaryPlays.map(MilitaryPlay::card).distinct().size == militaryPlays.size
        ) {
            "Military cards must each have one registered player for the current turn."
        }
    }

    val provinceIds: Set<ProvinceId>
        get() = configuration.provinces.map(ProvinceDefinition::id).toSet()

    fun player(player: PlayerId): BoardPlayerProfile {
        checkRule(player in players, ViolationCode.INVALID_BOARD_PLAYER, listOf(player.value))
        return players.getValue(player)
    }

    fun province(province: ProvinceId): ProvinceDefinition {
        checkRule(province in provinceIds, ViolationCode.UNKNOWN_PROVINCE, listOf(province.value))
        return ProvinceData.byId.getValue(province)
    }

    fun bases(player: PlayerId): Set<ProvinceId> =
        board.colouredPresence(player) + player(player).capital

    fun recordShipment(player: PlayerId, destination: ProvinceId, tokens: Int): BoardRulesContext {
        require(tokens >= 0) { "A recorded shipping delivery cannot be negative." }
        val previous = shipped[player].orEmpty()
        val count = (previous[destination] ?: 0).toLong() + tokens
        require(count <= Int.MAX_VALUE) { "Recorded shipping usage must fit an integer." }
        return copy(shipped = shipped + (player to (previous + (destination to count.toInt()))))
    }
}
