package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed receipt/counter checks only; no simulator or learned-model execution. */
class CoordinatedEsContractsTest {
    @Test
    fun globalBankRoundTripsWithoutAddingDerivedHashToWire() {
        val bank = CoordinatedEsBank(81000000, 256, List(256) { StrategyArena.seed(81000000, it) })
        val wire = ExpansionOnPolicyStore.wire(bank)
        assertThat(wire.fieldNames().asSequence().toSet())
            .containsExactly("nativeOffset", "count", "seeds")
        assertThat(CoordinatedEsBank.read(wire, 256)).isEqualTo(bank)
        assertThat(bank.seeds[255]).isEqualTo(-919000000 + 255 * 8191)
    }

    @Test
    fun resetOrTruncatedSeedBankIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsBank(81000000, 256, List(256) { 1 })
        }
    }

    @Test
    fun populationRolesPreserveSignedDirectionOrder() {
        assertThat((0..31).map(CoordinatedEsPopulation::role).take(4))
            .containsExactly("PLUS-00", "MINUS-00", "PLUS-01", "MINUS-01")
            .inOrder()
        assertThat(CoordinatedEsPopulation.role(31)).isEqualTo("MINUS-15")
    }

    @Test
    fun projectedRadiusFourCenterSurvivesSummationRoundoff() {
        val period = listOf(-6.249067727079545, -2.0830225756931817, 2.0830225756931817)
        val coordinates = List(256) { period[it % period.size] }
        val scope = mapOf("scopeSha256" to "constructed")
        val center =
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "kind" to "coordinated-es-center-1",
                    "scope" to scope,
                    "generation" to 1,
                    "coordinates" to coordinates,
                )
            )
        CoordinatedEsWire.center(center, scope, 1)
    }

    @Test
    fun centerOutsideFixedRadiusIsRejected() {
        val scope = mapOf("scopeSha256" to "constructed")
        val center =
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "kind" to "coordinated-es-center-1",
                    "scope" to scope,
                    "generation" to 1,
                    "coordinates" to List(256) { 4.0001 },
                )
            )
        assertFailsWith<IllegalArgumentException> { CoordinatedEsWire.center(center, scope, 1) }
    }

    @Test
    fun failedAttemptIsCountedWithoutInventingCompletion() {
        val attempts = CoordinatedEsAttempts(System.nanoTime() + 1_000_000_000L)
        assertFailsWith<IllegalStateException> {
            attempts.execute<Unit> { error("constructed failure") }
        }
        assertThat(attempts.attemptedGames).isEqualTo(1)
        assertThat(attempts.completedGames).isEqualTo(0)
    }

    @Test
    fun expiredOriginalDeadlinePreventsAnyAttempt() {
        val attempts = CoordinatedEsAttempts(System.nanoTime() - 1)
        assertFailsWith<IllegalStateException> { attempts.execute { error("must not enter") } }
        assertThat(attempts.attemptedGames).isEqualTo(0)
    }

    @Test
    fun gameDeadlineCannotExtendOriginalClock() {
        val deadline = System.nanoTime() + 1_000_000_000L
        val attempts = CoordinatedEsAttempts(deadline)
        val supplied = attempts.execute { it }
        assertThat(supplied).isEqualTo(deadline)
        assertThat(attempts.completedGames).isEqualTo(1)
    }

    @Test
    fun newGameDeadlineIsAtMostThreeHundredSeconds() {
        val before = System.nanoTime()
        val attempts = CoordinatedEsAttempts(before + 1_000_000_000_000L)
        val supplied = attempts.execute { it }
        assertThat(supplied).isAtMost(System.nanoTime() + 300_000_000_000L)
        assertThat(supplied).isAtLeast(before + 300_000_000_000L)
    }

    @Test
    fun oversizedTerminalBatchFailsInsteadOfBecomingAnImputedReward() {
        val game =
            ArenaGame(
                List(4) { .25 },
                List(4) { .5 },
                List(4) { .5 },
                List(4) { PilotReplayScore("p$it", 0, 0, 0, 0, false) },
                12001,
                0,
            )
        assertFailsWith<IllegalArgumentException> { CoordinatedEsAttempts.terminal(game) }
    }

    @Test
    fun replayPermitsTimingDifferenceOnly() {
        val expected = replay()
        val actual = expected.deepCopy()
        (actual["terminal"] as ObjectNode).put("elapsedMillis", 99)
        CoordinatedEsGames.verifyReproduction(expected, actual)
    }

    @Test
    fun replayRejectsChangedAuthorityHistory() {
        val expected = replay()
        val actual = expected.deepCopy()
        (actual["canonical"] as ObjectNode).put("canonicalSha256", "different")
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsGames.verifyReproduction(expected, actual)
        }
    }

    private fun replay(): ObjectNode =
        PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to "coordinated-es-game-1",
                "role" to "PLUS-00",
                "globalIndex" to 0,
                "canonical" to mapOf("replayVerified" to true, "canonicalSha256" to "constructed"),
                "terminal" to mapOf("elapsedMillis" to 1, "rewards" to listOf(.5, .5, .5, .5)),
            )
        )
}
