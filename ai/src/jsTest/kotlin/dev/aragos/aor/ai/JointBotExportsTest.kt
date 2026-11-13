package dev.aragos.aor.ai

import kotlin.js.JSON
import kotlin.test.Test
import kotlin.test.assertEquals

class JointBotExportsTest {
    @Test
    fun cardChoicesUseTheExactNativeSeededOwner() {
        val selector = JointBotSelector(baseline(), incumbent(), asset())
        val input = observation("play-cards")
        val projected = readCardProjection(projection("card"))
        val model = readCardModel(JSON.parse<dynamic>(model("card", active = true)))

        listOf(0, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            val expected =
                CardPolicy.choose(projected.bindBaseline("pass"), model, CardPolicy.actorSeed(seed))
            assertEquals(
                expected.command,
                selector.choose(input, null, projection("card"), "HARD", seed),
            )
        }
    }

    @Test
    fun purchaseChoicesUseTheSeparateFrozenPriorAndNativeSampler() {
        val selector = JointBotSelector(baseline(), incumbent(), asset())
        val projected = readPurchaseProjection(projection("purchase"))
        val model = readPurchaseModel(JSON.parse<dynamic>(model("purchase", active = true)))

        listOf(0, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            val expected =
                PurchasePolicy.choose(
                    projected.bindBaseline("pass"),
                    model,
                    PurchasePolicy.actorSeed(seed),
                )
            assertEquals(
                expected.command,
                selector.choose(
                    observation("purchase-advances"),
                    projection("purchase"),
                    null,
                    "HARD",
                    seed,
                ),
            )
        }
    }

    @Test
    fun zeroResidualCardActorSamplesItsNonmaximumActionOnTheNativeStream() {
        val raw = JSON.parse<dynamic>(asset())
        raw.cardModel = JSON.parse<dynamic>(model("card", active = false))
        val selector = JointBotSelector(baseline(), incumbent(), JSON.stringify(raw))

        assertEquals(
            "play card",
            selector.choose(
                observation("play-cards"),
                null,
                projection("card"),
                "HARD",
                Int.MAX_VALUE xor 0x3c6ef372,
            ),
        )
    }

    @Test
    fun missingCardModelCannotInstallTheValidPurchaseHalf() {
        val raw = JSON.parse<dynamic>(asset())
        raw.cardModel = null
        val selector = JointBotSelector(baseline(), incumbent(), JSON.stringify(raw))
        val fallback = PurchaseBotSelector(baseline(), incumbent())
        val input = observation("purchase-advances")
        val seed = 17 xor 0x6a09e667

        assertEquals("pass", fallback.choose(input, projection("purchase"), "HARD", seed))
        assertEquals("pass", selector.choose(input, projection("purchase"), null, "HARD", seed))
    }

    @Test
    fun missingPurchaseModelCannotInstallTheValidCardHalf() {
        val raw = JSON.parse<dynamic>(asset())
        raw.purchaseModel = null
        val selector = JointBotSelector(baseline(), incumbent(), JSON.stringify(raw))

        assertEquals(
            "pass",
            selector.choose(observation("play-cards"), null, projection("card"), "HARD", 17),
        )
    }

    @Test
    fun outsideScopeRetainsTheCompleteIncumbentPolicy() {
        val selector = JointBotSelector(baseline(), incumbent(), asset())
        val fallback = PurchaseBotSelector(baseline(), incumbent())
        val inputs =
            listOf(
                observation("purchase-advances").replace("three-epoch-5", "three-epoch-4"),
                observation("purchase-advances").replace("\"playerCount\":4", "\"playerCount\":3"),
                observation("purchase-advances")
                    .replace("\"drawThreeKeepTwo\":false", "\"drawThreeKeepTwo\":true"),
                observation("expand"),
            )
        inputs.forEach { input ->
            assertEquals(
                fallback.choose(input, projection("purchase"), "HARD", 17),
                selector.choose(input, projection("purchase"), projection("card"), "HARD", 17),
            )
        }
    }

