package dev.aragos.aor

internal fun GameState.nextHandReference(): String =
    "hand-${CardData.epochOne.size - deck.size + 1}"

internal fun GameState.requireActor(actor: PlayerId, expected: Stage) {
    checkRule(stage == expected && flow.decisions.isEmpty(), ViolationCode.WRONG_PHASE)
    checkRule(flow.actor == actor, ViolationCode.WRONG_PLAYER)
    playerState(actor).requireActive()
}

internal fun GameState.pushDecision(decision: TurnDecision): GameState =
    copy(flow = flow.copy(decisions = flow.decisions + decision))

internal fun GameState.popDecision(): GameState {
    check(flow.decisions.isNotEmpty()) { "Popping a decision requires a pending frame." }
    return copy(flow = flow.copy(decisions = flow.decisions.dropLast(1)))
}

/**
 * Queue an earned draw in request order, preserving any outer decision below consecutive draws.
 * Keep every beneficiary until resolution: an earlier beneficiary may enter Chaos before drawing.
 * Consuming the last card removes any remaining draw requests and triggers the ending.
 */
internal fun GameState.queueDraw(player: PlayerId): GameState {
    if (playerState(player).misery == MiseryStep.CHAOS) return this
    if (deck.isEmpty()) return copy(endingTriggered = true)
    val insertion = flow.decisions.indexOfLast { decision -> decision !is DrawDecision } + 1
    val decisions =
        flow.decisions.take(insertion) + DrawDecision(player) + flow.decisions.drop(insertion)
    return copy(flow = flow.copy(decisions = decisions))
}

/** Player-local economy results enter Chaos exactly once here; board pieces remain neutral. */
internal fun GameState.replacePlayer(player: PlayerState): GameState {
    val id = player.player.id
    val chaos = player.misery == MiseryStep.CHAOS
    val cleaned =
        if (chaos) {
            player.copy(
                hand = emptyList(),
                stockTokens = player.stockTokens + player.poolTokens,
                poolTokens = 0,
                tokenPurchase = player.tokenPurchase ?: 0,
            )
        } else {
            player
        }
    return copy(
        activeWar =
            if (chaos && (activeWar?.initiator == id || activeWar?.opponent == id)) {
                null
            } else {
                activeWar
            },
        players = players + (id to cleaned),
        flow =
            flow.copy(
                decisions =
                    flow.decisions.filterNot { frame ->
                        chaos &&
                            (frame.player == id || (frame is DrawDecision && frame.owner == id))
                    }
            ),
    )
}

/**
 * Stock-funded card changes; expansion spends the pool and clears tokensRequired before calling.
 */
internal fun GameState.withBoardChange(change: BoardChange): GameState {
    val updated = playerIds.associateWith { id ->
        val owner = playerState(id)
        val stock =
            owner.stockTokens + change.tokensReturned.getOrElse(id) { 0 } -
                change.tokensRequired.getOrElse(id) { 0 }
        checkRule(stock >= 0, ViolationCode.INVALID_BOARD_MUTATION)
        owner.copy(stockTokens = stock)
    }
    return copy(board = change.board, players = updated)
}

internal fun GameState.normalise(): GameState = TurnEngine.normalise(this)

internal fun GameState.finishActor(): GameState = TurnEngine.finishActor(this)
