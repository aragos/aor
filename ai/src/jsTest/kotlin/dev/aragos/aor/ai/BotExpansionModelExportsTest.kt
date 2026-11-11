package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotExpansionModelExportsTest {
    private val incumbent = BotModel(weights = mapOf("income" to -5.0, "points" to 1.0))
    private val replacements =
        mapOf(
            "tokens" to 0.0,
            "progress" to 0.0,
            "reach" to 0.0,
            "commodity" to 0.0,
            "income" to 1.0,
            "risk" to 0.0,
            "action.expansion" to 0.0,
            "action.pass" to 0.0,
        )
    private val enabled =
        incumbent.copy(
            weights =
                incumbent.weights +
                    ("planning.expansionModel.enabled" to 1.0) +
                    replacements.mapKeys { "planning.expansionModel.${it.key}" }
        )

    @Test
    fun parsedCompleteCoefficientsMatchTheFullModelOnExpansion() {
        val full = incumbent.copy(weights = incumbent.weights + replacements)

        assertEquals("attack a", chooseBotMove(observation(), model(full), "hard", 0))
        assertEquals(
            chooseBotMove(observation(), model(full), "hard", 0),
            chooseBotMove(observation(), model(enabled), "hard", 0),
        )
    }

    @Test
    fun modelBundlesRetainTheOptInCoefficients() {
        val bundle = """{"schemaVersion":1,"models":[${model(enabled)}]}"""

        assertEquals("attack a", chooseBotMove(observation(), bundle, "hard", 0))
    }

    @Test
    fun anIncompleteParsedModelRetainsTheOrdinaryPolicy() {
        val incomplete =
            enabled.copy(weights = enabled.weights - "planning.expansionModel.action.pass")

        assertEquals("pass", chooseBotMove(observation(), model(incomplete), "hard", 0))
    }

    @Test
    fun parsedCoefficientsDoNotAffectOtherDecisions() {
        val input = observation().replace("\"expand\"", "\"play-cards\"")

        assertEquals(
            chooseBotMove(input, model(incumbent), "hard", 0),
            chooseBotMove(input, model(enabled), "hard", 0),
        )
    }

    @Test
    fun parsedCoefficientsDoNotAffectOtherDifficulties() {
        for (difficulty in listOf("easy", "normal")) {
            for (seed in 0..20) {
                assertEquals(
                    chooseBotMove(observation(), model(incumbent), difficulty, seed),
                    chooseBotMove(observation(), model(enabled), difficulty, seed),
                )
            }
        }
    }

    private fun model(model: BotModel): String {
        val weights =
            BotModels.serializedWeights(model).entries.joinToString(",") { (feature, weight) ->
                "\"$feature\":$weight"
            }
        return """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION","weights":{$weights}}"""
    }

    private fun observation(): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"expand","context":{},
        "availableCash":0,"availableTokens":2,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[
            {"command":"attack a","features":{"income":1},"cashCost":0,"tokenCost":1,"group":"a","family":"expansion"},
            {"command":"pass","features":{"points":0.5},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}
        ]
    }"""
}
