package dev.aragos.aor

/** Recorded randomness belongs to logical requests, including participants and destinations. */
internal object OutcomeIdentity {
    /** Logical events include their participants/target; unrelated equal dice cannot substitute. */
    fun requestKey(
        state: GameState,
        occurrences: MutableMap<List<Any?>, Int>,
    ): List<Any?> {
        val need =
            (state.prompts().singleOrNull() as? Prompt.System)?.need
                ?: throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
        val normalized =
            if (need is SystemNeed.Shuffle) need.copy(cards = need.cards.sorted()) else need
        val frame =
            when (val decision = state.flow.decisions.lastOrNull()) {
                is BoardDecision.CompetitionDice ->
                    listOf(decision.plan.player, decision.plan.destination, decision.plan.defenders)
                is BoardDecision.WindmillDice ->
                    listOf(decision.winner, decision.victim, decision.marketNumber)
                is BoardDecision.RandomTrade ->
                    listOf(decision.owner, decision.recipient, decision.offeredReference)
                is CardDecision.WarRoll ->
                    listOf(
                        decision.war.initiator,
                        decision.war.opponent,
                        decision.war.startedTurn,
                        decision.war.ties,
                        decision.war.lastRound,
                    )
                else -> null
            }
        val base =
            listOf(
                state.turnNumber,
                state.currentEpoch,
                state.stage,
                state.flow.actor,
                state.flow.shortageRolls,
                normalized,
                frame,
            )
        val ordinal = occurrences[base] ?: 0
        occurrences[base] = ordinal + 1
        return base + ordinal
    }

    fun validate(header: GameHeader, original: List<String>, candidate: List<String>) {
        fun requests(entries: List<String>): List<Pair<List<Any?>, Entry>> {
            var state = GameState(header)
            val occurrences = mutableMapOf<List<Any?>, Int>()
            return entries.mapNotNull { text ->
                val entry = TextCodec.parse(text)
                val request =
                    if (entry is Entry.Action) null else requestKey(state, occurrences) to entry
                state = state.step(entry)
                request
            }
        }
        if (requests(original) != requests(candidate)) {
            throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
        }
    }
}
