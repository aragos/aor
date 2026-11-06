package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseProjectionTest {
    @Test
    fun bindingTheRecommendationPreservesEveryProjectedFeatureAndAction() {
        val projection = fixture()

        val observation = projection.bindBaseline("pass")

        assertThat(observation)
            .isEqualTo(
                PurchaseObservation(
                    stateFeatures = mapOf("role.0.cash" to 0.2),
                    actions =
                        listOf(
                            PurchaseAction("buy-advance A", mapOf("cost" to 0.15)),
                            PurchaseAction("pass", mapOf("cashAfter" to 0.2)),
                        ),
                    baselineIndex = 1,
                )
            )
    }

    @Test
    fun aDifferentBaselineChangesOnlyItsRecommendationIndex() {
        val projection = fixture()

        val observation = projection.bindBaseline("buy-advance A")

        assertThat(observation.baselineIndex).isEqualTo(0)
        assertThat(observation.actions).isEqualTo(projection.actions)
        assertThat(observation.stateFeatures).isEqualTo(projection.stateFeatures)
    }

    @Test
    fun anUnavailableRecommendationCannotBeSubstitutedWithAPlaceholder() {
        assertFailsWith<IllegalArgumentException> { fixture().bindBaseline("upgrade-ships") }
    }

    @Test
    fun aProjectionCannotReorderCommandsBeforeBinding() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(actions = fixture().actions.reversed())
        }
    }

    @Test
    fun aProjectionCannotContainDuplicateCommands() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(actions = List(2) { fixture().actions.first() })
        }
    }

    @Test
    fun aProjectionRejectsNonfiniteStateFeatures() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(stateFeatures = mapOf("role.0.cash" to Double.NaN))
        }
    }

    @Test
    fun aProjectionRejectsAnUnsupportedEncoder() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(encoderVersion = "future-purchase-encoder")
        }
    }

    @Test
    fun theActorSeedRetainsTheFrozenCollectorsSignedSalt() {
        val examples =
            mapOf(
                0 to 1779033703,
                17 to 1779033718,
                -1 to -1779033704,
                Int.MIN_VALUE to -368449945,
                Int.MAX_VALUE to 368449944,
            )

        examples.forEach { (seed, expected) ->
            assertThat(PurchasePolicy.actorSeed(seed)).isEqualTo(expected)
        }
    }

    private fun fixture(): PurchaseProjection =
        PurchaseProjection(
            stateFeatures = mapOf("role.0.cash" to 0.2),
            actions =
                listOf(
                    PurchaseAction("buy-advance A", mapOf("cost" to 0.15)),
                    PurchaseAction("pass", mapOf("cashAfter" to 0.2)),
                ),
        )
}
