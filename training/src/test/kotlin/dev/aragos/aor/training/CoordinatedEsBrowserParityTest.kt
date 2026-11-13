package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.CardAction
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.IncumbentResidualPolicy
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.PurchaseAction
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPolicyModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.Test

/** Literal public tensors only; the independent Python proof reconstructs this test factor map. */
class CoordinatedEsBrowserParityTest {
    @Test
    fun exportZeroAndArbitraryCoordinateRolesForProductionJavaScript() {
        val cases =
            Head.entries.flatMap { head ->
                listOf("zero" to 5, "vector" to 7, "singleton" to 1, "full" to head.maximum)
                    .flatMap { (name, count) ->
                        val original = network(head, false)
                        val current = network(head, name != "zero")
                        val scales = if (head.sampled) listOf(1.0) else listOf(1.0 / 32, 1.0, 32.0)
                        scales.flatMap { scale ->
                            seeds.map { botSeed ->
                                mapOf(
                                    "id" to "${head.name}-$name-$scale-$botSeed",
                                    "head" to head.name,
                                    "case" to name,
                                    "original" to wire(original),
                                    "model" to wire(current),
                                    "observation" to observation(head, count),
                                    "scale" to scale,
                                    "botSeed" to botSeed,
                                    "actorSeed" to actorSeed(head, botSeed),
                                    "expected" to
                                        evaluate(original, current, count, scale, botSeed),
                                )
                            }
                        }
                    }
            }
        val target = Path.of("build", "test-fixtures", "coordinated-es-browser-parity.json")
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            json(
                mapOf(
                    "kind" to "coordinated-es-browser-parity-1",
                    "constructedOnly" to true,
                    "newGames" to 0,
                    "retainedModelsRead" to 0,
                    "tolerance" to 2e-12,
                    "projection" to
                        mapOf(
                            "kind" to "coordinated-es-literal-test-map-1",
                            "headOrder" to Head.entries.map(Head::name),
                            "blockOrder" to blockOrder,
                            "coordinates" to coordinates,
                            "factorRule" to "((k*3+j*5+headOrdinal+blockOrdinal)%7)<3 ? -1 : 1",
                        ),
                    "cases" to cases,
                )
            ) + "\n",
        )
        assertThat(cases).hasSize(224)
        for (head in Head.entries) {
            assertThat(cases.any { it["head"] == head.name && it["case"] == "zero" }).isTrue()
            assertThat(cases.any { it["head"] == head.name && it["case"] == "full" }).isTrue()
        }
    }

    private enum class Head(val encoder: String, val schema: Int, val maximum: Int) {
        CARD(CardObservationEncoder.ENCODER_VERSION, 1, 1024),
        PURCHASE(PurchaseObservationEncoder.ENCODER_VERSION, 1, 128),
        EXPANSION(TerritoryEncoder.EXPANSION_2.version, 2, 1024),
        TOKEN(TerritoryEncoder.TOKEN_2.version, 3, 75);

        val sampled: Boolean
            get() = this == CARD || this == PURCHASE
    }

    private data class Network(
        val head: Head,
        val states: List<String>,
        val actions: List<String>,
        val stateWeights: List<Double>,
        val stateBias: List<Double>,
        val actionWeights: List<Double>,
        val actionBias: List<Double>,
        val actorWeights: List<Double>,
        val valueWeights: List<Double>,
    )

    private val seeds = listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, -179, 2147480000)
    private val coordinates = List(256) { ((it % 13) - 6) / 16.0 + ((it % 7) - 3) / 64.0 }
    private val blockOrder =
        listOf(
            "stateLeft",
            "stateRight",
            "actionLeft",
            "actionRight",
            "stateBias",
            "actionBias",
            "actorWeights",
        )

    private fun factor(head: Head, block: Int, coordinate: Int, column: Int): Double =
        if ((coordinate * 3 + column * 5 + head.ordinal + block) % 7 < 3) -1.0 else 1.0

    private fun network(head: Head, changed: Boolean): Network {
        val states =
            (listOf("role.0.cash", "role.0.stock") +
                    if (head == Head.TOKEN) (0..5).map { "role.$it.capitalChoicePosition" }
                    else emptyList())
                .sorted()
        val actions =
            (listOf("amount", "kind.pass") +
                    if (head == Head.TOKEN)
                        (-36..36).map { "bid.amount.$it" } +
                            listOf("bid.amount.above", "bid.amount.below")
                    else emptyList())
                .sorted()
        fun matrix(width: Int, left: Int, right: Int, modulus: Int, divisor: Double): List<Double> =
            List(64 * width) { index ->
                val original = ((index % modulus) - modulus / 2) / divisor
                val delta =
                    if (changed) {
                        (0 until 64).sumOf { k ->
                            factor(head, left, k, index / width) *
                                coordinates[head.ordinal * 64 + k] *
                                factor(head, right, k, index % width)
                        } / (8 * sqrt(width.toDouble()))
                    } else 0.0
                original + delta
            }
        fun vector(block: Int, modulus: Int, divisor: Double, zero: Boolean = false): List<Double> =
            List(64) { index ->
                val original = if (zero) 0.0 else ((index % modulus) - modulus / 2) / divisor
                val delta =
                    if (changed) {
                        (0 until 64).sumOf { k ->
                            factor(head, block, k, index) * coordinates[head.ordinal * 64 + k]
                        } / 64
                    } else 0.0
                original + delta
            }
        return Network(
            head = head,
            states = states,
            actions = actions,
            stateWeights = matrix(states.size, 0, 1, 17, 64.0),
            stateBias = vector(4, 7, 128.0),
            actionWeights = matrix(actions.size, 2, 3, 13, 32.0),
            actionBias = vector(5, 11, 128.0),
            actorWeights = vector(6, 5, 16.0, zero = !head.sampled),
            valueWeights = List(64) { ((it % 7) - 3) / 64.0 },
        )
    }

    private fun command(index: Int): String = "unit-${index.toString().padStart(4, '0')}"

    private fun state(model: Network): Map<String, Double> =
        model.states.mapIndexed { index, name -> name to (index % 7 + 1) / 8.0 }.toMap()

    private fun features(model: Network, row: Int): Map<String, Double> =
        model.actions
            .mapIndexed { index, name ->
                name to (if ((index + row) % 2 == 0) 1.0 else -1.0) * ((index + row) % 5 + 1) / 8.0
            }
            .toMap()

    private fun observation(head: Head, count: Int): Map<String, Any> {
        val model = network(head, false)
        return mapOf(
            "encoderVersion" to head.encoder,
            "stateFeatures" to state(model),
            "baselineIndex" to count / 2,
            "actions" to
                List(count) {
                    mapOf("command" to command(it), "features" to features(model, it))
                },
        )
    }

    private fun actorSeed(head: Head, botSeed: Int): Int? =
        when (head) {
            Head.CARD -> CardPolicy.actorSeed(botSeed)
            Head.PURCHASE -> PurchasePolicy.actorSeed(botSeed)
            else -> null
        }

    private fun evaluate(
        original: Network,
        current: Network,
        count: Int,
        scale: Double,
        botSeed: Int,
    ): Map<String, Any?> =
        when (current.head) {
            Head.CARD -> {
                val observation =
                    CardObservation(
                        stateFeatures = state(current),
                        actions = List(count) { CardAction(command(it), features(current, it)) },
                        baselineIndex = count / 2,
                    )
                val model =
                    CardPolicyModel(
                        stateFeatureNames = current.states,
                        actionFeatureNames = current.actions,
                        stateWeights = current.stateWeights,
                        stateBias = current.stateBias,
                        actionWeights = current.actionWeights,
                        actionBias = current.actionBias,
                        actorWeights = current.actorWeights,
                        valueWeights = current.valueWeights,
                        valueBias = .125,
                    )
                val evaluation = CardPolicy.evaluate(observation, model)
                val choice = CardPolicy.choose(observation, model, CardPolicy.actorSeed(botSeed))
                mapOf(
                    "logits" to evaluation.logits,
                    "probabilities" to evaluation.probabilities,
                    "value" to evaluation.value,
                    "index" to choice.index,
                    "command" to choice.command,
                    "logProbability" to choice.logProbability,
                )
            }
            Head.PURCHASE -> {
                val observation =
                    PurchaseObservation(
                        stateFeatures = state(current),
                        actions =
                            List(count) { PurchaseAction(command(it), features(current, it)) },
                        baselineIndex = count / 2,
                    )
                val model =
                    PurchasePolicyModel(
                        stateFeatureNames = current.states,
                        actionFeatureNames = current.actions,
                        stateWeights = current.stateWeights,
                        stateBias = current.stateBias,
                        actionWeights = current.actionWeights,
                        actionBias = current.actionBias,
                        actorWeights = current.actorWeights,
                        valueWeights = current.valueWeights,
                        valueBias = .125,
                    )
                val evaluation = PurchasePolicy.evaluate(observation, model)
                val choice =
                    PurchasePolicy.choose(observation, model, PurchasePolicy.actorSeed(botSeed))
                mapOf(
                    "logits" to evaluation.logits,
                    "probabilities" to evaluation.probabilities,
                    "value" to evaluation.value,
                    "index" to choice.index,
                    "command" to choice.command,
                    "logProbability" to choice.logProbability,
                )
            }
            else -> {
                val observation =
                    TerritoryObservation(
                        encoderVersion = current.head.encoder,
                        stateFeatures = state(current),
                        actions =
                            List(count) { TerritoryAction(command(it), features(current, it)) },
                        baselineIndex = count / 2,
                    )
                val choice =
                    IncumbentResidualPolicy.choose(
                        observation,
                        territory(current),
                        territory(original),
                        IncumbentResidualSelector(scale),
                    )
                mapOf(
                    "residualDifferences" to choice.residualDifferences,
                    "scores" to choice.scores,
                    "index" to choice.index,
                    "command" to choice.command,
                    "strongestGap" to
                        IncumbentResidualPolicy.strongestGap(choice.residualDifferences, count / 2),
                )
            }
        }

    private fun territory(model: Network): TerritoryPolicyModel =
        TerritoryPolicyModel(
            schemaVersion = model.head.schema,
            encoderVersion = model.head.encoder,
            stateFeatureNames = model.states,
            actionFeatureNames = model.actions,
            stateWeights = model.stateWeights,
            stateBias = model.stateBias,
            actionWeights = model.actionWeights,
            actionBias = model.actionBias,
            actorWeights = model.actorWeights,
            valueWeights = model.valueWeights,
            valueBias = .125,
        )

    private fun wire(model: Network): Map<String, Any> =
        mapOf(
            "schemaVersion" to model.head.schema,
            "encoderVersion" to model.head.encoder,
            "hiddenSize" to 64,
            "baselineProbability" to .97,
            "stateFeatureNames" to model.states,
            "actionFeatureNames" to model.actions,
            "stateWeights" to model.stateWeights,
            "stateBias" to model.stateBias,
            "actionWeights" to model.actionWeights,
            "actionBias" to model.actionBias,
            "actorWeights" to model.actorWeights,
            "valueWeights" to model.valueWeights,
            "valueBias" to .125,
        )

    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Boolean,
            is Int -> value.toString()
            is Double -> {
                require(value.isFinite()) { "Constructed JSON requires finite values." }
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
