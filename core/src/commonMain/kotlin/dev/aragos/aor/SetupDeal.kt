package dev.aragos.aor

/** Deterministic opening distribution; every random permutation is supplied by the authority. */
internal object SetupDeal {
    fun initialCards(state: GameState): List<String> = state.rules.cardsForEpoch(Epoch.ONE)

    /**
     * Validate supply before setup; sequential returns leave one candidate beyond all kept hands.
     */
    fun validate(state: GameState) {
        if (!state.rules.drawThreeKeepTwo) return
        val eligible =
            initialCards(state).count { state.rules.initialCard.accepts(CardData.definition(it)) }
        checkRule(
            eligible >= state.playerIds.size * 2 + 1,
            ViolationCode.INSUFFICIENT_INITIAL_CARDS,
        )
    }

    /** Setup allocates consecutive draw occurrences, including each subsequently returned card. */
    fun nextPlayer(state: GameState): PlayerId? {
        val cardsPerPlayer = if (state.rules.drawThreeKeepTwo) 3 else 1
        return state.playerIds.getOrNull((state.deckHistory.nextDraw - 1) / cardsPerPlayer)
    }

    fun choosingPlayer(state: GameState): PlayerId =
        state.playerIds.first { state.playerState(it).hand.size == 3 }

    /** Filtering only the opening deal leaves excluded physical cards in the draw pile. */
    fun nextCard(state: GameState): String? =
        if (state.stage == Stage.INITIAL_DRAW) {
            state.deck.firstOrNull { id ->
                state.rules.initialCard.accepts(CardData.definition(id))
            }
        } else {
            state.deck.firstOrNull()
        }

    fun afterInitialDraw(state: GameState): GameState {
        if (state.rules.drawThreeKeepTwo) {
            return if (state.players.values.any { it.hand.size == 3 }) {
                state.copy(stage = Stage.SETUP_RETURN)
            } else {
                state
            }
        }
        return if (nextPlayer(state) == null) state.copy(stage = Stage.BIDDING) else state
    }

    fun returnCard(state: GameState, actor: PlayerId, reference: String): GameState {
        checkRule(state.stage == Stage.SETUP_RETURN, ViolationCode.WRONG_PHASE)
        checkRule(actor == choosingPlayer(state), ViolationCode.WRONG_PLAYER)
        val hand = state.playerState(actor).hand
        val card =
            hand.singleOrNull { it.reference == reference }
                ?: throw RuleViolation(ViolationCode.INVALID_CARD_RETURN)
        val cards = state.deck + card.definition.id
        return state
            .updatePlayer(actor) { owner ->
                owner.copy(hand = hand.filterNot { it.reference == reference })
            }
            .copy(
                stage = Stage.SHUFFLE,
                deck = emptyList(),
                deckHistory =
                    state.deckHistory.copy(
                        pendingShuffle = ShuffleRequest(cards, ShufflePurpose.SETUP_RETURN)
                    ),
            )
    }
}
