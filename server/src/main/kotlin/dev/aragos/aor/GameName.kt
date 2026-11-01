package dev.aragos.aor

import com.fasterxml.jackson.annotation.JsonValue

/** Creator-chosen, single-line display name; independent of the game's stable storage ID. */
data class GameName(@get:JsonValue val value: String) {
    init {
        require(value == value.trim() && value.length in 1..80 && value.none(Char::isISOControl)) {
            "A game name requires 1–80 printable characters without surrounding whitespace."
        }
    }

    companion object {
        val DEFAULT = GameName("Age of Renaissance")

        fun parse(value: String): GameName = GameName(value.trim())
    }
}
