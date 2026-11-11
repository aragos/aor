package dev.aragos.aor.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenOrderReferenceTest {
    @Test
    fun fixedReferenceUsesOnlyTheExactOfferedAdjustment() {
        val command =
            TokenOrderReference.FIXED_16_TO_15.command(
                "buy-tokens 16",
                listOf("buy-tokens 16", "buy-tokens 15"),
            )

        assertEquals("buy-tokens 15", command)
    }

    @Test
    fun absentFifteenRetainsTheExactOrdinaryChoice() {
        val command =
            TokenOrderReference.FIXED_16_TO_15.command(
                "buy-tokens 16",
                listOf("buy-tokens 16", "buy-tokens 14"),
            )

        assertEquals("buy-tokens 16", command)
    }

    @Test
    fun ordinaryReferenceRetainsSixteen() {
        val command =
            TokenOrderReference.ORDINARY.command(
                "buy-tokens 16",
                listOf("buy-tokens 16", "buy-tokens 15"),
            )

        assertEquals("buy-tokens 16", command)
    }

    @Test
    fun referenceRejectsAnUnofferedOrdinaryChoice() {
        assertFailsWith<IllegalArgumentException> {
            TokenOrderReference.FIXED_16_TO_15.command("buy-tokens 16", listOf("buy-tokens 15"))
        }
    }
}
