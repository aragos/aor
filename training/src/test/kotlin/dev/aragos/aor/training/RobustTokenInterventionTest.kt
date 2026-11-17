package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class RobustTokenInterventionTest {
    private val commands = (0..36).map { "buy-tokens $it" }

    @Test
    fun undercutTracksTheActualStateDependentQualifiedBid() {
        assertThat(
                RobustTokenIntervention.command(
                    "buy-tokens 21",
                    commands,
                    RobustOpponentStyle.UNDERCUT,
                    turn = 2,
                    stock = 36,
                )
            )
            .isEqualTo("buy-tokens 20")
    }

    @Test
    fun overbidDoesNotWasteMoneyBeyondAvailableStock() {
        assertThat(
                RobustTokenIntervention.command(
                    "buy-tokens 12",
                    commands,
                    RobustOpponentStyle.OVERBID,
                    turn = 3,
                    stock = 12,
                )
            )
            .isEqualTo("buy-tokens 12")
    }

    @Test
    fun interventionUsesOnlyActuallyOfferedAmounts() {
        assertThat(
                RobustTokenIntervention.command(
                    "buy-tokens 30",
                    listOf("buy-tokens 30", "buy-tokens 36"),
                    RobustOpponentStyle.UNDERCUT,
                    turn = 3,
                    stock = 36,
                )
            )
            .isEqualTo("buy-tokens 30")
    }

    @Test
    fun everySeatEncountersEveryBalancedStyle() {
        for (seat in 0..3) {
            val styles =
                (0..3).map { group -> RobustOpponentStyle.BALANCED.at(group * 4 + seat, seat) }
            assertThat(styles)
                .containsExactly(
                    RobustOpponentStyle.QUALIFIED,
                    RobustOpponentStyle.UNDERCUT,
                    RobustOpponentStyle.OVERBID,
                    RobustOpponentStyle.MIXED,
                )
        }
    }
}