    @Test
    fun easyAndNormalRetainTheCompleteIncumbentPolicy() {
        val selector = JointBotSelector(baseline(), incumbent(), asset())
        val fallback = PurchaseBotSelector(baseline(), incumbent())
        listOf("EASY", "NORMAL").forEach { level ->
            val input = observation("purchase-advances")
            assertEquals(
                fallback.choose(input, projection("purchase"), level, 17),
                selector.choose(input, projection("purchase"), projection("card"), level, 17),
            )
        }
    }

    @Test
    fun invalidCardProjectionRetainsOrdinaryCards() {
        val selector = JointBotSelector(baseline(), incumbent(), asset())
        listOf(null, "{}", projection("card").replace("play card", "play absent")).forEach { input
            ->
            assertEquals(
                "pass",
                selector.choose(observation("play-cards"), null, input, "HARD", 17),
            )
        }
    }

    @Test
    fun changedOrdinaryBindingRetainsIncumbentPurchaseInference() {
        val raw = JSON.parse<dynamic>(asset())
        raw.ordinaryBaseline.weights["test.baseline"] = -1
        val selector = JointBotSelector(baseline(), incumbent(), JSON.stringify(raw))
        val fallback = PurchaseBotSelector(baseline(), incumbent())
        val input = observation("purchase-advances")

        assertEquals(
            fallback.choose(input, projection("purchase"), "HARD", 17),
            selector.choose(input, projection("purchase"), null, "HARD", 17),
        )
    }

    @Test
    fun supportedCardProfileExcludesOnlyKnownOwnChaos() {
        val projected = JSON.parse<dynamic>(projection("card"))
        projected.actions[1].features["effect.role.0.chaos"] = 1.0
        val selector = JointBotSelector(baseline(), incumbent(), supportedAsset())

        listOf(0, 17, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            assertEquals(
                "pass",
                selector.choose(
                    observation("play-cards"),
                    null,
                    JSON.stringify(projected),
                    "HARD",
                    seed,
                ),
            )
        }
    }

    @Test
    fun supportedPurchaseProfileRetainsTheSurvivingAlternative() {
        val projected = JSON.parse<dynamic>(projection("purchase"))
        projected.actions[0].features.chaos = 1.0
        val selector = JointBotSelector(baseline(), incumbent(), supportedAsset())

        assertEquals(
            "pass",
            selector.choose(
                observation("purchase-advances"),
                JSON.stringify(projected),
                null,
                "HARD",
                17,
            ),
        )
    }

    @Test
    fun uncertainCardConsequencePreservesTheOriginalSample() {
        val projected = JSON.parse<dynamic>(projection("card"))
        projected.actions[1].features["effect.role.0.chaos"] = 1.0
        projected.actions[1].features["effect.role.0.miseryUncertain"] = 1.0
        val raw = JointBotSelector(baseline(), incumbent(), asset())
        val supported = JointBotSelector(baseline(), incumbent(), supportedAsset())
        val input = observation("play-cards")
        val facts = JSON.stringify(projected)

        listOf(0, 17, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            assertEquals(
                raw.choose(input, null, facts, "HARD", seed),
                supported.choose(input, null, facts, "HARD", seed),
            )
        }
    }

    @Test
    fun allKnownChaosCardActionsPreserveTheOriginalSample() {
        val projected = JSON.parse<dynamic>(projection("card"))
        projected.actions[0].features["effect.role.0.chaos"] = 1.0
        projected.actions[1].features["effect.role.0.chaos"] = 1.0
        val raw = JointBotSelector(baseline(), incumbent(), asset())
        val supported = JointBotSelector(baseline(), incumbent(), supportedAsset())
        val input = observation("play-cards")
        val facts = JSON.stringify(projected)

        listOf(0, 17, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
            assertEquals(
                raw.choose(input, null, facts, "HARD", seed),
                supported.choose(input, null, facts, "HARD", seed),
            )
        }
    }

