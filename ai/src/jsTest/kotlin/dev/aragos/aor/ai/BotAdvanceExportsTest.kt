package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotAdvanceExportsTest {
    @Test
    fun exportedPlannerBuysResearchBeforeTheAdvancesItDiscounts() {
        val selected = chooseBotMove(observation(), model(weight = 1.0), "hard", 0)

        assertEquals("buy-advance X", selected)
    }

    @Test
    fun zeroResearchWeightKeepsTheOriginalOrder() {
        val selected = chooseBotMove(observation(), model(weight = 0.0), "hard", 0)

        assertEquals("buy-advance T", selected)
    }

    @Test
    fun missingAdvanceMetadataKeepsTheOriginalOrder() {
        val selected = chooseBotMove(observation(planning = null), model(weight = 1.0), "hard", 0)

        assertEquals("buy-advance T", selected)
    }

    @Test
    fun disabledTokenHeadCannotChangeResearchRepricingAcrossJson() {
        val observation =
            """{
            "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
            "context":{"stabilization":0},"availableCash":120,"availableTokens":0,
            "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
            "advancePlanning":{"steps":{
                "buy-advance T":{"advance":"OCEAN_NAVIGATION","institutionalResearchDiscount":10},
                "buy-advance X":{"advance":"INSTITUTIONAL_RESEARCH","institutionalResearchDiscount":0}
            }},
            "candidates":[
                {"command":"buy-advance T","features":{"cash":-80,"points":81,"planning.tokenValue.v1.cost.cash":1},"cashCost":80,"tokenCost":0,"group":"t","family":"advance"},
                {"command":"buy-advance X","features":{"cash":-40,"points":40},"cashCost":40,"tokenCost":0,"group":"x","family":"advance"},
                {"command":"pass","features":{},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
            ]
        }"""
        val model =
            """{
            "schemaVersion":1,"weights":{
                "cash":1,"points":1,"planning.advanceResearchSaving":1,
                "planning.tokenValue.enabled":0,"planning.tokenValue.v1.cost.cash":2000
            }
        }"""

        val selected = chooseBotMove(observation, model, "hard", 0)

        assertEquals("buy-advance X", selected)
    }

    private fun model(weight: Double): String =
        """{
        "schemaVersion":1,"weights":{
            "cash":1,"advance.T":106.9,"advance.X":50.4,"advance.W":9.7,"advance.O":47.6,
            "planning.advanceResearchSaving":$weight
        }
    }"""

    private fun observation(planning: String? = planning()): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":0},"availableCash":185,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        ${planning?.let { "\"advancePlanning\":$it," }.orEmpty()}
        "candidates":[
            {"command":"buy-advance T","features":{"cash":-80,"advance.T":1},"cashCost":80,"tokenCost":0,"group":"t","family":"advance"},
            {"command":"buy-advance X","features":{"cash":-40,"advance.X":1},"cashCost":40,"tokenCost":0,"group":"x","family":"advance"},
            {"command":"buy-advance W","features":{"cash":0,"advance.W":1},"cashCost":0,"tokenCost":0,"group":"w","family":"advance"},
            {"command":"buy-advance O","features":{"cash":-50,"advance.O":1},"cashCost":50,"tokenCost":0,"group":"o","family":"advance"}
        ]
    }"""

    private fun planning(): String =
        """{
        "steps":{
            "buy-advance T":{"advance":"OCEAN_NAVIGATION","institutionalResearchDiscount":10},
            "buy-advance X":{"advance":"INSTITUTIONAL_RESEARCH","institutionalResearchDiscount":0},
            "buy-advance W":{"advance":"NATIONALISM","institutionalResearchDiscount":0},
            "buy-advance O":{"advance":"PRINTED_WORD","institutionalResearchDiscount":10}
        }
    }"""
}
