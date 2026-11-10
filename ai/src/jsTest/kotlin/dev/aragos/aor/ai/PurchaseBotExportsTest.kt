package dev.aragos.aor.ai

import dev.aragos.aor.RULES_VERSION
import kotlin.js.JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PurchaseBotExportsTest {
    @Test
    fun activeActorSamplesTheCollectorSeedStreamInsteadOfTakingTheLargestProbability() {
        val selector = PurchaseBotSelector(baseline(), asset())

        val command = selector.choose(observation(), projection(), "HARD", 0x6a09e667)

        assertEquals("buy-advance A", command)
        assertEquals("pass", chooseBotMove(observation(), baseline(), "HARD", 0x6a09e667))
    }

    @Test
    fun anExplicitlyDisabledOpeningSelectionRuleAcceptsTheLegacyPurchaseAsset() {
        val input = JSON.parse<dynamic>(observation())
        input.configuration.drawThreeKeepTwo = false
        val selector = PurchaseBotSelector(baseline(), asset())

        val command = selector.choose(JSON.stringify(input), projection(), "HARD", 0x6a09e667)

        assertEquals("buy-advance A", command)
    }

    @Test
    fun theOpeningSelectionVariantRetainsTheOrdinaryPolicyInsteadOfTheFrozenPurchasePrior() {
        val input = JSON.parse<dynamic>(observation())
        input.configuration.drawThreeKeepTwo = true
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())

        val command =
            selector.choose(JSON.stringify(input), projection(), "HARD", 17 xor 0x6a09e667)

        assertEquals("buy-advance A", command)
    }

    @Test
    fun currentRulesGamesRetainTheOrdinaryPolicyInsteadOfTheLegacyPurchaseActor() {
        val input = JSON.parse<dynamic>(observation())
        input.rulesVersion = RULES_VERSION
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())

        val command =
            selector.choose(JSON.stringify(input), projection(), "HARD", 17 xor 0x6a09e667)

        assertEquals("buy-advance A", command)
    }

    @Test
    fun aPurchaseAssetCannotBroadenItsQualifiedOpeningRules() {
        val raw = JSON.parse<dynamic>(asset())
        raw.configuration.drawThreeKeepTwo = true
        val selector = PurchaseBotSelector(baseline(), JSON.stringify(raw))

        val command = selector.choose(observation(), projection(), "HARD", 0x6a09e667)

        assertEquals("pass", command)
    }

    @Test
    fun shippingUsesTheSameSampledActorWithCanonicalActionOrder() {
        val input =
            observation()
                .replace("purchase-advances", "purchase-shipping")
                .replace("buy-advance A", "upgrade-ships")
        val raw = JSON.parse<dynamic>(projection().replace("buy-advance A", "upgrade-ships"))
        val actions = raw.actions as Array<dynamic>
        raw.actions = actions.reversedArray()
        val selector = PurchaseBotSelector(baseline(), asset())

        val command =
            selector.choose(input, JSON.stringify(raw), "HARD", Int.MAX_VALUE xor 0x6a09e667)

        assertEquals("upgrade-ships", command)
    }

    @Test
    fun cachedProductionActorMatchesNonzeroNumericalInference() {
        val raw = JSON.parse<dynamic>(asset())
        raw.model.actionWeights[0] = 1.0
        raw.model.actorWeights[0] = 3.0
        val input = JSON.parse<dynamic>(projection())
        input.baselineIndex = 1
        val selector = PurchaseBotSelector(baseline(), JSON.stringify(raw))
        listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            val expected =
                JSON.parse<dynamic>(
                    evaluatePurchasePolicy(
                        JSON.stringify(input),
                        JSON.stringify(raw.model),
                        PurchasePolicy.uniform(seed xor 0x6a09e667),
                    )
                )

            assertEquals(
                expected.command as String,
                selector.choose(observation(), projection(), "HARD", seed),
            )
        }
    }

    @Test
    fun signedSeedGoldensPreserveTheSharedSampledChoice() {
        val selector = PurchaseBotSelector(baseline(), asset())
        val actorSeeds = listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE, -979738860)
        val expected =
            listOf("buy-advance A", "pass", "pass", "pass", "pass", "pass", "pass", "pass")

        val commands = actorSeeds.map { seed ->
            selector.choose(observation(), projection(), "HARD", seed xor 0x6a09e667)
        }

        assertEquals(expected, commands)
    }

    @Test
    fun optionalMissingAndMalformedAssetsKeepTheComputedBaseline() {
        listOf("", "null", "{}", "invalid-json").forEach { invalid ->
            val selector = PurchaseBotSelector(baseline(), invalid)

            assertEquals("pass", selector.choose(observation(), projection(), "HARD", 0x6a09e667))
        }
    }

    @Test
    fun differentlyWeightedBaselineCannotActivateTheActor() {
        val different = baseline().replace("\"test.baseline\":1", "\"test.baseline\":2")
        val selector = PurchaseBotSelector(different, asset())

        assertEquals("pass", selector.choose(observation(), projection(), "HARD", 0x6a09e667))
    }

    @Test
    fun qualifiedHybridUsesItsFrozenPriorWhenTheOrdinaryPolicyDisagrees() {
        val seed = 17 xor 0x6a09e667
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())
        val prior = chooseBotMove(observation(), baseline(), "HARD", seed)
        val ordinary = chooseBotMove(observation(), ordinaryBaseline(), "HARD", seed)

        val command = selector.choose(observation(), projection(), "HARD", seed)

        assertEquals("pass", prior)
        assertEquals("buy-advance A", ordinary)
        assertEquals(prior, command)
    }

    @Test
    fun qualifiedHybridShippingAlsoUsesTheFrozenPurchasePrior() {
        val input =
            observation()
                .replace("purchase-advances", "purchase-shipping")
                .replace("buy-advance A", "upgrade-ships")
        val purchase = JSON.parse<dynamic>(projection().replace("buy-advance A", "upgrade-ships"))
        val actions = purchase.actions as Array<dynamic>
        purchase.actions = actions.reversedArray()
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())
        val ordinary = chooseBotMove(input, ordinaryBaseline(), "HARD", 17 xor 0x6a09e667)

        val command = selector.choose(input, JSON.stringify(purchase), "HARD", 17 xor 0x6a09e667)

        assertEquals("upgrade-ships", ordinary)
        assertEquals("pass", command)
    }

    @Test
    fun qualifiedHybridPreservesTheFrozenPriorAndSampledActorSeedStream() {
        val raw = JSON.parse<dynamic>(hybridAsset())
        raw.model.actionWeights[0] = 1.0
        raw.model.actorWeights[0] = 3.0
        val input = JSON.parse<dynamic>(projection())
        input.baselineIndex = 1
        val selector = PurchaseBotSelector(ordinaryBaseline(), JSON.stringify(raw))

        listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { actorSeed ->
            val expected =
                JSON.parse<dynamic>(
                    evaluatePurchasePolicy(
                        JSON.stringify(input),
                        JSON.stringify(raw.model),
                        PurchasePolicy.uniform(actorSeed),
                    )
                )

            assertEquals(
                expected.command as String,
                selector.choose(observation(), projection(), "HARD", actorSeed xor 0x6a09e667),
            )
        }
    }

    @Test
    fun unmatchedHybridOrdinaryBindingCannotActivateTheActor() {
        val different = ordinaryBaseline().replace("\"test.baseline\":-1", "\"test.baseline\":-2")
        val selector = PurchaseBotSelector(different, hybridAsset())

        val command = selector.choose(observation(), projection(), "HARD", 17 xor 0x6a09e667)

        assertEquals("buy-advance A", command)
    }

    @Test
    fun malformedExplicitOrdinaryBindingCannotBecomeALegacyBinding() {
        val bindings =
            listOf(
                "null",
                "false",
                "1",
                "\"invalid\"",
                "[]",
                "{}",
                """{"schemaVersion":"1","weights":{"test.baseline":1}}""",
                """{"schemaVersion":1,"weights":{"test.baseline":false}}""",
            )
        bindings.forEach { binding ->
            val selector = PurchaseBotSelector(baseline(), hybridAsset(binding))

            assertEquals("pass", selector.choose(observation(), projection(), "HARD", 0x6a09e667))
        }
    }

    @Test
    fun suppliedRecommendationCannotReplaceTheActuallyComputedBaseline() {
        val raw = JSON.parse<dynamic>(projection())
        raw.baselineIndex = 0
        val selector = PurchaseBotSelector(baseline(), asset())

        val command = selector.choose(observation(), JSON.stringify(raw), "HARD", 17 xor 0x6a09e667)

        assertEquals("pass", command)
    }

    @Test
    fun easyAndNormalChoicesRemainExactlyTheOrdinaryPolicy() {
        listOf(baseline() to asset(), ordinaryBaseline() to hybridAsset()).forEach {
            (model, qualifiedAsset) ->
            val selector = PurchaseBotSelector(model, qualifiedAsset)
            listOf("EASY", "NORMAL").forEach { difficulty ->
                listOf(0, -1, 17, 0x6a09e667).forEach { seed ->
                    assertEquals(
                        chooseBotMove(observation(), model, difficulty, seed),
                        selector.choose(observation(), projection(), difficulty, seed),
                    )
                }
            }
        }
    }

    @Test
    fun otherDecisionFamiliesKeepTheOrdinaryPolicy() {
        val input = observation().replace("purchase-advances", "play-cards")
        val selector = PurchaseBotSelector(baseline(), asset())

        assertEquals(
            chooseBotMove(input, baseline(), "HARD", 0x6a09e667),
            selector.choose(input, projection(), "HARD", 0x6a09e667),
        )
    }

    @Test
    fun qualifiedHybridUsesItsOrdinaryModelOutsidePurchases() {
        val input = observation().replace("purchase-advances", "play-cards")
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())

        val command = selector.choose(input, projection(), "HARD", 17 xor 0x6a09e667)

        assertEquals("buy-advance A", command)
    }

    @Test
    fun unqualifiedCatalogAndConfigurationKeepTheOrdinaryPolicy() {
        val inputs =
            listOf(
                observation().replace("catalog-16", "catalog-15"),
                observation().replace("\"playerCount\":4", "\"playerCount\":3"),
                observation().replace("STANDARD", "EUROPEAN"),
                observation().replace("NO_CRUSADES", "ANY"),
                observation().replace("\"deckModification\":true", "\"deckModification\":false"),
                observation()
                    .replace(
                        "\"immediateMasterArtDiscard\":true",
                        "\"immediateMasterArtDiscard\":false",
                    ),
            )
        listOf(baseline() to asset(), ordinaryBaseline() to hybridAsset()).forEach {
            (model, qualifiedAsset) ->
            val selector = PurchaseBotSelector(model, qualifiedAsset)
            val seed = if (model == baseline()) 0x6a09e667 else 17 xor 0x6a09e667
            inputs.forEach { input ->
                assertEquals(
                    chooseBotMove(input, model, "HARD", seed),
                    selector.choose(input, projection(), "HARD", seed),
                )
            }
        }
    }

    @Test
    fun unsupportedOrdinaryRulesAreStillRejectedByBothSelectors() {
        val input = observation().replace("three-epoch-4", "three-epoch-3")
        val selector = PurchaseBotSelector(baseline(), asset())

        assertFailsWith<IllegalArgumentException> {
            chooseBotMove(input, baseline(), "HARD", 0x6a09e667)
        }
        assertFailsWith<IllegalArgumentException> {
            selector.choose(input, projection(), "HARD", 0x6a09e667)
        }
    }

    @Test
    fun incompatibleManifestIdentitiesCannotEnableTheActor() {
        val inputs =
            listOf(
                asset().replace("aor-purchase-policy-1", "aor-purchase-policy-2"),
                asset().replace("catalog-16", "catalog-15"),
                asset().replace("three-epoch-4", "three-epoch-3"),
                asset().replace("\"playerCount\":4", "\"playerCount\":3"),
                asset().replace("\"hiddenSize\":64", "\"hiddenSize\":63"),
            )
        inputs.forEach { invalid ->
            val selector = PurchaseBotSelector(baseline(), invalid)

            assertEquals("pass", selector.choose(observation(), projection(), "HARD", 0x6a09e667))
        }
    }

    @Test
    fun missingMalformedAndUnknownFeatureProjectionsKeepTheComputedBaseline() {
        val inputs =
            listOf(
                null,
                "{}",
                "invalid-json",
                projection().replace("\"root\":1", "\"unknown\":0"),
                projection().replace("\"root\":1", "\"root\":false"),
                projection().replace("aor-purchase-observation-1", "aor-purchase-observation-2"),
            )
        val selector = PurchaseBotSelector(baseline(), asset())
        inputs.forEach { invalid ->
            assertEquals("pass", selector.choose(observation(), invalid, "HARD", 0x6a09e667))
        }
        assertEquals(
            "buy-advance A",
            selector.choose(observation(), projection(), "HARD", 0x6a09e667),
        )
    }

    @Test
    fun qualifiedHybridFallsBackToItsOrdinaryModelWhenPurchaseInputFails() {
        val selector = PurchaseBotSelector(ordinaryBaseline(), hybridAsset())

        listOf(null, "{}", "invalid-json").forEach { invalid ->
            assertEquals(
                "buy-advance A",
                selector.choose(observation(), invalid, "HARD", 17 xor 0x6a09e667),
            )
        }
    }

    @Test
    fun projectionMustContainExactlyTheOrdinaryOfferedCommands() {
        val missing = JSON.parse<dynamic>(projection())
        missing.actions = arrayOf(missing.actions[1])
        val extra = projection().replace("buy-advance A", "buy-advance B")
        val selector = PurchaseBotSelector(baseline(), asset())

        listOf(JSON.stringify(missing), extra).forEach { invalid ->
            assertEquals("pass", selector.choose(observation(), invalid, "HARD", 0x6a09e667))
        }
    }

    private fun configuration(): String =
        """{"playerCount":4,"map":"STANDARD","initialCard":"NO_CRUSADES",
            "deckModification":true,"immediateMasterArtDiscard":true}"""

    private fun baseline(): String = """{"schemaVersion":1,"weights":{"test.baseline":1}}"""

    private fun ordinaryBaseline(): String =
        """{"schemaVersion":1,"weights":{"test.baseline":-1}}"""

    private fun hybridAsset(binding: String = ordinaryBaseline()): String {
        val raw = JSON.parse<dynamic>(asset())
        raw.ordinaryBaseline = JSON.parse<dynamic>(binding)
        return JSON.stringify(raw)
    }

    private fun observation(): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"purchase-advances",
        "rulesVersion":"ah1996-three-epoch-4","dataVersion":"ah1996-catalog-16",
        "configuration":${configuration()},"context":{},"availableCash":10,"availableTokens":0,
        "candidates":[
          {"command":"buy-advance A","features":{},"cashCost":1,"tokenCost":0,
           "group":"advance.A","family":"advance"},
          {"command":"pass","features":{"test.baseline":1},"cashCost":0,"tokenCost":0,
           "group":"pass","family":"pass"}]
        }"""

    private fun projection(): String =
        """{"encoderVersion":"aor-purchase-observation-1","stateFeatures":{"root":1},
        "actions":[{"command":"buy-advance A","features":{"choice":1}},
                   {"command":"pass","features":{}}]}"""

    private fun zeros(): String = List(64) { 0.0 }.joinToString(prefix = "[", postfix = "]")

    private fun asset(): String =
        """{"schemaVersion":1,"actorVersion":"aor-purchase-policy-1",
        "encoderVersion":"aor-purchase-observation-1","rulesVersion":"ah1996-three-epoch-4",
        "dataVersion":"ah1996-catalog-16","configuration":${configuration()},
        "baseline":${baseline()},"model":{
          "schemaVersion":1,"encoderVersion":"aor-purchase-observation-1",
          "hiddenSize":64,"baselineProbability":0.97,
          "stateFeatureNames":["root"],"actionFeatureNames":["choice"],
          "stateWeights":${zeros()},"stateBias":${zeros()},"actionWeights":${zeros()},
          "actionBias":${zeros()},"actorWeights":${zeros()},"valueWeights":${zeros()},
          "valueBias":0}}
        """
}
