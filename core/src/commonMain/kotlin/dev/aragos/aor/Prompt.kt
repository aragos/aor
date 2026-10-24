package dev.aragos.aor

sealed interface SystemNeed {
    data class Draw(val player: PlayerId, val reference: String, val card: String) : SystemNeed

    /** [players] attributes one die per participant, in the requested order, when nonempty. */
    data class GameRoll(
        val kind: GameRollKind,
        val diceCount: Int,
        val players: List<PlayerId> = emptyList(),
    ) : SystemNeed

    data class Trade(val owner: PlayerId, val recipient: PlayerId) : SystemNeed

    data class Shuffle(val cards: List<String>, val purpose: ShufflePurpose) : SystemNeed
}

sealed interface Prompt {
    data class SinglePlayer(val player: PlayerId, val choice: String) : Prompt

    data class MultiPlayer(val players: List<PlayerId>, val choice: String) : Prompt

    data class System(val need: SystemNeed) : Prompt
}
