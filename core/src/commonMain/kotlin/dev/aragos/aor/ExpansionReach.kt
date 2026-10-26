package dev.aragos.aor

/** A route proves reach from an original coloured presence or the player's original capital. */
sealed interface ExpansionRoute {
    val base: ProvinceId

    data class Local(override val base: ProvinceId) : ExpansionRoute

    data class Land(override val base: ProvinceId) : ExpansionRoute

    data class Caravan(override val base: ProvinceId, val via: ProvinceId) : ExpansionRoute

    /** [path] includes both the starting coast and destination; sea spaces count as one step. */
    data class Shipping(override val base: ProvinceId, val path: List<ShippingSpace>) :
        ExpansionRoute
}

sealed interface ShippingSpace {
    data class Coast(val province: ProvinceId) : ShippingSpace

    data class Sea(val sea: SeaArea) : ShippingSpace
}

object ExpansionReach {
    /**
     * Finds routes in automatic preference order: avoid shipping, then prefer fewer steps. Province
     * identifiers break equivalent-route ties independently of board insertion order. Token
     * commitments and remaining capacity are checked when choosing a placement plan.
     */
    fun routesTo(
        context: BoardRulesContext,
        player: PlayerId,
        destination: ProvinceId,
    ): List<ExpansionRoute> {
        val profile = context.player(player)
        if (
            profile.chaos ||
                destination !in context.provinceIds ||
                !canEnter(profile, context.province(destination))
        ) {
            return emptyList()
        }
        return context
            .bases(player)
            .flatMap { base ->
                listOfNotNull(
                    ExpansionRoute.Local(base).takeIf { base == destination },
                    ExpansionRoute.Land(base).takeIf {
                        connected(context.configuration.landBorders, base, destination)
                    },
                ) +
                    caravanRoutes(context, profile, base, destination) +
                    shippingRoutes(context, profile, base, destination)
            }
            .distinct()
            .sortedWith(
                compareBy<ExpansionRoute>(
                    { route -> if (route is ExpansionRoute.Shipping) 1 else 0 },
                    ::routeLength,
                    { route -> route.base.value },
                    ::routeKey,
                )
            )
    }

    private fun routeLength(route: ExpansionRoute): Int =
        when (route) {
            is ExpansionRoute.Local -> 0
            is ExpansionRoute.Land -> 1
            is ExpansionRoute.Caravan -> 2
            is ExpansionRoute.Shipping -> route.path.size - 1
        }

    private fun routeKey(route: ExpansionRoute): String =
        when (route) {
            is ExpansionRoute.Local,
            is ExpansionRoute.Land -> route.base.value
            is ExpansionRoute.Caravan -> route.via.value
            is ExpansionRoute.Shipping ->
                route.path.joinToString("/") { space ->
                    when (space) {
                        is ShippingSpace.Coast -> "coast:${space.province.value}"
                        is ShippingSpace.Sea -> "sea:${space.sea.name}"
                    }
                }
        }

