package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class BotTokenValueExportsTest {
    @Test
    fun exportedHardPolicyUsesTheLearnedTokenAdvantage() {
        val choice = chooseBotMove(observation(), model(gate = 1), "hard", 0)

        assertEquals("buy-tokens 0", choice)
    }

    @Test
    fun zeroGatePreservesTheOriginalChoiceAcrossJson() {
        val choice = chooseBotMove(observation(), model(gate = 0), "hard", 0)

        assertEquals("buy-tokens 16", choice)
    }

    @Test
    fun missingGatePreservesTheOriginalChoiceAcrossJson() {
        val choice = chooseBotMove(observation(), model(gate = null), "hard", 0)

        assertEquals("buy-tokens 16", choice)
    }

    @Test
    fun incompleteMetadataPreservesTheOriginalChoiceAcrossJson() {
        val choice = chooseBotMove(observation(complete = false), model(gate = 1), "hard", 0)

        assertEquals("buy-tokens 16", choice)
    }

    @Test
    fun normalDifficultyDoesNotUseTheTokenHead() {
        val choice = chooseBotMove(observation(), model(gate = 1), "normal", 0)

        assertEquals("buy-tokens 16", choice)
    }

    @Test
    fun exportedHardPolicyUsesTheAppendedJointPlanColumns() {
        val model =
            """{"schemaVersion":1,"weights":{"points":1,"${BotTokenValue.GATE}":1,"${BotTokenPlanFeature.HELD_COMMODITY.key}":1}}"""

        val choice = chooseBotMove(observation(heldAmount = 24), model, "hard", 0)

        assertEquals("buy-tokens 24", choice)
    }

    @Test
    fun missingAnAppendedPlanColumnKeepsTheIncumbent() {
        val model =
            """{"schemaVersion":1,"weights":{"points":1,"${BotTokenValue.GATE}":1,"${BotTokenPlanFeature.HELD_COMMODITY.key}":1}}"""

        val choice =
            chooseBotMove(
                observation(heldAmount = 24, missingFeature = BotTokenPlanFeature.INTEREST.key),
                model,
                "hard",
                0,
            )

        assertEquals("buy-tokens 16", choice)
    }

    private fun model(gate: Int?): String =
        """{
        "schemaVersion":1,"weights":{
            "points":1,"planning.tokenValue.v1.cost":-1
            ${gate?.let { ",\"${BotTokenValue.GATE}\":$it" }.orEmpty()}
        }
    }"""

    private fun observation(
        complete: Boolean = true,
        heldAmount: Int? = null,
        missingFeature: String? = null,
    ): String {
        val candidates =
            listOf(16, 0, -4, 8, 24, 36).joinToString(",") { amount ->
                val features =
                    BotTokenValue.featureNames.associateWith { 0.0 } +
                        mapOf(
                            "planning.tokenValue.v1.cost" to abs(amount) / 64.0,
                            BotTokenPlanFeature.HELD_COMMODITY.key to
                                if (amount == heldAmount) 0.1 else 0.0,
                            "points" to if (amount == 16) 1.0 else 0.0,
                        )
                val included =
                    (if (complete) features else features - "planning.tokenValue.v1.received")
                        .filterKeys { it != missingFeature }
                val json = included.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
                """{"command":"buy-tokens $amount","features":{$json},"cashCost":${abs(amount)},"tokenCost":0,"group":"","family":"other"}"""
            }
        return """{
        "schemaVersion":1,"playerId":"p1","decision":"buy-tokens",
        "context":{"stock":36},"availableCash":100,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[$candidates]
    }"""
    }
}
