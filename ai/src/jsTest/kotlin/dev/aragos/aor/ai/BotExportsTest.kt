package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.LEGACY_RULES_VERSION
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
    fun aQualifiedConfigurationActivatesItsScopedModel() {
        val selected = chooseBotMove(configuredObservation(), scopedModel(), "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun aLegacyGameRetainsItsMatchingLegacyModel() {
        val input = configuredObservation().replace(RULES_VERSION, LEGACY_RULES_VERSION)
        val model = scopedModel().replace(RULES_VERSION, LEGACY_RULES_VERSION)

        val selected = chooseBotMove(input, model, "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun aCurrentRulesGameCannotActivateALegacyQualifiedModel() {
        val model = scopedModel().replace(RULES_VERSION, LEGACY_RULES_VERSION)

        val selected = chooseBotMove(configuredObservation(), model, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aLegacyModelAcceptsAnExplicitlyDisabledOpeningSelectionVariant() {
        val input = configuredObservation(openingConfiguration("false"))

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun anExplicitlyDisabledOpeningSelectionScopeAcceptsLegacyObservations() {
        val model = scopedModel(openingConfiguration("false"))

        val selected = chooseBotMove(configuredObservation(), model, "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun theOpeningSelectionVariantCannotActivateALegacyScopedModel() {
        val input = configuredObservation(openingConfiguration("true"))

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun anExplicitlyQualifiedOpeningSelectionModelMatchesThatVariant() {
        val configuration = openingConfiguration("true")

        val selected =
            chooseBotMove(
                configuredObservation(configuration),
                scopedModel(configuration),
                "normal",
                0,
            )

        assertEquals("points-choice", selected)
    }

    @Test
    fun malformedOpeningSelectionScopesCannotActivateAModel() {
        val selections =
            listOf("null", "1", "\"false\"").map { invalid ->
                chooseBotMove(
                    configuredObservation(),
                    scopedModel(openingConfiguration(invalid)),
                    "normal",
                    0,
                )
            }

        assertEquals(listOf("cash-choice", "cash-choice", "cash-choice"), selections)
    }

    @Test
    fun malformedObservedOpeningSelectionRulesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(configuredObservation(openingConfiguration("\"true\"")), "", "normal", 0)
        }
    }

    @Test
    fun aDifferentPlayerCountRetainsThePrior() {
        val input =
            configuredObservation(configuration().replace("\"playerCount\":4", "\"playerCount\":3"))

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aDifferentMapRetainsThePrior() {
        val input = configuredObservation(configuration().replace("STANDARD", "EUROPEAN"))

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aDifferentInitialCardRuleRetainsThePrior() {
        val input = configuredObservation(configuration().replace("NO_CRUSADES", "ANY"))

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aDifferentDeckModificationRetainsThePrior() {
        val input =
            configuredObservation(
                configuration().replace("\"deckModification\":true", "\"deckModification\":false")
            )

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aDifferentMasterArtRuleRetainsThePrior() {
        val input =
            configuredObservation(
                configuration()
                    .replace(
                        "\"immediateMasterArtDiscard\":true",
                        "\"immediateMasterArtDiscard\":false",
                    )
            )

        val selected = chooseBotMove(input, scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun missingObservationConfigurationCannotActivateAScopedModel() {
        val selected = chooseBotMove(observation(), scopedModel(), "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun malformedModelConfigurationFallsBackToThePrior() {
        val model =
            scopedModel(
                configuration()
                    .replace("\"deckModification\":true", "\"deckModification\":\"true\"")
            )

        val selected = chooseBotMove(configuredObservation(), model, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aScopedModelMustDeclareItsCatalogAndRulesVersions() {
        val model =
            """{"schemaVersion":1,"configuration":${configuration()},"weights":{"cash":-1}}"""

        val selected = chooseBotMove(configuredObservation(), model, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun aBundleRetainsItsLegacyModelForExistingCatalogGames() {
        val legacy =
            """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"ah1996-catalog-15","weights":{"cash":-1}}"""
        val bundle = """{"schemaVersion":1,"models":[${scopedModel()},$legacy]}"""
        val input = observation().replace(DATA_VERSION, "ah1996-catalog-15")

        val selected = chooseBotMove(input, bundle, "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun aBundleFallsBackOutsideItsQualifiedCurrentConfiguration() {
        val legacy =
            """{"schemaVersion":1,"dataVersion":"ah1996-catalog-15","weights":{"cash":-1}}"""
        val bundle = """{"schemaVersion":1,"models":[${scopedModel()},$legacy]}"""
        val input = configuredObservation(configuration().replace("NO_CRUSADES", "ANY"))

        val selected = chooseBotMove(input, bundle, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    @Test
    fun malformedBundleEntriesDoNotHideALaterQualifiedModel() {
        val invalid = """{"schemaVersion":1,"weights":{"cash":"invalid"}}"""
        val bundle = """{"schemaVersion":1,"models":[$invalid,${scopedModel()}]}"""

        val selected = chooseBotMove(configuredObservation(), bundle, "normal", 0)

        assertEquals("points-choice", selected)
    }

    @Test
    fun unsupportedBundleSchemasRetainThePrior() {
        val bundle = """{"schemaVersion":2,"models":[${scopedModel()}]}"""

        val selected = chooseBotMove(configuredObservation(), bundle, "normal", 0)

        assertEquals("cash-choice", selected)
    }

    private fun configuration(): String =
        """{"playerCount":4,"map":"STANDARD","initialCard":"NO_CRUSADES","deckModification":true,"immediateMasterArtDiscard":true}"""

    private fun openingConfiguration(value: String): String =
        configuration().dropLast(1) + ",\"drawThreeKeepTwo\":$value}"

    private fun configuredObservation(configuration: String = configuration()): String =
        observation().replace("\"context\":{}", "\"context\":{},\"configuration\":$configuration")

    private fun scopedModel(configuration: String = configuration()): String =
        """{"schemaVersion":1,"rulesVersion":"$RULES_VERSION","dataVersion":"$DATA_VERSION","configuration":$configuration,"weights":{"cash":-1}}"""

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
