package dev.aragos.aor

/** Stable player identity in logs and maps; permitted values are `p1` through `p6`. */
data class PlayerId(val value: String) : Comparable<PlayerId> {
    init {
        require(value.matches(Regex("p[1-6]"))) { "Player id must be p1 through p6." }
    }

    override fun compareTo(other: PlayerId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}
