package dev.aragos.aor

/** Deterministic setup distribution and the later return of reserved physical cards. */
internal object SetupDeal {
    private const val additionalTimber = "timber-2-1"
    private val reserved =
        listOf("the-crusades", "walter-the-penniless", "rashid-ad-din", "silk", "spice")

    fun prepare(state: GameState): GameState {
        if (state.rules.setup != SetupRule.IMPROVED) return state
        // The approved choose-two setup cannot use a reduced initial-deal deck (2026-10-24).
        checkRule(
            state.rules.initialCard != InitialCardRule.COMMODITY_ONLY,
            ViolationCode.UNSUPPORTED_OPTION,
        )
        return state.copy(
            deckHistory =
                state.deckHistory.copy(
                    setupReserves = reserved,
                    movedToFirstDeck = setOf(additionalTimber),
                )
        )
    }

    fun initialCards(state: GameState): List<String> {
        val printed = CardData.forEpoch(Epoch.ONE).map(CardDefinition::id)
        if (state.rules.setup != SetupRule.IMPROVED) return printed
        return printed.filterNot(reserved::contains) + additionalTimber
    }

    fun cardsPerPlayer(state: GameState): Int =
        if (state.rules.setup == SetupRule.IMPROVED) 3 else 1

    /** The first commodity in a uniform permutation is a uniform eligible initial card. */
    fun nextCard(state: GameState): String? =
        if (
            state.stage == Stage.INITIAL_DRAW &&
                state.rules.initialCard == InitialCardRule.COMMODITY_ONLY
        ) {
            state.deck.firstOrNull { id -> CardData.definition(id).kind == CardKind.COMMODITY }
        } else {
            state.deck.firstOrNull()
        }

    fun afterInitialDraw(state: GameState): GameState {
        val complete =
            state.players.values.all { owner ->
                owner.hand.size == cardsPerPlayer(state)
            }
        if (!complete) return state
        return state.copy(
            stage =
                if (state.rules.setup == SetupRule.IMPROVED) {
                    Stage.SETUP_RETURN
                } else {
                    Stage.BIDDING
                }
        )
    }

    fun returnChoices(state: GameState, player: PlayerId): List<String> {
        if (player in state.deckHistory.setupReturns) return emptyList()
        return state.playerState(player).hand.map { card ->
            SetupCommand.ReturnCard(card.reference).toText()
        }
    }

    fun returnCard(state: GameState, actor: PlayerId, reference: String): GameState {
        checkRule(state.stage == Stage.SETUP_RETURN, ViolationCode.WRONG_PHASE)
        checkRule(actor !in state.deckHistory.setupReturns, ViolationCode.WRONG_PLAYER)
        val returned =
            state.playerState(actor).hand.singleOrNull { card -> card.reference == reference }
                ?: throw RuleViolation(ViolationCode.INVALID_SETUP_RETURN)
        val returns = state.deckHistory.setupReturns + (actor to returned)
        val selected =
            state.updatePlayer(actor) { owner ->
                owner.copy(hand = owner.hand.filterNot { card -> card.reference == reference })
            }
        val request =
            if (returns.size == state.playerIds.size) {
                ShuffleRequest(
                    state.deck +
                        state.playerIds.map { id ->
                            requireNotNull(returns[id]) {
                                    "Every player must select a setup return before shuffling."
                                }
                                .definition
                                .id
                        },
                    ShufflePurpose.SETUP_RETURNS,
                )
            } else {
                null
            }
        return selected.copy(
            deckHistory =
                selected.deckHistory.copy(
                    setupReturns = returns,
                    pendingShuffle = request,
                )
        )
    }

    /**
     * Approved Q14 rule: reinsert after turn one's regular draws (3–4 seats), or turn two's (5–6
     * seats). The user confirmed this project rule on 2026-10-24; other timings are future options.
     */
    fun reinsertionTurn(playerCount: Int): Int {
        require(playerCount in 3..6) { "Setup reinsertion requires three through six players." }
        return if (playerCount <= 4) 1 else 2
    }

    fun afterRegularDraw(before: GameState, resumed: GameState): GameState {
        val reserves = before.deckHistory.setupReserves
        if (reserves.isEmpty() || before.turnNumber != reinsertionTurn(before.playerIds.size)) {
            return resumed
        }
        return resumed.copy(
            deckHistory =
                resumed.deckHistory.copy(
                    pendingShuffle =
                        ShuffleRequest(resumed.deck + reserves, ShufflePurpose.SETUP_RESERVES)
                )
        )
    }
}

/** Private setup selections use their own command; returning a card is never a discard. */
sealed interface SetupCommand : Command {
    data class ReturnCard(val reference: String) : SetupCommand {
        override fun toText(): String = "return-card $reference"

        override fun applyTo(state: GameState, actor: PlayerId): GameState =
            SetupDeal.returnCard(state, actor, reference)
    }

    companion object {
        fun parse(name: String, arguments: List<String>): SetupCommand? {
            if (name != "return-card") return null
            checkRule(arguments.size == 1, ViolationCode.INVALID_COMMAND)
            return ReturnCard(arguments.single())
        }
    }
}
