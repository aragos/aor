package dev.aragos.aor

/** Operational lifecycle is separate from the engine's game phase. */
enum class GameStatus {
    ACTIVE,
    CANCELLED,
    ABANDONED,
}

enum class AdministrativeAction {
    CANCEL,
    ABANDON,
    UPGRADE,
    PURGE,
    ALLOW_CREATION,
    DENY_CREATION,
}

internal sealed interface GameAccess {
    data class SeatToken(val token: String) : GameAccess

    data class Account(val id: AccountId) : GameAccess
}

data class GameRead(val etag: String, val snapshot: GameSnapshot?)
