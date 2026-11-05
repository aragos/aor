package dev.aragos.aor

/** Why the authority must permute the supplied physical cards. */
enum class ShufflePurpose {
    INITIAL_DECK,
    NEXT_EPOCH,
}

/** A suspended operation resumes only after an exact permutation has been recorded. */
data class ShuffleRequest(
    val cards: List<String>,
    val purpose: ShufflePurpose,
)

/**
 * Physical-card locations outside player hands and the ordered draw pile.
 *
 * [nextDraw] allocates occurrence references independently of physical identities and deck size.
 * [movedToFirstDeck] excludes the additional Timber from its printed epoch's later deck.
 */
data class DeckHistory(
    val discarded: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val movedToFirstDeck: Set<String> = emptySet(),
    val nextDraw: Int = 1,
    val pendingShuffle: ShuffleRequest? = null,
) {
    init {
        require(nextDraw > 0) { "The next draw occurrence must be positive." }
    }
}

/** A physical discard is distinct from a transfer or a setup return. */
internal fun GameState.discardCards(cards: List<HandCard>): GameState =
    copy(
        deckHistory =
            deckHistory.copy(
                discarded =
                    deckHistory.discarded +
                        cards
                            .filter { card -> card.definition.shape.recycledOnEpochChange }
                            .map { card -> card.definition.id },
                removed =
                    deckHistory.removed +
                        cards
                            .filterNot { card -> card.definition.shape.recycledOnEpochChange }
                            .map { card -> card.definition.id },
            )
    )

/** Exact eligible cards remain authoritative hidden state, never a public game-view field. */
internal fun GameState.pendingShuffle(): ShuffleRequest? {
    deckHistory.pendingShuffle?.let {
        return it
    }
    if (stage == Stage.SHUFFLE) {
        return ShuffleRequest(SetupDeal.initialCards(this), ShufflePurpose.INITIAL_DECK)
    }
    val awaitingDraw = flow.decisions.lastOrNull() is DrawDecision
    val next = currentEpoch.next
    if (deck.isEmpty() && awaitingDraw && next != null) {
        val printed = rules.cardsForEpoch(next).filterNot(deckHistory.movedToFirstDeck::contains)
        return ShuffleRequest(printed + deckHistory.discarded, ShufflePurpose.NEXT_EPOCH)
    }
    return null
}

internal fun GameState.applyShuffle(cards: List<String>): GameState {
    val request = pendingShuffle() ?: throw RuleViolation(ViolationCode.WRONG_PHASE)
    checkRule(cards.sorted() == request.cards.sorted(), ViolationCode.INVALID_OUTCOME)
    val history = deckHistory.copy(pendingShuffle = null)
    return when (request.purpose) {
        ShufflePurpose.INITIAL_DECK ->
            copy(stage = Stage.INITIAL_DRAW, deck = cards.toList(), deckHistory = history)
        ShufflePurpose.NEXT_EPOCH ->
            copy(
                currentEpoch =
                    requireNotNull(currentEpoch.next) {
                        "A next-epoch shuffle requires an unplayed epoch."
                    },
                deck = cards.toList(),
                deckHistory = history.copy(discarded = emptyList()),
                playedCards =
                    playedCards
                        .filterNot { CardData.definition(it).shape.recycledOnEpochChange }
                        .toSet(),
            )
    }
}

/** Earlier empty piles still offer draws: the request suspends for the next epoch's shuffle. */
internal fun GameState.canDrawCards(): Boolean = deck.isNotEmpty() || currentEpoch != Epoch.THREE
