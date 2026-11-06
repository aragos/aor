package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotReserveExportsTest {
    @Test
    fun exportedPlannerUsesTheCompleteContextualReserveCoefficient() {
        val selected =
            chooseBotMove(
                observation(basis = """{"reserveShortfall":1,"count.4.reserveShortfall":1}"""),
                model(),
                "hard",
                0,
            )

        assertEquals("pass", selected)
    }

    @Test
    fun olderObservationsRetainTheirGlobalReserveFallback() {
        val selected = chooseBotMove(observation(basis = null), model(), "hard", 0)

        assertEquals("buy-advance A", selected)
    }

    private fun model(): String =
        """{"schemaVersion":1,"weights":{"reserveShortfall":-1,"count.4.reserveShortfall":-4}}"""

    private fun observation(basis: String?): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":5},"availableCash":10,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        ${basis?.let { "\"reserveShortfallBasis\":$it," }.orEmpty()}
        "candidates":[
            {"command":"buy-advance A","features":{"points":10,"reserveShortfall":1,"count.4.reserveShortfall":1},"cashCost":6,"tokenCost":0,"group":"a","family":"advance"},
            {"command":"buy-advance B","features":{"points":0,"reserveShortfall":5,"count.4.reserveShortfall":5},"cashCost":10,"tokenCost":0,"group":"b","family":"advance"},
            {"command":"pass","features":{"points":6},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
        ]
    }"""
}
