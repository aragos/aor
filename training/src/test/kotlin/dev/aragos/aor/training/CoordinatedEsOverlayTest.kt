package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedEsOverlayTest {
    @Test
    fun zeroRolePreservesAllFourActualReferenceFactories() {
        CoordinatedEsFixture().use { fixture ->
            val raw = fixture.input.arena
            val zero =
                raw.copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.zero(
                            fixture.original,
                            fixture.input.purchaseBaseline,
                        )
                )
            for (head in CoordinatedEsHead.entries) {
                val decision = fixture.decision(head)
                val expected =
                    requireNotNull(
                            raw.controllers(
                                    4,
                                    0,
                                    ArenaSide.CANDIDATE,
                                    raw.candidate,
                                    List(4) { 0 },
                                )[0]
                        ) {
                            "Reference fixture seat."
                        }
                        .choose(decision)
                val actual =
                    requireNotNull(
                            zero
                                .controllers(
                                    4,
                                    0,
                                    ArenaSide.CANDIDATE,
                                    raw.candidate,
                                    List(4) { 0 },
                                )[0]
                        ) {
                            "Zero fixture seat."
                        }
                        .choose(decision)
                assertThat(actual).isEqualTo(expected)
            }
            assertThat(fixture.original.models.card.actorWeights.any { it != 0.0 }).isTrue()
            assertThat(fixture.original.models.purchase.actorWeights.any { it != 0.0 }).isTrue()
        }
    }

    @Test
    fun sealedTokenBatchUsesEachOriginalSeatAndOffset() {
        CoordinatedEsFixture().use { fixture ->
            val raw = fixture.input.arena
            val zero =
                raw.copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.zero(
                            fixture.original,
                            fixture.input.purchaseBaseline,
                        )
                )
            for (seat in 0..3) {
                val decision = fixture.decision(CoordinatedEsHead.TOKEN, seat, 71)
                assertThat(decision.number).isEqualTo(71 + seat)
                assertThat(decision.batchStartNumber).isEqualTo(71)
                val expected =
                    requireNotNull(
                            raw.controllers(
                                    4,
                                    seat,
                                    ArenaSide.CANDIDATE,
                                    raw.candidate,
                                    List(4) { 0 },
                                )[seat]
                        ) {
                            "Reference seat."
                        }
                        .choose(decision)
                val actual =
                    requireNotNull(
                            zero
                                .controllers(
                                    4,
                                    seat,
                                    ArenaSide.CANDIDATE,
                                    raw.candidate,
                                    List(4) { 0 },
                                )[seat]
                        ) {
                            "Zero seat."
                        }
                        .choose(decision)
                assertThat(actual).isEqualTo(expected)
            }
        }
    }

    @Test
    fun overlayChangesOnlyTheEvaluatedController() {
        CoordinatedEsFixture().use { fixture ->
            val arena = fixture.input.arena
            val original =
                arena.controllers(4, 2, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
            val role = ArenaCoordinatedPolicy.zero(fixture.original, fixture.input.purchaseBaseline)
            val actual = role.controllers(original, 2, ArenaSide.CANDIDATE, arena.candidate)
            assertThat(actual[2]).isNotSameInstanceAs(original[2])
            listOf(0, 1, 3).forEach { seat ->
                assertThat(actual[seat]).isSameInstanceAs(original[seat])
            }
            assertThat(role.controllers(original, 2, ArenaSide.INCUMBENT, arena.candidate))
                .isSameInstanceAs(original)
        }
    }

    @Test
    fun absentRoleLeavesOldIdentityAndWireExact() {
        CoordinatedEsFixture().use { fixture ->
            val arena = fixture.input.arena
            assertThat(arena.copy(coordinatedEs = null).identity()).isEqualTo(arena.identity())
            assertThat(arena.overlayMetadata()).doesNotContainKey("coordinatedEs")
        }
    }

    @Test
    fun constructedContainersCannotPassScientificOriginalPins() {
        CoordinatedEsFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.original.requireFrozenContainers() }
        }
    }

    @Test
    fun legacyCollectionRejectsTheNewRoleBeforeDecodingIt() {
        CoordinatedEsFixture().use { fixture ->
            val path = fixture.prior.prior.prior.arenaSource
            val arena = PurchaseJson.read(path) as ObjectNode
            arena.putObject("coordinatedEs")
            fixture.files.write("arena.json", arena)
            assertFailsWith<IllegalArgumentException> { fixture.prior.prior.input }
        }
    }
}
