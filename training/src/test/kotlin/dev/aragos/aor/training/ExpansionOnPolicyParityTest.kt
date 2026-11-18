package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyModel
import java.nio.file.Path
import org.junit.Test

/** Synthetic cross-runtime fixture; no retained model, observation or game is opened. */
class ExpansionOnPolicyParityTest {
    @Test
    fun publishSyntheticNativeProbabilitiesAndSignedSeedChoices() {
        val model =
            TerritoryPolicyModel(
                schemaVersion = 2,
                encoderVersion = TerritoryEncoder.EXPANSION_2.version,
                stateFeatureNames = listOf("after-left", "after-right", "root"),
                actionFeatureNames = listOf("cost", "left", "right"),
                stateWeights = List(192) { if (it == 2) .3 else 0.0 },
                stateBias = List(64) { if (it == 0) .2 else 0.0 },
                actionWeights =
                    List(192) {
                        when (it) {
                            0 -> .1
                            1 -> .4
                            2 -> -.2
                            else -> 0.0
                        }
                    },
                actionBias = List(64) { if (it == 0) -.1 else 0.0 },
                actorWeights = List(64) { if (it == 0) 1.2 else 0.0 },
                valueWeights = List(64) { if (it == 0) .8 else 0.0 },
                valueBias = -.3,
            )
        val observation =
            TerritoryObservation(
                TerritoryEncoder.EXPANSION_2.version,
                mapOf("root" to 1.0),
                listOf(
                    TerritoryAction("left", mapOf("left" to 1.0)),
                    TerritoryAction("right", mapOf("right" to 1.0)),
                ),
                0,
            )
        val cases =
            listOf(
                Triple("nonzero", model, observation),
                Triple("zero-head", model.copy(actorWeights = List(64) { 0.0 }), observation),
                Triple("singleton", model, observation.copy(actions = observation.actions.take(1))),
            )
        val seeds = listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE, -179)
        val fixture =
            mapOf(
                "kind" to "expansion-schema2-synthetic-parity-1",
                "cases" to
                    cases.map { (name, actor, state) ->
                        mapOf(
                            "name" to name,
                            "model" to
                                mapOf(
                                    "schemaVersion" to actor.schemaVersion,
                                    "encoderVersion" to actor.encoderVersion,
                                    "hiddenSize" to actor.hiddenSize,
                                    "baselineProbability" to actor.baselineProbability,
                                    "stateFeatureNames" to actor.stateFeatureNames,
                                    "actionFeatureNames" to actor.actionFeatureNames,
                                    "stateWeights" to actor.stateWeights,
                                    "stateBias" to actor.stateBias,
                                    "actionWeights" to actor.actionWeights,
                                    "actionBias" to actor.actionBias,
                                    "actorWeights" to actor.actorWeights,
                                    "valueWeights" to actor.valueWeights,
                                    "valueBias" to actor.valueBias,
                                ),
                            "observation" to state,
                            "evaluation" to TerritoryPolicy.evaluate(state, actor),
                            "choices" to
                                seeds.map { seed ->
                                    mapOf(
                                        "seed" to seed,
                                        "uniform" to TerritoryPolicy.uniform(seed),
                                        "choice" to TerritoryPolicy.choose(state, actor, seed),
                                    )
                                },
                        )
                    },
            )
        val directory = Path.of("build/test-fixtures").toAbsolutePath()
        TrainingArtifacts(directory).write("expansion-schema2-parity.json", fixture)

        assertThat(TerritoryPolicy.evaluate(observation, model).probabilities[0])
            .isWithin(1e-12)
            .of(.9829436874186683)
        assertThat(PurchaseJson.read(directory.resolve("expansion-schema2-parity.json")))
            .isEqualTo(ExpansionOnPolicyStore.wire(fixture))
    }
}
