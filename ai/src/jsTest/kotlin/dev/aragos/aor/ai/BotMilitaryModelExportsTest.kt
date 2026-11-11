package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals

class BotMilitaryModelExportsTest {
    private val incumbent = BotModel(weights = mapOf("points" to 1.0, "card.stirrups" to -20.0))
    private val enabled =
        incumbent.copy(
            weights =
                incumbent.weights +
                    (BotMilitaryModel.GATE to 1.0) +
                    BotMilitaryModel.Coefficient.entries.associate { it.key to 2.0 }
        )

    @Test
    fun parsedBundleActivatesTheCompleteOverrideForHard() {
        val full =
            incumbent.copy(
                weights =
                    incumbent.weights +
                        BotMilitaryModel.Coefficient.entries.associate { it.feature to 2.0 }
            )

        assertEquals("play military", chooseBotMove(observation, model(full), "hard", 0))
        assertEquals(
            chooseBotMove(observation, model(full), "hard", 0),
            chooseBotMove(
                observation,
                """{"schemaVersion":1,"models":[${model(enabled)}]}""",
                "hard",
                0,
            ),
        )
    }

    @Test
    fun parsedTransportPreservesEasyAndNormalExactly() {
        for (difficulty in listOf("easy", "normal")) {
            for (seed in 0..20) {
                assertEquals(
                    chooseBotMove(observation, model(incumbent), difficulty, seed),
                    chooseBotMove(observation, model(enabled), difficulty, seed),
                )
            }
        }
    }

    @Test
    fun incompleteParsedCoefficientsCannotPartiallyActivate() {
        val incomplete =
            enabled.copy(weights = enabled.weights - BotMilitaryModel.Coefficient.GUNPOWDER.key)

        assertEquals("pass", chooseBotMove(observation, model(incomplete), "hard", 0))
    }

    private fun model(model: BotModel): String {
        val weights =
            BotModels.serializedWeights(model).entries.joinToString(",") { (feature, weight) ->
                "\"$feature\":$weight"
            }
        return """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION","weights":{$weights}}"""
    }

    private val observation =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"play-cards","context":{},
        "availableCash":0,"availableTokens":0,"rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[{"command":"play military","features":{"card.stirrups":1},
        "cashCost":0,"tokenCost":0,"group":"card.military","family":"play"},
        {"command":"pass","features":{"points":1},
        "cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}]
    }"""
}