    @Test
    fun supportDoesNotEnterEasyNormalOrOtherSetupScopes() {
        val raw = JointBotSelector(baseline(), incumbent(), asset())
        val supported = JointBotSelector(baseline(), incumbent(), supportedAsset())
        val projected = JSON.parse<dynamic>(projection("purchase"))
        projected.actions[0].features.chaos = 1.0
        val facts = JSON.stringify(projected)
        val input = observation("purchase-advances")
        val cases =
            listOf(
                input to "EASY",
                input to "NORMAL",
                input.replace("\"playerCount\":4", "\"playerCount\":3") to "HARD",
                observation("expand") to "HARD",
            )

        cases.forEach { (ordinary, difficulty) ->
            assertEquals(
                raw.choose(ordinary, facts, null, difficulty, 17),
                supported.choose(ordinary, facts, null, difficulty, 17),
            )
        }
    }

    @Test
    fun unknownSupportProfileCannotSilentlyInstallAnUnrecognizedBehavior() {
        val raw = JSON.parse<dynamic>(supportedAsset())
        raw.supportProfile = "future-support"
        val selector = JointBotSelector(baseline(), incumbent(), JSON.stringify(raw))
        val fallback = PurchaseBotSelector(baseline(), incumbent())
        val input = observation("purchase-advances")

        assertEquals(
            fallback.choose(input, projection("purchase"), "HARD", 17),
            selector.choose(input, projection("purchase"), null, "HARD", 17),
        )
    }

    @Test
    fun supportedDiagnosticExportsExposeConditionalProbabilityAndUnchangedValue() {
        val raw = JSON.parse<dynamic>(projection("card"))
        raw.baselineIndex = 0
        raw.actions[1].features["effect.role.0.chaos"] = 1.0
        val input = JSON.stringify(raw)
        val modelJson = model("card", active = true)
        val original = JSON.parse<dynamic>(evaluateCardPolicy(input, modelJson, .5))
        val supported = JSON.parse<dynamic>(evaluateKnownOwnChaosCardPolicy(input, modelJson, .5))

        assertEquals(KnownOwnChaosSupport.VERSION, purchaseString(supported.supportProfile))
        assertEquals("pass", purchaseString(supported.command))
        assertEquals(1.0, purchaseNumber(supported.probabilities[0]))
        assertEquals(0.0, purchaseNumber(supported.probabilities[1]))
        assertEquals(1, purchaseInteger(supported.excludedIndices[0]))
        assertEquals(0.0, purchaseNumber(supported.logProbability))
        assertEquals(purchaseNumber(original.value), purchaseNumber(supported.value))
        assertEquals(
            JSON.stringify(original.probabilities),
            JSON.stringify(supported.originalProbabilities),
        )
    }

    @Test
    fun supportedPurchaseExportPreservesTheOriginalProbabilityReference() {
        val raw = JSON.parse<dynamic>(projection("purchase"))
        raw.baselineIndex = 1
        raw.actions[0].features.chaos = 1.0
        val input = JSON.stringify(raw)
        val modelJson = model("purchase", active = true)
        val original = JSON.parse<dynamic>(evaluatePurchasePolicy(input, modelJson, .5))
        val supported =
            JSON.parse<dynamic>(evaluateKnownOwnChaosPurchasePolicy(input, modelJson, .5))

        assertEquals("pass", purchaseString(supported.command))
        assertEquals(0.0, purchaseNumber(supported.probabilities[0]))
        assertEquals(1.0, purchaseNumber(supported.probabilities[1]))
        assertEquals(purchaseNumber(original.value), purchaseNumber(supported.value))
        assertEquals(
            JSON.stringify(original.probabilities),
            JSON.stringify(supported.originalProbabilities),
        )
    }

