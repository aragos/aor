package dev.aragos.aor

enum class Stage {
    CONFIGURATION,
    SHUFFLE,
    /** Starting-card distribution before capital bidding, using [SystemNeed.Draw]. */
    INITIAL_DRAW,
    /** Private choice of one opening card to shuffle back before capital bidding. */
    SETUP_RETURN,
    BIDDING,
    CAPITAL_TIE,
    CAPITAL_CHOICE,
    TOKEN_PURCHASE,
    TURN_ORDER_TIE,
    /** Recurring Draw Card phase, including first-player shortage/surplus cancellation. */
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
            SHUFFLE -> null
            SETUP_RETURN -> Prompt.SinglePlayer(SetupDeal.choosingPlayer(state), "return-card")
            BIDDING ->
                Prompt.MultiPlayer(
                    players =
                        state.playerIds.filter { playerId ->
                            state.playerState(playerId).capitalBid == null
                        },
                    choice = "bid-capital",
                )
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
                            state.playerState(playerId).tokenPurchase == null &&
                                state.playerState(playerId).misery != MiseryStep.CHAOS
                        },
                    choice = "buy-tokens",
                )
            TURN_ORDER_TIE ->
                Prompt.SinglePlayer(
                    player = state.turnOrderSelection.ties.first().choosingPlayer,
                    choice = "choose-turn-position",
                )
            INITIAL_DRAW,
            CAPITAL_TIE,
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
                            RuleOptionView.from(state.rules).flatMap { option ->
                                option.choices.map { value ->
                                    RuleOptionCommand(option.key, value).toText()
                                }
                            } + "start"
                    )
                } else {
                    LegalChoices()
                }
            SETUP_RETURN ->
                if (SetupDeal.choosingPlayer(state) == player) {
                    LegalChoices(
                        commands =
                            state.playerState(player).hand.map { card ->
                                Command.ReturnCard(card.reference).toText()
                            }
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
                if (
                    state.playerState(player).tokenPurchase == null &&
                        state.playerState(player).misery != MiseryStep.CHAOS
                ) {
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
