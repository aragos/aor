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

    @Test
    fun exportedPurchasePlanningSkipsShippingThatSeaworthyWillReplace() {
        val model = """{"schemaVersion":1,"weights":{"advance.S":1000,"reach":10}}"""

        val selected = chooseBotMove(purchaseObservation(), model, "hard", 0)

        assertEquals("pass", selected)
    }

    @Test
    fun exportedImmediateMasterArtKeepsValuableCardsAndDiscardsTheObservedBurden() {
        val hand =
            """[
            {"reference":"a-valuable","card":"timber","value":20,"burden":false},
            {"reference":"z-burden","card":"armor","value":0,"burden":true}
        ]"""
        val acquisitions =
            """[
            {"letter":"N","turn":1},{"letter":"O","turn":1},{"letter":"P","turn":1}
        ]"""
        val input =
            purchaseObservation()
                .replace("purchase-shipping", "immediate-master-art-discard")
                .replace("upgrade-ships", "discard-card z-burden")
                .replace("\"acquisitions\":[]", "\"acquisitions\":$acquisitions")
                .replace("\"hand\":[]", "\"hand\":$hand")

        val selected = chooseBotMove(input, "", "normal", 0)

        assertEquals("discard-card z-burden", selected)
    }

    private fun purchaseObservation(): String =
        """{
            "schemaVersion":1,"playerId":"p1","decision":"purchase-shipping",
            "context":{"playerCount":4,"epoch":1,"endgame":0},
            "availableCash":100,"availableTokens":0,
            "rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION",
            "candidates":[
                {"command":"pass","features":{},"cashCost":0,"tokenCost":0,"group":"pass","family":"pass"},
                {"command":"upgrade-ships","features":{"cash":-10,"reach":6},"cashCost":10,"tokenCost":0,"group":"ships","family":"upgrade-ships"}
            ],
            "purchase":{
                "owner":"p1","currentTurn":2,"decision":"purchase-shipping",
                "seats":[
                    {"id":"p1","capital":"venice","cash":100},
                    {"id":"p2","capital":"genoa","cash":40},
                    {"id":"p3","capital":"barcelona","cash":40},
                    {"id":"p4","capital":"paris","cash":40}
                ],
                "cash":100,"misery":"ZERO","ships":"NONE","acquisitions":[],"hand":[],
                "lastShipUpgradeTurn":null,"reliefSpaces":0,"reliefRatingSpent":0,
                "immediateMasterArtDiscard":true,"europeanMap":false,"papalCategory":null,
                "crusadesPlayed":false,"leaders":[],"dataVersion":"$DATA_VERSION"
            }
        }"""

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
