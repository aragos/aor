package dev.aragos.aor

/** Expansion commands and the optional interruptions produced by a successful competition. */
sealed interface BoardCommand : Command {
    data class Place(
        val tokens: Int,
        val province: ProvinceId,
    ) : BoardCommand {
        override fun toText(): String = "place $tokens $province"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.place(state, actor, province, tokens)
    }

    /** Calculates the exact additional commitment, including the attacker's existing tokens. */
    data class Attack(val province: ProvinceId) : BoardCommand {
        override fun toText(): String = "attack $province"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.attack(state, actor, province)
    }

    data class ReduceMarker(val province: ProvinceId) : BoardCommand {
        override fun toText(): String = "reduce-marker $province"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            BoardGame.reduceMarker(state, actor, province)
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
                        checkRule(arguments.size == 2, ViolationCode.INVALID_COMMAND)
                        Place(
                            arguments[0].toInt(),
                            ProvinceId(arguments[1]),
                        )
                    }
                    "attack" -> {
                        checkRule(arguments.size == 1, ViolationCode.INVALID_COMMAND)
                        Attack(ProvinceId(arguments.single()))
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
                    "buy-expansion-card" -> noArguments(arguments, BuyExpansionCard)
                    "cathedral" -> noArguments(arguments, Cathedral)
                    "windmill" -> noArguments(arguments, Windmill)
                    else -> null
                }
            } catch (failure: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }

        private fun noArguments(arguments: List<String>, command: BoardCommand): BoardCommand {
            checkRule(arguments.isEmpty(), ViolationCode.INVALID_COMMAND)
            return command
        }
    }
}
