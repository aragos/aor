package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed pair metadata only; no simulator, retained model or historical input access. */
class CoordinatedEsHistoricalPositionTest {
    private val raw =
        ArenaSpecification(
            purpose = ArenaPurpose.SCREENING,
            configurations = listOf(TrainingMatrix.stratified.first()),
            candidate = BotModels.default,
            incumbent = BotModels.default,
            opponents = listOf(BotModels.default),
            pairsPerConfiguration = 512,
            seed = 617,
        )

    @Test
    fun global4095SelectsOriginalLocal511() {
        withPair(511) { pair ->
            val position = position(pair, globalIndex = 4095, index = 511)
            assertThat(position).isEqualTo(CoordinatedEsHistoricalPosition(4095, 511))
        }
    }

    @Test
    fun firstGlobalWorldKeepsLocalZero() {
        withPair(0) { pair ->
            val position = position(pair, globalIndex = 0, index = 0, declared = 0)
            assertThat(position).isEqualTo(CoordinatedEsHistoricalPosition(0, 0))
        }
    }

    @Test
    fun globalIndexCannotBePassedAsSourceLocalIndex() {
        withPair(511) { pair ->
            assertFailsWith<IllegalArgumentException> {
                position(pair, globalIndex = 4095, index = 4095)
            }
        }
    }

    @Test
    fun swappedGlobalIdentityRejectsBeforeExecution() {
        withPair(511) { pair ->
            assertFailsWith<IllegalArgumentException> {
                position(pair, globalIndex = 511, index = 511)
            }
        }
    }

    @Test
    fun recordedPairMustBindTheExactLocalIndex() {
        withPair(510) { pair ->
            assertFailsWith<IllegalArgumentException> {
                position(pair, globalIndex = 4095, index = 511)
            }
        }
    }

    @Test
    fun recordedPairMustBindTheOriginalChunkSeed() {
        withPair(511, seedDelta = 1) { pair ->
            assertFailsWith<IllegalArgumentException> {
                position(pair, globalIndex = 4095, index = 511)
            }
        }
    }

    private fun position(
        pair: StrategyReplayPair,
        globalIndex: Int,
        index: Int,
        declared: Int = 4095,
    ): CoordinatedEsHistoricalPosition =
        CoordinatedEsHistoricalPosition.read(
            ExpansionOnPolicyStore.wire(
                mapOf("globalIndex" to globalIndex, "index" to index, "side" to "candidate")
            ),
            ExpansionOnPolicyStore.wire(mapOf("index" to declared, "side" to "candidate")),
            raw,
            pair,
        )

    private fun withPair(index: Int, seedDelta: Int = 0, operation: (StrategyReplayPair) -> Unit) {
        val lineup = StrategyArena.lineup(raw, index)
        val path = Files.createTempFile("coordinated-es-local-index-unit", ".json")
        try {
            PurchaseJson.mapper.writeValue(
                path.toFile(),
                mapOf(
                    "index" to index,
                    "seed" to lineup.seed + seedDelta,
                    "seat" to lineup.seat,
                    "configuration" to lineup.configuration.id,
                    "opponentIndices" to lineup.opponentIndices,
                ),
            )
            operation(StrategyReplayPair.read(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
