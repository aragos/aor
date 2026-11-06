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
)

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
        get() = configuration.provinceIds

    fun player(player: PlayerId): BoardPlayerProfile =
        players[player]
            ?: throw RuleViolation(ViolationCode.INVALID_BOARD_PLAYER, listOf(player.value))

    fun province(province: ProvinceId): ProvinceDefinition {
        checkRule(province in provinceIds, ViolationCode.UNKNOWN_PROVINCE, listOf(province.value))
        return configuration.map.province(province)
    }

    // A context owns one immutable board/capability snapshot. Copies rebuild these derived routes;
    // pool tokens and per-destination shipping capacity are still checked for each placement.
    private val routesByPlayer by lazy {
        players.keys.associateWith { player -> lazy { destinationRoutes(player) } }
    }

    private fun destinationRoutes(player: PlayerId) = provinceIds.associateWith { destination ->
        lazy { ExpansionReach.findRoutesTo(this, player, destination) }
    }

    internal fun routesTo(player: PlayerId, destination: ProvinceId): List<ExpansionRoute> {
        val routes =
            routesByPlayer[player]?.value
                ?: throw RuleViolation(ViolationCode.INVALID_BOARD_PLAYER, listOf(player.value))
        return routes[destination]?.value.orEmpty()
    }

    private val galleyRoutesByPlayer by lazy {
        players.mapValues { (_, profile) ->
            lazy {
                bases(profile.player)
                    .filter { province(it).coastal }
                    .associateWith { base -> ExpansionReach.galleyRoutes(this, profile, base) }
            }
        }
    }

    internal fun galleyRoutes(
        player: PlayerId,
        base: ProvinceId,
        destination: ProvinceId,
    ): List<ExpansionRoute.Shipping> {
        val routesByBase = galleyRoutesByPlayer[player]?.value ?: return emptyList()
        return routesByBase[base]?.let { routes -> routes[destination] }.orEmpty()
    }

    private val basesByPlayer by lazy {
        players.mapValues { (id, profile) -> board.colouredPresence(id) + profile.capital }
    }

    /** Live coloured presence includes an existing base retained by its white marker conversion. */
    fun bases(player: PlayerId): Set<ProvinceId> =
        basesByPlayer[player]
            ?: throw RuleViolation(ViolationCode.INVALID_BOARD_PLAYER, listOf(player.value))

    fun recordShipment(player: PlayerId, destination: ProvinceId, tokens: Int): BoardRulesContext {
        require(tokens >= 0) { "A recorded shipping delivery cannot be negative." }
        val previous = shipped[player].orEmpty()
        val count = (previous[destination] ?: 0).toLong() + tokens
        require(count <= Int.MAX_VALUE) { "Recorded shipping usage must fit an integer." }
        return copy(shipped = shipped + (player to (previous + (destination to count.toInt()))))
    }
}
