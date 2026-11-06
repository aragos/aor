package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotInterestExportsTest {
    @Test
    fun exportedPlannerPricesTheCombinedLossOfAnOwnedInterestReceipt() {
        val selected = chooseBotMove(observation(), model(), "hard", 0)

        assertEquals("buy-advance C", selected)
    }

    @Test
    fun olderIncomeMetadataKeepsTheIndependentFeatureBehavior() {
        val selected = chooseBotMove(observation(income = ""), model(), "hard", 0)

        assertEquals("buy-advance A", selected)
    }

    @Test
    fun zeroInterestWeightPreservesTheChoiceAcrossMetadataVersions() {
        val current = chooseBotMove(observation(), model(weight = 0.0), "hard", 0)
        val legacy = chooseBotMove(observation(income = ""), model(weight = 0.0), "hard", 0)

        assertEquals(legacy, current)
    }

    @Test
    fun theOnlyAvailableAdvanceCanRaiseTheInterestCap() {
        val input =
            singleObservation(
                command = "buy-advance Z",
                advance = "MIDDLE_CLASS",
                cost = 0,
                points = -5,
                passPoints = 0,
                ownsInterest = true,
            )

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("buy-advance Z", selected)
    }

    @Test
    fun aNewInterestAdvanceDoesNotReceiveItsStandaloneBonusTwice() {
        val input =
            singleObservation(
                command = "buy-advance L",
                advance = "INTEREST_PROFIT",
                cost = 40,
                points = 10,
                passPoints = 20,
                ownsInterest = false,
            )

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("pass", selected)
    }

    private fun model(weight: Double = 1.0): String =
        """{"schemaVersion":1,"weights":{"planning.foregoneInterest":$weight}}"""

    private fun observation(
        income: String = "\"ownsInterestProfit\":true,\"ordinaryIncome\":60,"
    ): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":0},"availableCash":100,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "advancePlanning":{$income"steps":{
            "buy-advance A":{"advance":"THE_HEAVENS","institutionalResearchDiscount":0},
            "buy-advance B":{"advance":"HUMAN_BODY","institutionalResearchDiscount":0},
            "buy-advance C":{"advance":"LAWS_OF_MATTER","institutionalResearchDiscount":0}
        }},
        "candidates":[
            {"command":"buy-advance A","features":{"points":30},"cashCost":40,"tokenCost":0,"group":"a","family":"advance"},
            {"command":"buy-advance B","features":{"points":29},"cashCost":40,"tokenCost":0,"group":"b","family":"advance"},
            {"command":"buy-advance C","features":{"points":65,"planning.foregoneInterest":-30},"cashCost":70,"tokenCost":0,"group":"c","family":"advance"}
        ]
    }"""

    private fun singleObservation(
        command: String,
        advance: String,
        cost: Int,
        points: Int,
        passPoints: Int,
        ownsInterest: Boolean,
    ): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":0},"availableCash":100,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "advancePlanning":{"ownsInterestProfit":$ownsInterest,"ordinaryIncome":60,"steps":{
            "$command":{"advance":"$advance","institutionalResearchDiscount":0}
        }},
        "candidates":[
            {"command":"$command","features":{"points":$points},"cashCost":$cost,"tokenCost":0,"group":"advance","family":"advance"},
            {"command":"pass","features":{"points":$passPoints},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
        ]
    }"""
}
