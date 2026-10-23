package dev.aragos.aor

sealed interface SystemNeed {
    data object Shuffle : SystemNeed

    data class Deal(val player: PlayerId, val reference: String) : SystemNeed

    data class CapitalTie(val players: List<PlayerId>) : SystemNeed
}

sealed interface Prompt {
    data class SinglePlayer(val player: PlayerId, val choice: String) : Prompt

    data class MultiPlayer(val players: List<PlayerId>, val choice: String) : Prompt

    data class System(val need: SystemNeed) : Prompt
}
