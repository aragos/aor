package dev.aragos.aor

/** An optional acquisition discard resumes the same advances-purchase activity. */
internal data class ImmediateMasterArtDecision(override val player: PlayerId) : TurnDecision {
    override val choice: String = "immediate-master-art-discard"

    override fun choices(state: GameState): List<String> =
        listOf("pass") +
            state.playerState(player).hand.map { card -> "discard-card ${card.reference}" }
}
