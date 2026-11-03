package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BotExportsTest {
    @Test
    fun anEmptyDownloadedModelUsesThePlayablePrior() {
        val selected =
            chooseBotMove(observation(), """{"schemaVersion":1,"weights":{}}""", "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aCompatibleModelChangesTheActualExportedInference() {
        val model =
            """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION","weights":{"cash":-1}}"""

        val selected = chooseBotMove(observation(), model, "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun aModelFromAnotherCatalogFallsBackToThePrior() {
        val model =
            """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"obsolete","weights":{"cash":-1}}"""

        val selected = chooseBotMove(observation(), model, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun anOlderSupportedSaveCanUseThePriorWithANewerCachedModel() {
        val model = """{"schemaVersion":1,"dataVersion":"$DATA_VERSION","weights":{"cash":-1}}"""

        val selected =
            chooseBotMove(
                observation().replace(DATA_VERSION, "ah1996-catalog-13"),
                model,
                "normal",
                0,
            )

        assertEquals("cash-choice", selected)
    }

    @Test
    fun malformedDownloadedModelFallsBackWithoutChangingTheGame() {
        val selected = chooseBotMove(observation(), "{broken", "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun infiniteObservedFeaturesAreRejectedAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(observation().replace("\"cash\":10", "\"cash\":1e309"), "", "normal", 0)
        }
    }

    @Test
    fun unsupportedObservationSchemasCannotReachInference() {
        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(
                observation().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                "",
                "normal",
                0,
            )
        }
    }

    private fun observation(): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"test","context":{},
        "availableCash":10,"availableTokens":0,
        "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
        "candidates":[
            {"command":"cash-choice","features":{"cash":10},"cashCost":0,"tokenCost":0,"group":"cash","family":"other"},
            {"command":"points-choice","features":{"points":5},"cashCost":0,"tokenCost":0,"group":"points","family":"other"}
        ]
    }"""
}
