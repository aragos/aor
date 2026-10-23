package dev.aragos.aor

/**
 * Ordered spaces on the misery track, rather than arbitrary misery point values.
 *
 * [value] is the printed box value used for costs and scoring. [CHAOS] lies beyond the last box,
 * eliminates its player, and has no numeric value. Moving spaces and paying values are distinct.
 */
enum class MiseryStep(val value: Int?) {
    ZERO(0),
    TEN(10),
    TWENTY(20),
    THIRTY(30),
    FORTY(40),
    FIFTY(50),
    SIXTY(60),
    SEVENTY(70),
    EIGHTY(80),
    NINETY(90),
    HUNDRED(100),
    HUNDRED_TWENTY_FIVE(125),
    HUNDRED_FIFTY(150),
    HUNDRED_SEVENTY_FIVE(175),
    TWO_HUNDRED(200),
    TWO_HUNDRED_FIFTY(250),
    THREE_HUNDRED(300),
    THREE_HUNDRED_FIFTY(350),
    FOUR_HUNDRED(400),
    FOUR_HUNDRED_FIFTY(450),
    FIVE_HUNDRED(500),
    SIX_HUNDRED(600),
    SEVEN_HUNDRED(700),
    EIGHT_HUNDRED(800),
    NINE_HUNDRED(900),
    THOUSAND(1000),
    CHAOS(null),
}
