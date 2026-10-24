package dev.aragos.aor

/**
 * Player-owned capabilities copied from authoritative game state by the game driver. [capital] is
 * the original capital chosen during setup; constructing a query does not repeat setup validation.
 */
data class BoardPlayerProfile(
    val player: PlayerId,
    val capital: ProvinceId,
    val advances: Set<AdvanceId> = emptySet(),
    val ships: ShippingCapability = ShippingCapability.NONE,
    val chaos: Boolean = false,
) {
    val homeArea: GeographicalArea
        get() =
            requireNotNull(ProvinceData.definition(capital).area) {
                "An original capital must belong to a geographical area."
            }
}

/**
 * Immutable inputs for board operations, separate from phase progression and player resources. The
 * driver supplies registered players, their selected capitals, board pieces, shipping usage and
 * active military cards from authoritative game state for [configuration]. Query construction
 * trusts that state; each operation validates its requested player, province, route and resource
 * use.
 *
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
    val provinceIds: Set<ProvinceId>
        get() = configuration.provinces.map(ProvinceDefinition::id).toSet()

    fun player(player: PlayerId): BoardPlayerProfile =
        players[player]
            ?: throw RuleViolation(ViolationCode.INVALID_BOARD_PLAYER, listOf(player.value))

    fun province(province: ProvinceId): ProvinceDefinition {
        checkRule(province in provinceIds, ViolationCode.UNKNOWN_PROVINCE, listOf(province.value))
        return ProvinceData.definition(province)
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
