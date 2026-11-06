package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotModelWireExportsTest {
    @Test
    fun omittedWireWeightsRetainTheirPriorValues() {
        val selected = chooseBotMove(observation(), model("\"cash\":0"), "normal", 0)

        assertEquals("income", selected)
    }

    @Test
    fun explicitZeroDisablesAPriorFeature() {
        val selected = chooseBotMove(observation(), model("\"income\":0"), "normal", 0)

        assertEquals("cash", selected)
    }

    @Test
    fun unknownLearnedWeightsReachExportedInference() {
        val selected = chooseBotMove(observation(), model("\"planning.example\":4"), "normal", 0)

        assertEquals("learned", selected)
    }

    @Test
    fun completeWireWeightsPreserveASparseInMemoryPolicy() {
        val sparse = BotModel(weights = mapOf("cash" to 0.0, "planning.example" to 2.0))
        val weights =
            BotModels.serializedWeights(sparse).entries.joinToString(",") { (feature, weight) ->
                "\"$feature\":$weight"
            }

        val selected = chooseBotMove(observation(), model(weights), "normal", 0)

        assertEquals("learned", selected)
    }

    @Test
    fun invalidModelSchemaNumbersFallBackToThePrior() {
        for (schema in listOf("1.5", "\"1\"", "4294967297", "-4294967295", "1e309")) {
            val document = """{"schemaVersion":$schema,"weights":{"planning.example":4}}"""

            val selected = chooseBotMove(observation(), document, "normal", 0)

            assertEquals("income", selected)
        }
    }

    @Test
    fun invalidBundleSchemaNumbersCannotActivateAnEntry() {
        for (schema in listOf("1.5", "\"1\"", "4294967297")) {
            val bundle =
                """{"schemaVersion":$schema,"models":[${model("\"planning.example\":4")}]}"""

            val selected = chooseBotMove(observation(), bundle, "normal", 0)

            assertEquals("income", selected)
        }
    }

    private fun model(weights: String): String = """{"schemaVersion":1,"weights":{$weights}}"""

    private fun observation(): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"test","context":{},
        "availableCash":10,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[
            {"command":"income","features":{"income":1},"cashCost":0,"tokenCost":0,"group":"income","family":"other"},
            {"command":"cash","features":{"cash":1},"cashCost":0,"tokenCost":0,"group":"cash","family":"other"},
            {"command":"learned","features":{"planning.example":1},"cashCost":0,"tokenCost":0,"group":"learned","family":"other"}
        ]
    }"""
}
