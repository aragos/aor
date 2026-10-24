package dev.aragos.aor

/** Replay commands owned by card effects and their suspended victim decisions. */
sealed interface CardCommand : Command {
    data class PlayCard(val reference: String, val choice: CardChoice = CardChoice.None) :
        CardCommand {
        init {
            require(reference.matches(Regex("[a-z][a-z0-9-]*"))) {
                "A hand reference must be a lowercase log identifier."
            }
        }

        override fun toText(): String =
            "play-card $reference" +
                when (choice) {
                    CardChoice.None -> ""
                    is CardChoice.CommodityChoice ->
                        " commodity ${choice.commodity.name.lowercase()}"
                    is CardChoice.PlayerChoice -> " player ${choice.player}"
                    is CardChoice.ProvinceChoice -> " province ${choice.province}"
                    is CardChoice.CategoryChoice ->
                        " category ${choice.category?.name?.lowercase() ?: "none"}"
                    is CardChoice.LeaderProtection -> " protect ${choice.cash}"
                }

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.play(state, actor, reference, choice)
    }

    data class ChooseCivilWarLoss(val loss: CivilWarLossChoice) : CardCommand {
        override fun toText(): String =
            "civil-war-loss ${if (loss == CivilWarLossChoice.CASH) "cash" else "tokens"}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.chooseCivilWarLoss(state, actor, loss)
    }

    data class ReplaceEventToken(val usePool: Boolean) : CardCommand {
        override fun toText(): String = "replace-event-token ${if (usePool) "pool" else "none"}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.replaceEventToken(state, actor, usePool)
    }

    data object ResolveWar : CardCommand {
        override fun toText(): String = "resolve-war"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.resolveWar(state, actor)
    }

    data class CedeWar(val provinces: Set<ProvinceId>) : CardCommand {
        init {
            require(provinces.isNotEmpty()) {
                "A surrender command must name at least one province."
            }
        }

        override fun toText(): String =
            "cede-war ${provinces.sortedBy(ProvinceId::value).joinToString(" ")}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.surrenderWar(state, actor, provinces)
    }

    data object ForgoMarker : CardCommand {
        override fun toText(): String = "forgo-marker"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardMarkerPlacements.choose(state, actor, source = null)
    }

    data class MoveMarker(val source: ProvinceId) : CardCommand {
        override fun toText(): String = "move-marker $source"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardMarkerPlacements.choose(state, actor, source)
    }

    data class PatronizeLeader(val leader: LeaderId) : CardCommand {
        override fun toText(): String = "patronize-leader ${leader.cardId}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            CardGame.patronize(state, actor, leader)
    }

    companion object {
        /** Null leaves commands owned by another phase/rules module to its parser. */
        fun parse(name: String, arguments: List<String>): CardCommand? =
            try {
                when (name) {
                    "play-card" -> {
                        checkRule(
                            arguments.size == 1 || arguments.size == 3,
                            ViolationCode.INVALID_COMMAND,
                        )
                        val choice =
                            if (arguments.size == 1) CardChoice.None
                            else
                                when (arguments[1]) {
                                    "commodity" ->
                                        CardChoice.CommodityChoice(
                                            Commodity.valueOf(arguments[2].uppercase())
                                        )
                                    "player" -> CardChoice.PlayerChoice(PlayerId(arguments[2]))
                                    "province" ->
                                        CardChoice.ProvinceChoice(ProvinceId(arguments[2]))
                                    "category" ->
                                        CardChoice.CategoryChoice(
                                            if (arguments[2] == "none") null
                                            else AdvanceCategory.valueOf(arguments[2].uppercase())
                                        )
                                    "protect" -> CardChoice.LeaderProtection(arguments[2].toInt())
                                    else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                                }
                        PlayCard(arguments[0], choice)
                    }
                    "civil-war-loss" -> {
                        size(arguments, 1)
                        ChooseCivilWarLoss(
                            when (arguments.single()) {
                                "cash" -> CivilWarLossChoice.CASH
                                "tokens" -> CivilWarLossChoice.EXPANSION_TOKENS
                                else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                            }
                        )
                    }
                    "replace-event-token" -> {
                        size(arguments, 1)
                        ReplaceEventToken(
                            when (arguments.single()) {
                                "pool" -> true
                                "none" -> false
                                else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                            }
                        )
                    }
                    "resolve-war" -> {
                        size(arguments, 0)
                        ResolveWar
                    }
                    "cede-war" -> {
                        checkRule(
                            arguments.isNotEmpty() && arguments.distinct().size == arguments.size,
                            ViolationCode.INVALID_COMMAND,
                        )
                        CedeWar(arguments.map(::ProvinceId).toSet())
                    }
                    "forgo-marker" -> {
                        size(arguments, 0)
                        ForgoMarker
                    }
                    "move-marker" -> {
                        size(arguments, 1)
                        MoveMarker(ProvinceId(arguments.single()))
                    }
                    "patronize-leader" -> {
                        size(arguments, 1)
                        PatronizeLeader(
                            LeaderId.entries.singleOrNull { leader ->
                                leader.cardId == arguments.single()
                            } ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)
                        )
                    }
                    else -> null
                }
            } catch (_: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }

        private fun size(arguments: List<String>, expected: Int) {
            checkRule(arguments.size == expected, ViolationCode.INVALID_COMMAND)
        }
    }
}
