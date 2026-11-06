package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotFinalAdvanceExportsTest {
    @Test
    fun exportedPlannerBuysAnAdvanceWithPositiveFinalScore() {
        val selected = chooseBotMove(masterArtObservation(), masterArtModel(1.0), "hard", 0)

        assertEquals("buy-advance P", selected)
    }

    @Test
    fun zeroFinalScoreWeightKeepsTheOriginalChoice() {
        val selected = chooseBotMove(masterArtObservation(), masterArtModel(0.0), "hard", 0)

        assertEquals("pass", selected)
    }

    @Test
    fun absentFinalScoreFeatureKeepsTheOriginalChoice() {
        val selected =
            chooseBotMove(masterArtObservation(finalScore = false), masterArtModel(1.0), "hard", 0)

        assertEquals("pass", selected)
    }

    @Test
    fun explicitZeroFinalScoreReceivesResearchSavingsAtItsOwnWeight() {
        val selected =
            chooseBotMove(researchObservation(finalScore = true), researchModel(), "hard", 0)

        assertEquals("buy-advance X", selected)
    }

    @Test
    fun absentFinalScoreDoesNotReceiveTheTerminalResearchCorrection() {
        val selected =
            chooseBotMove(researchObservation(finalScore = false), researchModel(), "hard", 0)

        assertEquals("buy-advance T", selected)
    }

    private fun masterArtModel(weight: Double): String =
        """{
        "schemaVersion":1,"weights":{
            "advance.P":-5.35,"planning.finalAdvanceScore":$weight
        }
    }"""

    private fun masterArtObservation(finalScore: Boolean = true): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":0},"availableCash":386,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[
            {"command":"buy-advance P","features":{"advance.P":1${if (finalScore) ",\"planning.finalAdvanceScore\":20" else ""}},"cashCost":70,"tokenCost":0,"group":"p","family":"advance"},
            {"command":"pass","features":{},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
        ]
    }"""

    private fun researchModel(): String =
        """{
        "schemaVersion":1,"weights":{
            "cash":1,"count.4.cash":1,"advance.T":410,"advance.X":80,
            "planning.advanceResearchSaving":0.5,"planning.finalAdvanceScore":1.5
        }
    }"""

    private fun researchObservation(finalScore: Boolean): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "context":{"stabilization":0},"availableCash":120,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "advancePlanning":{"steps":{
            "buy-advance T":{"advance":"OCEAN_NAVIGATION","institutionalResearchDiscount":10},
            "buy-advance X":{"advance":"INSTITUTIONAL_RESEARCH","institutionalResearchDiscount":0}
        }},
        "candidates":[
            {"command":"buy-advance T","features":{"cash":-80,"count.4.cash":-80,"advance.T":1${if (finalScore) ",\"planning.finalAdvanceScore\":0" else ""}},"cashCost":80,"tokenCost":0,"group":"t","family":"advance"},
            {"command":"buy-advance X","features":{"cash":-40,"count.4.cash":-40,"advance.X":1},"cashCost":40,"tokenCost":0,"group":"x","family":"advance"},
            {"command":"pass","features":{},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
        ]
    }"""
}
