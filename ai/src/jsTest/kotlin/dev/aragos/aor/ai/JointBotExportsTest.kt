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

    private fun model(phase: String, active: Boolean): String =
        """{"schemaVersion":1,"encoderVersion":"aor-$phase-observation-1","hiddenSize":64,
        "baselineProbability":0.97,"stateFeatureNames":["root"],"actionFeatureNames":["choice"],
        "stateWeights":${vector()},"stateBias":${vector()},"actionWeights":${vector(if (active) 1.0 else 0.0)},
        "actionBias":${vector()},"actorWeights":${vector(if (active) 1.0 else 0.0)},
        "valueWeights":${vector()},"valueBias":0}"""

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