    @Test
    fun outerExpansionUsesActualReferenceAndOriginalTerritorySeed() {
        val original = JointBotSelector(baseline(), incumbent(), asset())
        val selector = ExpansionBotSelector(original, expansionAsset())
        val input = observation("expand").replace("buy-advance A", "place 1 genoa")
        val facts =
            projection("card")
                .replace("card-observation-1", "expansion-observation-2")
                .replace("play card", "place 1 genoa")
        val model = readExpansionModel(JSON.parse<dynamic>(expansionModel()))

        for (seed in listOf(0, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val reference = original.choose(input, null, null, "HARD", seed)
            val expected =
                TerritoryPolicy.choose(
                    readExpansionProjection(facts).bindBaseline(reference),
                    model,
                    TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, seed),
                )
            assertEquals(expected.command, selector.choose(input, null, null, facts, "HARD", seed))
        }
    }

    @Test
    fun malformedExpansionFactsRetainTheOriginalReference() {
        val original = JointBotSelector(baseline(), incumbent(), asset())
        val selector = ExpansionBotSelector(original, expansionAsset())
        val input = observation("expand")

        for (facts in listOf(null, "{}", projection("purchase"))) {
            assertEquals(
                original.choose(input, null, null, "HARD", 17),
                selector.choose(input, null, null, facts, "HARD", 17),
            )
        }
    }

    @Test
    fun outerExpansionPreservesSupportedCardBehaviorExactly() {
        val original = JointBotSelector(baseline(), incumbent(), supportedAsset())
        val selector = ExpansionBotSelector(original, expansionAsset(KnownOwnChaosSupport.VERSION))
        val input = observation("play-cards")
        val facts = JSON.parse<dynamic>(projection("card"))
        facts.actions[1].features["effect.role.0.chaos"] = 1.0
        val card = JSON.stringify(facts)

        assertEquals(
            original.choose(input, null, card, "HARD", 17),
            selector.choose(input, null, card, "{}", "HARD", 17),
        )
    }

    @Test
    fun rawJointReferenceCannotBeSubstitutedForSupportedComposition() {
        val original = JointBotSelector(baseline(), incumbent(), asset())
        val selector = ExpansionBotSelector(original, expansionAsset(KnownOwnChaosSupport.VERSION))
        val input = observation("expand").replace("buy-advance A", "place 1 genoa")
        val facts =
            projection("card")
                .replace("card-observation-1", "expansion-observation-2")
                .replace("play card", "place 1 genoa")

        assertEquals(
            original.choose(input, null, null, "HARD", 17),
            selector.choose(input, null, null, facts, "HARD", 17),
        )
    }

    @Test
    fun invalidJointCannotInstallTheOptionalExpansionHead() {
        val original = JointBotSelector(baseline(), incumbent(), "{}")
        val selector = ExpansionBotSelector(original, expansionAsset())
        val input = observation("expand").replace("buy-advance A", "place 1 genoa")
        val facts =
            projection("card")
                .replace("card-observation-1", "expansion-observation-2")
                .replace("play card", "place 1 genoa")

        assertEquals(
            original.choose(input, null, null, "HARD", 17),
            selector.choose(input, null, null, facts, "HARD", 17),
        )
    }

    private fun expansionModel(): String =
        model("expansion", active = true)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .replace("expansion-observation-1", "expansion-observation-2")

    private fun expansionAsset(support: String? = null): String {
        val raw = js("({})")
        raw.schemaVersion = 1
        raw.kind = "qualified-expansion-actor-1"
        raw.actorVersion = ExpansionPolicyScope.ACTOR_VERSION
        raw.encoderVersion = TerritoryEncoder.EXPANSION_2.version
        raw.rulesVersion = ExpansionPolicyScope.RULES_VERSION
        raw.dataVersion = ExpansionPolicyScope.DATA_VERSION
        raw.configuration = JSON.parse<dynamic>(configuration())
        raw.ordinaryBaseline = JSON.parse<dynamic>(baseline())
        raw.model = JSON.parse<dynamic>(expansionModel())
        val reference = js("({})")
        reference.kind = "browser-policy-reference-1"
        // Transport hashing is exercised independently at the worker's binding boundary.
        reference.ordinarySha256 = "0".repeat(64)
        reference.purchaseSha256 = "0".repeat(64)
        reference.jointSha256 = "0".repeat(64)
        reference.supportProfile = support
        raw.reference = reference
        return JSON.stringify(raw)
    }

