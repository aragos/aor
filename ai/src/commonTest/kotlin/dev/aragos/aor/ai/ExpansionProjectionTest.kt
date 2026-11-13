package dev.aragos.aor.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpansionProjectionTest {
    @Test
    fun bindingKeepsEveryFactAndTheOriginalCommandIndex() {
        val projection = fixture()

        val observation = projection.bindBaseline("place 1 venice")

        assertEquals(
            TerritoryObservation(
                encoderVersion = TerritoryEncoder.EXPANSION_2.version,
                stateFeatures = mapOf("role.0.pool" to 0.5),
                actions = projection.actions,
                baselineIndex = 1,
            ),
            observation,
        )
    }

    @Test
    fun aRecommendationMustBelongToTheCompleteOfferedSet() {
        assertFailsWith<IllegalArgumentException> { fixture().bindBaseline("buy-card") }
    }

    @Test
    fun aProjectionCannotReorderItsCommands() {
        val projection = fixture()

        assertFailsWith<IllegalArgumentException> {
            projection.copy(actions = projection.actions.reversed())
        }
    }

    @Test
    fun aProjectionCannotDuplicateAnAction() {
        val action = fixture().actions.first()

        assertFailsWith<IllegalArgumentException> {
            fixture().copy(actions = listOf(action, action))
        }
    }

    @Test
    fun aProjectionCannotCarryNonfiniteState() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(stateFeatures = mapOf("role.0.pool" to Double.NaN))
        }
    }

    @Test
    fun aTokenEncoderCannotEnterTheExpansionBoundary() {
        assertFailsWith<IllegalArgumentException> {
            fixture().copy(encoderVersion = TerritoryEncoder.TOKEN_1.version)
        }
    }

    @Test
    fun legacyExpansionFactsRetainTheirExplicitVersion() {
        val projection = fixture().copy(encoderVersion = TerritoryEncoder.EXPANSION_1.version)

        assertEquals(
            TerritoryEncoder.EXPANSION_1.version,
            projection.bindBaseline("pass").encoderVersion,
        )
    }

    private fun fixture() =
        ExpansionProjection(
            encoderVersion = TerritoryEncoder.EXPANSION_2.version,
            stateFeatures = mapOf("role.0.pool" to 0.5),
            actions =
                listOf(
                    TerritoryAction("pass", mapOf("kind.pass" to 1.0)),
                    TerritoryAction("place 1 venice", mapOf("tokens" to 1.0 / 36.0)),
                ),
        )
}
