package dev.aragos.aor

/** One six-sided die outcome, represented in replay text as an integer from 1 through 6. */
data class DieRoll(val value: Int) : Comparable<DieRoll> {
    init {
        require(value in 1..6) { "A six-sided die roll must be between 1 and 6." }
    }

    override fun compareTo(other: DieRoll): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /** Parse replay text, distinguishing malformed syntax from an impossible die outcome. */
        fun parse(text: String): DieRoll {
            val value = text.toIntOrNull() ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)
            return try {
                DieRoll(value)
            } catch (_: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_OUTCOME)
            }
        }
    }
}