    private fun supportedAsset(): String {
        val raw = JSON.parse<dynamic>(asset())
        raw.supportProfile = KnownOwnChaosSupport.VERSION
        return JSON.stringify(raw)
    }

    private fun baseline() = """{"schemaVersion":1,"weights":{"test.baseline":1}}"""

    private fun configuration() =
        """{"playerCount":4,"map":"STANDARD","initialCard":"NO_CRUSADES",
        "deckModification":true,"immediateMasterArtDiscard":true,"drawThreeKeepTwo":false}"""

    private fun observation(decision: String): String {
        val command = if (decision == "play-cards") "play card" else "buy-advance A"
        return """{"schemaVersion":1,"playerId":"p1","decision":"$decision",
        "rulesVersion":"ah1996-three-epoch-5","dataVersion":"ah1996-catalog-16",
        "configuration":${configuration()},"context":{},"availableCash":10,"availableTokens":0,
        "candidates":[{"command":"$command","features":{},"cashCost":0,"tokenCost":0,
        "group":"choice","family":"choice"},{"command":"pass","features":{"test.baseline":1},
        "cashCost":0,"tokenCost":0,"group":"pass","family":"pass"}]}"""
    }

    private fun projection(phase: String): String {
        val actions =
            if (phase == "card")
                """[{"command":"pass","features":{}},{"command":"play card","features":{"choice":1}}]"""
            else
                """[{"command":"buy-advance A","features":{"choice":1}},{"command":"pass","features":{}}]"""
        return """{"encoderVersion":"aor-$phase-observation-1","stateFeatures":{"root":1},"actions":$actions}"""
    }

    private fun vector(value: Double = 0.0) =
        List(64) { value }.joinToString(prefix = "[", postfix = "]")

    private fun model(phase: String, active: Boolean): String {
        val features =
            if (phase == "card") {
                listOf("choice", "effect.role.0.chaos", "effect.role.0.miseryUncertain")
            } else {
                listOf("chaos", "choice")
            }
        val names = features.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val weights =
            List(64) { features.map { if (active && it == "choice") 1.0 else 0.0 } }
                .flatten()
                .joinToString(prefix = "[", postfix = "]")
        return """{"schemaVersion":1,"encoderVersion":"aor-$phase-observation-1","hiddenSize":64,
        "baselineProbability":0.97,"stateFeatureNames":["root"],"actionFeatureNames":$names,
        "stateWeights":${vector()},"stateBias":${vector()},"actionWeights":$weights,
        "actionBias":${vector()},"actorWeights":${vector(if (active) 1.0 else 0.0)},
        "valueWeights":${vector()},"valueBias":0}"""
    }

    private fun incumbent() =
        """{"schemaVersion":1,"actorVersion":"aor-purchase-policy-1",
        "encoderVersion":"aor-purchase-observation-1","rulesVersion":"ah1996-three-epoch-5",
        "dataVersion":"ah1996-catalog-16","configuration":${configuration()},
        "baseline":${baseline()},"model":${model("purchase", active = false)}}"""

    private fun asset() =
        """{"schemaVersion":1,"kind":"qualified-joint-card-purchase-1",
        "rulesVersion":"ah1996-three-epoch-5","dataVersion":"ah1996-catalog-16",
        "configuration":${configuration()},"cardActorVersion":"aor-card-policy-1",
        "purchaseActorVersion":"aor-purchase-policy-1","ordinaryBaseline":${baseline()},
        "cardBaseline":${baseline()},"purchaseBaseline":${baseline()},
        "cardModel":${model("card", active = true)},"purchaseModel":${model("purchase", active = true)}}"""
}
