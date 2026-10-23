package dev.aragos.aor

sealed interface SystemNeed {
    data class Draw(val player: PlayerId, val reference: String) : SystemNeed

    data class GameRoll(val kind: GameRollKind, val diceCount: Int) : SystemNeed

    data class Trade(val owner: PlayerId, val recipient: PlayerId) : SystemNeed

    data object Shuffle : SystemNeed

    data class Deal(val player: PlayerId, val reference: String) : SystemNeed

    data class CapitalTie(val players: List<PlayerId>) : SystemNeed
}

sealed interface Prompt {
    data class SinglePlayer(val player: PlayerId, val choice: String) : Prompt

    data class MultiPlayer(val players: List<PlayerId>, val choice: String) : Prompt

    data class System(val need: SystemNeed) : Prompt
}
