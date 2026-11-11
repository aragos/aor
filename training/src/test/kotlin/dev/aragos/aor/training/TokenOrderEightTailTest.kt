package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderEightTailTest {
    @Test
    fun existingProtocolRejectsEightStreams() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit { put("continuationReplicates", 8) }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun eightTailProtocolRejectsTwoStreams() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit { put("continuationProtocol", "ARENA_OVERLAYS_EIGHT_TAILS_1") }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun eightTailReproductionRetainsCompleteProspectiveSkips() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                put("continuationProtocol", "ARENA_OVERLAYS_EIGHT_TAILS_1")
                put("continuationReplicates", 8)
            }
            val expected = fixture.completed()

            val actual = TokenOrderReproduction.read(fixture.directory)

            assertThat(actual.specification.identity()).isEqualTo(expected.identity())
        }
    }

    @Test
    fun eightStreamsPreserveTheFirstTwoCompleteTerminalOutcomes() {
        TokenOrderOverlayFixture().use { fixture ->
            val original = fixture.specification
            val seed =
                (71..200).first { candidate ->
                    original
                        .copy(arena = original.arena.copy(seed = candidate))
                        .request(0)
                        .ordinal == 1
                }
            val two = original.copy(arena = original.arena.copy(seed = seed))
            val eight =
                two.copy(
                    continuationReplicates = 8,
                    continuationProtocol =
                        TokenOrderContinuationProtocol.ARENA_OVERLAYS_EIGHT_TAILS_1,
                )
            val expected = TokenOrderCollector(two, "fixture", Long.MAX_VALUE).collect(0)

            val actual = TokenOrderCollector(eight, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.status).isEqualTo(TokenValueOriginStatus.COLLECTED)
            assertThat(actual.alternatives.orEmpty().map { it.outcomes.size })
                .containsExactlyElementsIn(List(actual.alternatives.orEmpty().size) { 8 })
            assertThat(
                    actual.copy(
                        alternatives =
                            actual.alternatives.orEmpty().map {
                                it.copy(outcomes = it.outcomes.take(2))
                            }
                    )
                )
                .isEqualTo(expected)
            TokenOrderCollectionMain.validateOrigin(
                PurchaseJson.mapper.valueToTree(actual),
                eight,
                0,
                "fixture",
            )
        }
    }
}
