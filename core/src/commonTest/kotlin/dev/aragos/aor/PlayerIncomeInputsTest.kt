package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerIncomeInputsTest {
    @Test
    fun publicQuoteRetainsTheCashBeforeIncomeCapAndMiddleClassBonus() {
        val quote =
            PlayerEconomy.incomeQuote(
                PlayerIncomeInputs(
                    cash = 8,
                    middleClass = true,
                    interestProfit = true,
                    newWorld = true,
                ),
                initialPlayerCount = 5,
                boardMarkerCount = 1,
            )

        assertEquals(IncomeQuote(20, 10, 8, 1), quote)
    }

    @Test
    fun publicAndPlayerQuotesAgreeAcrossIncomeTablesAndCapabilities() {
        for (count in 3..6) {
            for (markers in 0..25) {
                for (owned in
                    listOf(
                        emptySet(),
                        setOf(AdvanceId.MIDDLE_CLASS),
                        setOf(AdvanceId.INTEREST_PROFIT, AdvanceId.NEW_WORLD),
                        setOf(
                            AdvanceId.MIDDLE_CLASS,
                            AdvanceId.INTEREST_PROFIT,
                            AdvanceId.NEW_WORLD,
                        ),
                    )) {
                    val player =
                        PlayerState(
                            player = Player(PlayerId("p1"), "First"),
                            cash = 23,
                            advances = PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) }),
                        )
                    val inputs =
                        PlayerIncomeInputs(
                            23,
                            AdvanceId.MIDDLE_CLASS in owned,
                            AdvanceId.INTEREST_PROFIT in owned,
                            AdvanceId.NEW_WORLD in owned,
                        )

                    assertEquals(
                        PlayerEconomy.incomeQuote(player, count, markers),
                        PlayerEconomy.incomeQuote(inputs, count, markers),
                    )
                }
            }
        }
    }

    @Test
    fun publicIncomeInputsRejectNegativeCash() {
        assertFailsWith<IllegalArgumentException> {
            PlayerIncomeInputs(-1, false, false, false)
        }
    }
}
