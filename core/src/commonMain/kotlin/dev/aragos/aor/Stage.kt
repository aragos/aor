package dev.aragos.aor

enum class Stage {
    CONFIGURATION,
    SHUFFLE,
    DEAL,
    BIDDING,
    CAPITAL_TIE,
    CAPITAL_CHOICE,
    SETUP_COMPLETE;

    fun prompt(state: GameState): Prompt? =
        when (this) {
            CONFIGURATION ->
                Prompt.SinglePlayer(
                    player = state.header.creatorId,
                    choice = "configure",
                )
            SHUFFLE -> Prompt.System(SystemNeed.Shuffle)
            DEAL -> {
                val player =
                    state.playerIds.first { playerId ->
                        state.playerState(playerId).hand.isEmpty()
                    }
                Prompt.System(
                    SystemNeed.Deal(
                        player = player,
                        reference = "hand-${state.playerIds.indexOf(player) + 1}",
                    )
                )
            }
            BIDDING ->
                Prompt.MultiPlayer(
                    players =
                        state.playerIds.filter { playerId ->
                            state.playerState(playerId).capitalBid == null
                        },
                    choice = "bid-capital",
                )
            CAPITAL_TIE ->
                Prompt.System(SystemNeed.CapitalTie(state.capitalSelection.ties.first().players))
            CAPITAL_CHOICE ->
                Prompt.SinglePlayer(
                    player =
                        state.capitalOrder.first { playerId ->
                            state.playerState(playerId).capital == null
                        },
                    choice = "choose-capital",
                )
            SETUP_COMPLETE -> null
        }

    fun legalChoices(state: GameState, player: PlayerId): LegalChoices =
        when (this) {
            CONFIGURATION ->
                if (player == state.header.creatorId) {
                    LegalChoices(
                        commands =
                            (1..3).map { epochs -> Command.SetEpochs(epochs).toText() } + "start"
                    )
                } else {
                    LegalChoices()
                }
            BIDDING ->
                if (state.playerState(player).capitalBid == null) {
                    LegalChoices(commands = listOf("bid-capital"), bidMin = 0, bidMax = 40)
                } else {
                    LegalChoices()
                }
            CAPITAL_CHOICE ->
                if ((prompt(state) as Prompt.SinglePlayer).player == player) {
                    LegalChoices(
                        commands =
                            state.availableCapitals.map { capital ->
                                Command.ChooseCapital(capital).toText()
                            }
                    )
                } else {
                    LegalChoices()
                }
            else -> LegalChoices()
        }
}
