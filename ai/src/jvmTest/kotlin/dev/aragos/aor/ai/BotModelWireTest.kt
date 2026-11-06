package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class BotModelWireTest {
    @Test
    fun sparseWireWeightsOverlayThePriorIncludingExplicitZerosAndLearnedKeys() {
        val model = BotModels.fromSerializedWeights(mapOf("cash" to 0.0, "planning.example" to 2.0))

        assertThat(model.weights["cash"]).isEqualTo(0.0)
        assertThat(model.weights["income"]).isEqualTo(BotModels.default.weights["income"])
        assertThat(model.weights["planning.example"]).isEqualTo(2.0)
    }

    @Test
    fun directModelConstructionKeepsMissingWeightsAtZero() {
        val model = BotModel(weights = mapOf("planning.example" to 2.0))
        val candidate = BotCandidate("income", mapOf("income" to 1.0))

        assertThat(BotPolicy.score(candidate, model)).isEqualTo(0.0)
        assertThat(model.weights).doesNotContainKey("income")
    }

    @Test
    fun serializingASparseInMemoryModelPreservesItsExactPolicyOnRead() {
        val original = BotModel(weights = mapOf("cash" to 0.0, "planning.example" to 2.0))

        val serialized = BotModels.serializedWeights(original)
        val restored = BotModels.fromSerializedWeights(serialized)

        assertThat(serialized["income"]).isEqualTo(0.0)
        assertThat(serialized["cash"]).isEqualTo(0.0)
        assertThat(serialized["planning.example"]).isEqualTo(2.0)
        for (feature in restored.weights.keys + original.weights.keys) {
            assertThat(restored.weights[feature] ?: 0.0).isEqualTo(original.weights[feature] ?: 0.0)
        }
    }

    @Test
    fun completeModelSerializationLeavesEveryWeightUnchanged() {
        val original =
            BotModels.default.copy(
                weights = BotModels.default.weights + ("planning.example" to 2.0)
            )

        val serialized = BotModels.serializedWeights(original)

        assertThat(serialized).containsExactlyEntriesIn(original.weights)
        assertThat(BotModels.fromSerializedWeights(serialized)).isEqualTo(original)
    }
}
