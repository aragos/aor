package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Handwritten public fixtures only: no stored actor, game or trajectory is opened. */
class CpeBrowserParityTest {
    @Test
    fun exportAllThreeNativeHeadsForProductionJavaScript() {
        val cases =
            Head.entries.flatMap { head ->
                listOf(
                        Fixture(head, "zero", 5, true),
                        Fixture(head, "nonzero", 7, false),
                        Fixture(head, "singleton", 1, false),
                        Fixture(head, "full", head.maximumOffers, false),
                    )
                    .flatMap(::cases)
            }
        val target = Path.of("build", "test-fixtures", "cpe-browser-parity.json")
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            json(
                mapOf(
                    "kind" to "cpe-browser-parity-1",
                    "constructedOnly" to true,
                    "newGames" to 0,
                    "retainedModelsRead" to 0,
                    "tolerance" to 2e-12,
                    "selectionScope" to "native-uniform-and-signed-bot-seed",
                    "cases" to cases,
                )
            ) + "\n",
        )
        assertThat(cases).hasSize(180)
        for (head in Head.entries) {
            assertThat(
                    cases.any {
                        it["head"] == head.name && it["referenceDiffersFromPrior"] == true
                    }
                )
                .isTrue()
        }
    }

    private enum class Head(val encoder: String, val schema: Int, val maximumOffers: Int) {
        CARD(CardObservationEncoder.ENCODER_VERSION, 1, 1024),
        PURCHASE(PurchaseObservationEncoder.ENCODER_VERSION, 1, 128),
        EXPANSION(TerritoryEncoder.EXPANSION_2.version, 2, 1024),
    }

    private data class Fixture(
        val head: Head,
        val name: String,
        val count: Int,
        val zeroResidual: Boolean,
    )

    private data class Choice(val index: Int, val command: String, val logProbability: Double)

    private val stateNames = listOf("role.0.cash", "role.0.stock")
    private val actionNames = listOf("amount", "kind.pass")
    private val stateWeights = List(128) { ((it % 17) - 8) / 64.0 }
    private val stateBias = List(64) { ((it % 7) - 3) / 128.0 }
    private val actionWeights = List(128) { ((it % 13) - 6) / 32.0 }
    private val actionBias = List(64) { ((it % 11) - 5) / 128.0 }
    private val valueWeights = List(64) { ((it % 7) - 3) / 64.0 }
    private val state = mapOf("role.0.cash" to 0.5, "role.0.stock" to 1.0)

    private fun actorWeights(fixture: Fixture): List<Double> =
        List(64) { if (fixture.zeroResidual) 0.0 else ((it % 5) - 2) / 16.0 }

    private fun command(index: Int): String = "unit-${index.toString().padStart(4, '0')}"

    private fun features(index: Int, count: Int): Map<String, Double> =
        mapOf("amount" to index.toDouble() / count, "kind.pass" to if (index == 0) 1.0 else 0.0)

    private fun cases(fixture: Fixture): List<Map<String, Any?>> {
        val baselineIndex = fixture.count / 2
        return when (fixture.head) {
            Head.CARD -> {
                val model =
                    CardPolicyModel(
                        stateFeatureNames = stateNames,
                        actionFeatureNames = actionNames,
                        stateWeights = stateWeights,
                        stateBias = stateBias,
                        actionWeights = actionWeights,
                        actionBias = actionBias,
                        actorWeights = actorWeights(fixture),
                        valueWeights = valueWeights,
                        valueBias = 0.125,
                    )
                val observation =
                    CardObservation(
                        stateFeatures = state,
                        actions =
                            List(fixture.count) {
                                CardAction(command(it), features(it, fixture.count))
                            },
                        baselineIndex = baselineIndex,
                    )
                val evaluation = CardPolicy.evaluate(observation, model)
                export(
                    fixture,
                    evaluation.logits,
                    evaluation.probabilities,
                    evaluation.value,
                    CardPolicy::actorSeed,
                    CardPolicy::uniform,
                    { uniform ->
                        val choice = CardPolicy.select(observation, evaluation, uniform)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                    { seed ->
                        val choice = CardPolicy.choose(observation, model, seed)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                )
            }
            Head.PURCHASE -> {
                val model =
                    PurchasePolicyModel(
                        stateFeatureNames = stateNames,
                        actionFeatureNames = actionNames,
                        stateWeights = stateWeights,
                        stateBias = stateBias,
                        actionWeights = actionWeights,
                        actionBias = actionBias,
                        actorWeights = actorWeights(fixture),
                        valueWeights = valueWeights,
                        valueBias = 0.125,
                    )
                val observation =
                    PurchaseObservation(
                        stateFeatures = state,
                        actions =
                            List(fixture.count) {
                                PurchaseAction(command(it), features(it, fixture.count))
                            },
                        baselineIndex = baselineIndex,
                    )
                val evaluation = PurchasePolicy.evaluate(observation, model)
                export(
                    fixture,
                    evaluation.logits,
                    evaluation.probabilities,
                    evaluation.value,
                    PurchasePolicy::actorSeed,
                    PurchasePolicy::uniform,
                    { uniform ->
                        val choice = PurchasePolicy.select(observation, evaluation, uniform)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                    { seed ->
                        val choice = PurchasePolicy.choose(observation, model, seed)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                )
            }
            Head.EXPANSION -> {
                val model =
                    TerritoryPolicyModel(
                        schemaVersion = 2,
                        encoderVersion = TerritoryEncoder.EXPANSION_2.version,
                        stateFeatureNames = stateNames,
                        actionFeatureNames = actionNames,
                        stateWeights = stateWeights,
                        stateBias = stateBias,
                        actionWeights = actionWeights,
                        actionBias = actionBias,
                        actorWeights = actorWeights(fixture),
                        valueWeights = valueWeights,
                        valueBias = 0.125,
                    )
                val observation =
                    TerritoryObservation(
                        encoderVersion = TerritoryEncoder.EXPANSION_2.version,
                        stateFeatures = state,
                        actions =
                            List(fixture.count) {
                                TerritoryAction(command(it), features(it, fixture.count))
                            },
                        baselineIndex = baselineIndex,
                    )
                val evaluation = TerritoryPolicy.evaluate(observation, model)
                export(
                    fixture,
                    evaluation.logits,
                    evaluation.probabilities,
                    evaluation.value,
                    { seed -> TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, seed) },
                    TerritoryPolicy::uniform,
                    { uniform ->
                        val choice = TerritoryPolicy.select(observation, evaluation, uniform)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                    { seed ->
                        val choice = TerritoryPolicy.choose(observation, model, seed)
                        Choice(choice.index, choice.command, choice.logProbability)
                    },
                )
            }
        }
    }

    private fun export(
        fixture: Fixture,
        logits: List<Double>,
        probabilities: List<Double>,
        value: Double,
        actorSeed: (Int) -> Int,
        uniform: (Int) -> Double,
        select: (Double) -> Choice,
        choose: (Int) -> Choice,
    ): List<Map<String, Any?>> {
        val uniforms = listOf(0.0, 1e-12, 0.01, 0.03, 0.49, 0.97, 0.99, 0.999999999999)
        val seeds = listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, -179, 2147480000)
        fun sample(id: String, draw: Double, seed: Int? = null): Map<String, Any?> {
            val mixed = seed?.let(actorSeed)
            val choice = select(draw)
            if (mixed != null) assertThat(choose(mixed)).isEqualTo(choice)
            return mapOf(
                "id" to "${fixture.head}-${fixture.name}-$id",
                "head" to fixture.head.name,
                "model" to modelWire(fixture),
                "observation" to
                    mapOf(
                        "encoderVersion" to fixture.head.encoder,
                        "stateFeatures" to state,
                        "actions" to
                            List(fixture.count) {
                                mapOf(
                                    "command" to command(it),
                                    "features" to features(it, fixture.count),
                                )
                            },
                        "baselineIndex" to fixture.count / 2,
                    ),
                "referenceIndex" to choice.index,
                "referenceDiffersFromPrior" to (choice.index != fixture.count / 2),
                "uniform" to draw,
                "botSeed" to seed,
                "actorSeed" to mixed,
                "expected" to
                    mapOf(
                        "logits" to logits,
                        "probabilities" to probabilities,
                        "value" to value,
                        "index" to choice.index,
                        "command" to choice.command,
                        "logProbability" to choice.logProbability,
                    ),
            )
        }
        return uniforms.mapIndexed { index, draw -> sample("uniform-$index", draw) } +
            seeds.map { seed -> sample("seed-$seed", uniform(actorSeed(seed)), seed) }
    }

    private fun modelWire(fixture: Fixture): Map<String, Any> =
        mapOf(
            "schemaVersion" to fixture.head.schema,
            "encoderVersion" to fixture.head.encoder,
            "hiddenSize" to 64,
            "baselineProbability" to 0.97,
            "stateFeatureNames" to stateNames,
            "actionFeatureNames" to actionNames,
            "stateWeights" to stateWeights,
            "stateBias" to stateBias,
            "actionWeights" to actionWeights,
            "actionBias" to actionBias,
            "actorWeights" to actorWeights(fixture),
            "valueWeights" to valueWeights,
            "valueBias" to 0.125,
        )

    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Boolean,
            is Int -> value.toString()
            is Double -> {
                require(value.isFinite()) { "Constructed JSON requires finite numbers." }
                value.toString()
            }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            is Map<*, *> ->
                value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                    require(key is String) { "Constructed JSON requires string keys." }
                    "${json(key)}:${json(item)}"
                }
            else -> error("Unsupported constructed JSON value: ${value::class}.")
        }
}
