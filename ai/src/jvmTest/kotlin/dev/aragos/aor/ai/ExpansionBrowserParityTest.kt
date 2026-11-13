package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Constructed public inputs only; no game, stored actor or trajectory is read or simulated. */
class ExpansionBrowserParityTest {
    @Test
    fun exportNativeForwardAndSelectionForProductionJavaScript() {
        val fixtures =
            listOf(
                Fixture("zero", 5, true),
                Fixture("nonzero", 7, false),
                Fixture("singleton", 1, false),
                Fixture("full1024", 1024, false),
            )
        val cases = fixtures.flatMap(::cases)
        val target = Path.of("build", "test-fixtures", "expansion-browser-parity.json")
        val payload =
            mapOf(
                "kind" to "expansion-browser-parity-1",
                "constructedOnly" to true,
                "newGames" to 0,
                "retainedModelsRead" to 0,
                "tolerance" to 2e-12,
                "cases" to cases,
            )

        Files.createDirectories(target.parent)
        Files.writeString(target, json(payload) + "\n")

        assertThat(cases).hasSize(60)
        assertThat(Files.size(target)).isGreaterThan(0L)
    }

    private data class Fixture(val name: String, val count: Int, val zeroResidual: Boolean)

    private fun cases(fixture: Fixture): List<Map<String, Any?>> {
        val model = model(fixture.zeroResidual)
        val projection = projection(fixture.count)
        val baseline = projection.actions[fixture.count / 2].command
        val observation = projection.bindBaseline(baseline)
        val evaluation = TerritoryPolicy.evaluate(observation, model)
        val uniforms = listOf(0.0, 1e-12, 0.01, 0.03, 0.49, 0.97, 0.99, 0.999999999999)
        val seeds = listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, -179, 2147480000)
        fun sample(id: String, uniform: Double, seed: Int? = null): Map<String, Any?> {
            val actorSeed = seed?.let { TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, it) }
            val choice = TerritoryPolicy.select(observation, evaluation, uniform)
            if (actorSeed != null) {
                assertThat(TerritoryPolicy.choose(observation, model, actorSeed)).isEqualTo(choice)
            }
            return mapOf(
                "id" to "${fixture.name}-$id",
                "model" to modelWire(model),
                "projection" to
                    mapOf(
                        "encoderVersion" to projection.encoderVersion,
                        "stateFeatures" to projection.stateFeatures,
                        "actions" to
                            projection.actions.map {
                                mapOf("command" to it.command, "features" to it.features)
                            },
                    ),
                "baselineCommand" to baseline,
                "uniform" to uniform,
                "seed" to seed,
                "actorSeed" to actorSeed,
                "expected" to
                    mapOf(
                        "logits" to evaluation.logits,
                        "probabilities" to evaluation.probabilities,
                        "value" to evaluation.value,
                        "index" to choice.index,
                        "command" to choice.command,
                        "logProbability" to choice.logProbability,
                    ),
            )
        }
        return uniforms.mapIndexed { index, uniform -> sample("uniform-$index", uniform) } +
            seeds.map { seed ->
                val actorSeed = TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, seed)
                sample("seed-$seed", TerritoryPolicy.uniform(actorSeed), seed)
            }
    }

    private fun projection(count: Int): ExpansionProjection =
        ExpansionProjection(
            encoderVersion = TerritoryEncoder.EXPANSION_2.version,
            stateFeatures =
                mapOf("cash" to 0.625, "misery" to 0.25, "round" to 0.75, "tokens" to 0.5),
            actions =
                List(count) { index ->
                    TerritoryAction(
                        command =
                            if (count == 1) "pass"
                            else "place 1 unit${index.toString().padStart(4, '0')}",
                        features =
                            mapOf(
                                "attack" to (index % 2).toDouble(),
                                "cost" to (index % 13) / 16.0,
                                "income" to ((index % 11) - 5) / 8.0,
                            ),
                    )
                },
        )

    private fun model(zeroResidual: Boolean): TerritoryPolicyModel =
        TerritoryPolicyModel(
            schemaVersion = 2,
            encoderVersion = TerritoryEncoder.EXPANSION_2.version,
            stateFeatureNames = listOf("cash", "misery", "round", "tokens"),
            actionFeatureNames = listOf("attack", "cost", "income"),
            stateWeights = List(64 * 4) { ((it % 17) - 8) / 64.0 },
            stateBias = List(64) { ((it % 7) - 3) / 128.0 },
            actionWeights = List(64 * 3) { ((it % 13) - 6) / 32.0 },
            actionBias = List(64) { ((it % 11) - 5) / 128.0 },
            actorWeights = List(64) { if (zeroResidual) 0.0 else ((it % 5) - 2) / 16.0 },
            valueWeights = List(64) { if (zeroResidual) 0.0 else ((it % 7) - 3) / 64.0 },
            valueBias = if (zeroResidual) 0.0 else 0.125,
        )

    private fun modelWire(model: TerritoryPolicyModel): Map<String, Any> =
        mapOf(
            "schemaVersion" to model.schemaVersion,
            "encoderVersion" to model.encoderVersion,
            "hiddenSize" to model.hiddenSize,
            "baselineProbability" to model.baselineProbability,
            "stateFeatureNames" to model.stateFeatureNames,
            "actionFeatureNames" to model.actionFeatureNames,
            "stateWeights" to model.stateWeights,
            "stateBias" to model.stateBias,
            "actionWeights" to model.actionWeights,
            "actionBias" to model.actionBias,
            "actorWeights" to model.actorWeights,
            "valueWeights" to model.valueWeights,
            "valueBias" to model.valueBias,
        )

    /** Tiny test-only writer keeps this public fixture independent of server JSON dependencies. */
    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Boolean,
            is Int,
            is Long -> value.toString()
            is Double -> {
                require(value.isFinite()) {
                    "Constructed parity JSON cannot contain nonfinite values."
                }
                value.toString()
            }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            is Map<*, *> ->
                value.entries.joinToString(prefix = "{", postfix = "}") { (key, entry) ->
                    require(key is String) { "Constructed parity JSON keys must be strings." }
                    "${json(key)}:${json(entry)}"
                }
            else -> error("Unsupported constructed parity JSON value: ${value::class}.")
        }
}
