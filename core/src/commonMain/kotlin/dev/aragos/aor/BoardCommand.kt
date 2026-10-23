package dev.aragos.aor

enum class BoardRouteKind {
    AUTO,
    LAND,
    CARAVAN,
    SHIP,
}

/** A route preference; Caravan's intermediate province is explicit in canonical command text. */
data class BoardRouteChoice(
    val kind: BoardRouteKind = BoardRouteKind.AUTO,
    val via: ProvinceId? = null,
) {
    init {
        require((kind == BoardRouteKind.CARAVAN) == (via != null)) {
            "Only a Caravan route requires an intermediate province."
        }
    }

    fun toText(): String =
        when (kind) {
            BoardRouteKind.AUTO -> ""
            BoardRouteKind.LAND -> " land"
            BoardRouteKind.SHIP -> " ship"
            BoardRouteKind.CARAVAN -> " caravan $via"
        }
}

/** Expansion commands and the optional interruptions produced by a successful competition. */
sealed interface BoardCommand : Command {
    data class Place(
        val tokens: Int,
        val province: ProvinceId,
        val route: BoardRouteChoice = BoardRouteChoice(),
    ) : BoardCommand {
        override fun toText(): String = "place $tokens $province${route.toText()}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.place(state, actor, province, tokens, route)
    }

    /** Calculates the exact additional commitment, including the attacker's existing tokens. */
    data class Attack(
        val province: ProvinceId,
        val route: BoardRouteChoice = BoardRouteChoice(),
    ) : BoardCommand {
        override fun toText(): String = "attack $province${route.toText()}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.attack(state, actor, province, route)
    }

    data class ReduceMarker(val province: ProvinceId) : BoardCommand {
        override fun toText(): String = "reduce-marker $province"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.reduceMarker(state, actor, province)
    }

    data object EndExpansion : BoardCommand {
        override fun toText(): String = "end-expansion"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.endExpansion(state, actor)
    }

    data object BuyExpansionCard : BoardCommand {
        override fun toText(): String = "buy-expansion-card"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.buyExpansionCard(state, actor)
    }

    data object Cathedral : BoardCommand {
        override fun toText(): String = "cathedral"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.cathedral(state, actor)
    }

    data object Windmill : BoardCommand {
        override fun toText(): String = "windmill"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.windmill(state, actor)
    }

    data class OfferCard(val reference: String) : BoardCommand {
        override fun toText(): String = "offer-card $reference"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.offerCard(state, actor, reference)
    }

    companion object {
        /** Returns null for commands owned by another phase or subsystem. */
        fun parse(name: String, arguments: List<String>): BoardCommand? =
            try {
                when (name) {
                    "place" -> {
                        checkRule(arguments.size >= 2, ViolationCode.INVALID_COMMAND)
                        Place(
                            arguments[0].toInt(),
                            ProvinceId(arguments[1]),
                            route(arguments.drop(2)),
                        )
                    }
                    "attack" -> {
                        checkRule(arguments.isNotEmpty(), ViolationCode.INVALID_COMMAND)
                        Attack(ProvinceId(arguments[0]), route(arguments.drop(1)))
                    }
                    "reduce-marker" -> {
                        checkRule(arguments.size == 1, ViolationCode.INVALID_COMMAND)
                        ReduceMarker(ProvinceId(arguments.single()))
                    }
                    "offer-card" -> {
                        checkRule(
                            arguments.size == 1 &&
                                arguments.single().matches(Regex("hand-[1-9][0-9]*")),
                            ViolationCode.INVALID_COMMAND,
                        )
                        OfferCard(arguments.single())
                    }
                    "end-expansion" -> noArguments(arguments, EndExpansion)
                    "buy-expansion-card" -> noArguments(arguments, BuyExpansionCard)
                    "cathedral" -> noArguments(arguments, Cathedral)
                    "windmill" -> noArguments(arguments, Windmill)
                    else -> null
                }
            } catch (failure: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }

        private fun route(arguments: List<String>): BoardRouteChoice =
            when {
                arguments.isEmpty() -> BoardRouteChoice()
                arguments == listOf("land") -> BoardRouteChoice(BoardRouteKind.LAND)
                arguments == listOf("ship") -> BoardRouteChoice(BoardRouteKind.SHIP)
                arguments.size == 2 && arguments[0] == "caravan" ->
                    BoardRouteChoice(BoardRouteKind.CARAVAN, ProvinceId(arguments[1]))
                else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }

        private fun noArguments(arguments: List<String>, command: BoardCommand): BoardCommand {
            checkRule(arguments.isEmpty(), ViolationCode.INVALID_COMMAND)
            return command
        }
    }
}
