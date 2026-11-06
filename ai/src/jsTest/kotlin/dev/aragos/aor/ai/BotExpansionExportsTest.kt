package dev.aragos.aor.ai

import dev.aragos.aor.Commodity
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BotExpansionExportsTest {
    @Test
    fun exportedInferenceUsesTypedCommodityBundleFacts() {
        val selected = chooseBotMove(observation(), model(), "hard", 0)

        assertEquals("place 1 a", selected)
    }

    @Test
    fun olderObservationsWithoutPlanningFactsRemainPlayable() {
        val selected = chooseBotMove(observation(planning = null), model(), "hard", 0)

        assertEquals("place 2 c", selected)
    }

    @Test
    fun aPriorWithoutBundleWeightsKeepsItsOriginalMove() {
        val selected = chooseBotMove(observation(), "{}", "hard", 0)

        assertEquals("place 2 c", selected)
    }

    @Test
    fun exportedPriorRespectsItsMarkerBudgetWithoutBundleWeights() {
        val input =
            observation()
                .replace("\"markerStock\":2", "\"markerStock\":1")
                .replace("\"points\":6", "\"points\":7")

        val selected = chooseBotMove(input, "{}", "hard", 0)

        assertEquals("place 2 c", selected)
    }

    @Test
    fun exportedPriorCountsOverseasPartialsAgainstTheSharedSlotBudget() {
        val planning =
            planning()
                .replace("\"markerStock\":2", "\"markerStock\":2,\"overseasSlots\":1")
                .replace(
                    "\"markerCost\":1,\"commodities\":[\"WOOL\"]",
                    "\"markerCost\":0,\"commodities\":[],\"newOverseasCost\":1",
                )
        val input = observation(planning).replace("\"points\":6", "\"points\":7")

        val selected = chooseBotMove(input, "{}", "hard", 0)

        assertEquals("place 2 c", selected)
    }

    @Test
    fun aFractionalMarkerBudgetIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(
                observation().replace("\"markerStock\":2", "\"markerStock\":1.5"),
                model(),
                "hard",
                0,
            )
        }
    }

    @Test
    fun factsForAnUnofferedCommandAreRejected() {
        val planning = planning().replace("\"place 1 a\"", "\"place 1 unavailable\"")

        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(observation(planning), model(), "hard", 0)
        }
    }

    @Test
    fun sharedChoiceCardsRetainNegativeInteractionsAcrossJson() {
        val planning =
            planning()
                .replace("\"markets\":1", "\"markets\":0")
                .replace("\"heldCards\":[[\"WOOL\"]]", "\"heldCards\":[[\"CLOTH\",\"WINE\"]]")
                .replace(
                    "\"place 1 a\":{\"markerCost\":1,\"commodities\":[\"WOOL\"]}",
                    "\"place 1 a\":{\"markerCost\":1,\"commodities\":[\"CLOTH\"]}",
                )
                .replace(
                    "\"place 1 b\":{\"markerCost\":1,\"commodities\":[\"WOOL\"]}",
                    "\"place 1 b\":{\"markerCost\":1,\"commodities\":[\"WINE\"]}",
                )
        val input = observation(planning).replace("\"points\":13", "\"points\":10")

        val selected = chooseBotMove(input, model(), "hard", 0)

        assertEquals("place 2 c", selected)
    }

    private fun model(): String =
        """{"schemaVersion":1,"weights":{"planning.heldCommodityBundle":1}}"""

    private fun observation(planning: String? = planning()): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"expand","context":{},
        "availableCash":0,"availableTokens":2,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        ${planning?.let { "\"expansionPlanning\":$it," }.orEmpty()}
        "candidates":[
            {"command":"place 1 a","features":{"points":6},"cashCost":0,"tokenCost":1,"group":"a","family":"expansion"},
            {"command":"place 1 b","features":{"points":6},"cashCost":0,"tokenCost":1,"group":"b","family":"expansion"},
            {"command":"place 2 c","features":{"points":13},"cashCost":0,"tokenCost":2,"group":"c","family":"expansion"}
        ]
    }"""

    private fun planning(): String {
        val commodities =
            Commodity.entries.joinToString(",") { commodity ->
                """{"commodity":"${commodity.name}","markets":${if (commodity == Commodity.WOOL) 1 else 0},"baseValue":2,"adjustmentOffset":0,"outsideCards":0}"""
            }
        return """{
            "markerStock":2,"industry":false,"commodities":[$commodities],
            "heldCards":[["WOOL"]],
            "steps":{
                "place 1 a":{"markerCost":1,"commodities":["WOOL"]},
                "place 1 b":{"markerCost":1,"commodities":["WOOL"]},
                "place 2 c":{"markerCost":1,"commodities":["TIMBER"]}
            }
        }"""
    }
}
