package dev.aragos.aor

enum class Stage {
    CONFIGURATION,
    SHUFFLE,
    DEAL,
    BIDDING,
    CAPITAL_TIE,
    CAPITAL_CHOICE,
    TOKEN_PURCHASE,
    TURN_ORDER_TIE,
    DRAW_CARD,
    BUY_CARD,
    PLAY_CARDS,
    PURCHASE,
    EXPANSION,
    INCOME,
    FINAL_CARD_PLAY,
    FINISHED;

    val isTurnPhase: Boolean
        get() = ordinal >= DRAW_CARD.ordinal

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
            TOKEN_PURCHASE ->
                Prompt.MultiPlayer(
                    players =
                        state.playerIds.filter { playerId ->
                            state.playerState(playerId).tokenPurchase == null
                        },
                    choice = "buy-tokens",
                )
            TURN_ORDER_TIE ->
                Prompt.SinglePlayer(
                    player = state.turnOrderSelection.ties.first().choosingPlayer,
                    choice = "choose-turn-position",
                )
            DRAW_CARD,
            BUY_CARD,
            PLAY_CARDS,
            PURCHASE,
            EXPANSION,
            INCOME,
            FINAL_CARD_PLAY,
            FINISHED -> null
        }

    fun legalChoices(state: GameState, player: PlayerId): LegalChoices =
        when (this) {
            CONFIGURATION ->
                if (player == state.header.creatorId) {
                    LegalChoices(
                        commands =
                            listOf(Command.SetEpochs(1).toText()) +
                                RebuiltMarkerBonus.entries.map { policy ->
                                    Command.SetRebuiltMarkerBonus(policy).toText()
                                } +
                                "start"
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
            TOKEN_PURCHASE ->
                if (state.playerState(player).tokenPurchase == null) {
                    val cash = state.playerState(player).cash
                    LegalChoices(commands = listOf("buy-tokens"), bidMin = -cash, bidMax = cash)
                } else {
                    LegalChoices()
                }
            TURN_ORDER_TIE -> {
                val tie = state.turnOrderSelection.ties.first()
                if (tie.choosingPlayer == player) {
                    LegalChoices(
                        commands =
                            (1..tie.placed.size + 1).map { slot ->
                                Command.ChooseTurnPosition(slot).toText()
                            }
                    )
                } else {
                    LegalChoices()
                }
            }
            else -> LegalChoices()
        }
}
