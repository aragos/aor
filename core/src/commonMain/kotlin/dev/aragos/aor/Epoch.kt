package dev.aragos.aor

/** The three printed history epochs. Every game uses the complete sequence. */
enum class Epoch(val number: Int) {
    ONE(1),
    TWO(2),
    THREE(3);

    val next: Epoch?
        get() = entries.getOrNull(ordinal + 1)
}