    /** Validates the supplied route itself, so a longer but legal galley route remains usable. */
    fun validate(
        context: BoardRulesContext,
        player: PlayerId,
        destination: ProvinceId,
        route: ExpansionRoute,
    ) {
        val profile = context.player(player)
        checkRule(!profile.chaos, ViolationCode.INVALID_BOARD_PLAYER)
        val target = context.province(destination)
        checkRule(
            canEnter(profile, target),
            ViolationCode.PROVINCE_ACCESS_DENIED,
            listOf(destination.value),
        )
        checkRule(route.base in context.bases(player), ViolationCode.UNREACHABLE_PROVINCE)
        val borders = context.configuration.landBorders
        when (route) {
            is ExpansionRoute.Local ->
                checkRule(
                    route.base == destination,
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
            is ExpansionRoute.Land ->
                checkRule(
                    connected(borders, route.base, destination),
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
            is ExpansionRoute.Caravan -> {
                checkRule(
                    AdvanceId.CARAVAN in profile.advances &&
                        route.via != destination &&
                        connected(borders, route.base, route.via) &&
                        connected(borders, route.via, destination),
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
                checkRule(
                    canEnter(profile, context.province(route.via)),
                    ViolationCode.PROVINCE_ACCESS_DENIED,
                )
                checkRule(
                    !opponentFills(context, player, route.via),
                    ViolationCode.CARAVAN_INTERMEDIATE_OCCUPIED,
                    listOf(route.via.value),
                )
            }
            is ExpansionRoute.Shipping -> validateShipping(context, profile, destination, route)
        }
    }

    fun validateShippingCapacity(
        context: BoardRulesContext,
        player: PlayerId,
        destination: ProvinceId,
        newlyShipped: Int,
    ) {
        val profile = context.player(player)
        val previous = context.shipped[player].orEmpty()[destination] ?: 0
        checkRule(
            newlyShipped >= 0 &&
                profile.ships.fleet != ShippingFleet.NONE &&
                (profile.ships.fleet == ShippingFleet.OCEAN ||
                    previous.toLong() + newlyShipped <= profile.ships.level),
            ViolationCode.SHIPPING_CAPACITY_EXCEEDED,
            listOf(destination.value),
        )
        if (
            context.province(destination).overseas != null &&
                !context.board.hasPresence(player, destination)
        ) {
            val occupied =
                context.board.pieces.keys.count { province ->
                    context.province(province).overseas != null &&
                        context.board.hasPresence(player, province)
                }
            checkRule(occupied < profile.ships.level, ViolationCode.OVERSEAS_LIMIT_EXCEEDED)
        }
    }

    fun canEnter(profile: BoardPlayerProfile, province: ProvinceDefinition): Boolean =
        province.access.all { requirement ->
            requirement.isSatisfiedBy(profile.advances)
        }

    private fun caravanRoutes(
        context: BoardRulesContext,
        profile: BoardPlayerProfile,
        base: ProvinceId,
        destination: ProvinceId,
    ): List<ExpansionRoute.Caravan> {
        if (AdvanceId.CARAVAN !in profile.advances) return emptyList()
        return neighbors(context.configuration.landBorders, base)
            .filter { via ->
                via != destination &&
                    connected(context.configuration.landBorders, via, destination) &&
                    canEnter(profile, context.province(via)) &&
                    !opponentFills(context, profile.player, via)
            }
            .map { via -> ExpansionRoute.Caravan(base, via) }
    }

    private fun shippingRoutes(
        context: BoardRulesContext,
        profile: BoardPlayerProfile,
        base: ProvinceId,
        destination: ProvinceId,
    ): List<ExpansionRoute.Shipping> {
        if (
            !context.province(base).coastal ||
                !context.province(destination).coastal ||
                base == destination
        ) {
            return emptyList()
        }
        return when (profile.ships.fleet) {
            ShippingFleet.NONE -> emptyList()
            ShippingFleet.GALLEY -> galleyRoutes(context, profile, base, destination)
            ShippingFleet.SEAWORTHY ->
                if (context.province(destination).overseas == null) {
                    listOf(directShipping(base, destination))
                } else {
                    emptyList()
                }
            ShippingFleet.OCEAN -> listOf(directShipping(base, destination))
        }
    }

    private fun validateShipping(
        context: BoardRulesContext,
        profile: BoardPlayerProfile,
        destination: ProvinceId,
        route: ExpansionRoute.Shipping,
    ) {
        checkRule(
            context.province(route.base).coastal &&
                context.province(destination).coastal &&
                profile.ships.fleet != ShippingFleet.NONE,
            ViolationCode.UNREACHABLE_PROVINCE,
        )
        val expected = listOf(ShippingSpace.Coast(route.base), ShippingSpace.Coast(destination))
        when (profile.ships.fleet) {
            ShippingFleet.NONE -> throw RuleViolation(ViolationCode.UNREACHABLE_PROVINCE)
            ShippingFleet.SEAWORTHY ->
                checkRule(
                    context.province(destination).overseas == null && route.path == expected,
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
            ShippingFleet.OCEAN ->
                checkRule(route.path == expected, ViolationCode.UNREACHABLE_PROVINCE)
            ShippingFleet.GALLEY -> {
                checkRule(
                    route.path.firstOrNull() == expected.first() &&
                        route.path.lastOrNull() == expected.last() &&
                        route.path.size - 1 in 1..profile.ships.level,
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
                val seas = route.path.filterIsInstance<ShippingSpace.Sea>()
                checkRule(
                    seas.size <= 1 &&
                        seas.none { space -> space.sea.isOcean } &&
                        (seas.isEmpty() || AdvanceId.THE_HEAVENS in profile.advances),
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
                for (space in route.path.filterIsInstance<ShippingSpace.Coast>()) {
                    checkRule(
                        canEnter(profile, context.province(space.province)),
                        ViolationCode.PROVINCE_ACCESS_DENIED,
                    )
                }
                checkRule(
                    route.path.zipWithNext().all { step ->
                        waterNeighbors(context, step.first).contains(step.second)
                    },
                    ViolationCode.UNREACHABLE_PROVINCE,
                )
            }
        }
    }

    private fun galleyRoutes(
        context: BoardRulesContext,
        profile: BoardPlayerProfile,
        base: ProvinceId,
        destination: ProvinceId,
    ): List<ExpansionRoute.Shipping> {
        data class Visit(val space: ShippingSpace, val seas: Int)
        val start = Visit(ShippingSpace.Coast(base), 0)
        val paths = mutableMapOf(start to listOf(start.space))
        val queue = ArrayDeque<Visit>().apply { addLast(start) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val path =
                requireNotNull(paths[current]) {
                    "Every queued shipping visit must have a recorded path: ${current}."
                }
            if (path.size - 1 >= profile.ships.level) continue
            for (neighbor in waterNeighbors(context, current.space)) {
                val seaCount = current.seas + if (neighbor is ShippingSpace.Sea) 1 else 0
                if (
                    neighbor is ShippingSpace.Sea &&
                        (seaCount > 1 ||
                            neighbor.sea.isOcean ||
                            AdvanceId.THE_HEAVENS !in profile.advances)
                ) {
                    continue
                }
                if (
                    neighbor is ShippingSpace.Coast &&
                        !canEnter(profile, context.province(neighbor.province))
                ) {
                    continue
                }
                val visit = Visit(neighbor, seaCount)
                if (visit in paths) continue
                paths[visit] = path + neighbor
                queue.addLast(visit)
            }
        }
        return paths
            .filterKeys { visit -> visit.space == ShippingSpace.Coast(destination) }
            .values
            .map { path -> ExpansionRoute.Shipping(base, path) }
    }

    private fun waterNeighbors(
        context: BoardRulesContext,
        from: ShippingSpace,
    ): Set<ShippingSpace> =
        when (from) {
            is ShippingSpace.Coast ->
                neighbors(context.configuration.coastalConnections, from.province)
                    .map { province -> ShippingSpace.Coast(province) }
                    .toSet() +
                    context.configuration.seaCoasts
                        .filter { connection -> connection.province == from.province }
                        .map { connection -> ShippingSpace.Sea(connection.sea) }
            is ShippingSpace.Sea ->
                context.configuration.seaCoasts
                    .filter { connection -> connection.sea == from.sea }
                    .map { connection -> ShippingSpace.Coast(connection.province) }
                    .toSet()
        }

    private fun directShipping(base: ProvinceId, destination: ProvinceId): ExpansionRoute.Shipping =
        ExpansionRoute.Shipping(
            base,
            listOf(ShippingSpace.Coast(base), ShippingSpace.Coast(destination)),
        )

    private fun opponentFills(
        context: BoardRulesContext,
        player: PlayerId,
        province: ProvinceId,
    ): Boolean =
        when (val occupants = context.board.pieces[province]) {
            is ProvincePieces.Marker -> occupants.owner != player
            is ProvincePieces.Tokens ->
                occupants.total == context.province(province).marketNumber &&
                    occupants.holdings.keys.any { owner -> owner != player }
            null -> false
        }

    internal fun connected(
        edges: Set<ProvinceConnection>,
        first: ProvinceId,
        second: ProvinceId,
    ): Boolean = first != second && ProvinceConnection.of(first, second) in edges

    private fun neighbors(edges: Set<ProvinceConnection>, province: ProvinceId): Set<ProvinceId> =
        edges
            .mapNotNull { edge ->
                when (province) {
                    edge.first -> edge.second
                    edge.second -> edge.first
                    else -> null
                }
            }
            .toSet()
}
