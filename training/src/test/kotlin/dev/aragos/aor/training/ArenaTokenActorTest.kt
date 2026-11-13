package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaTokenActorTest {
    @Test
    fun arenaOuterActorMatchesCollectionAndLeavesAllRivalsUnchanged() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val actor =
                TerritoryActorInput(
                    input.currentActor.path,
                    input.currentActor.sha256,
                    input.current,
                )
            val arena = input.arena.copy(tokenActor = ArenaTokenActor.from(actor, actor))
            val expected = input.controllers(0)
            val actual =
                arena.controllers(4, 0, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
            val decisions = (0..3).map { fixture.decision(it) }
            assertThat((0..3).map { actual[it]?.choose(decisions[it]) })
                .isEqualTo((0..3).map { expected[it]?.choose(decisions[it]) })
        }
    }

    @Test
    fun oldTerritoryBundleStillRejectsNewTokenActorSchema() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val frozen = fixture.prior.base.input.bundle
            assertFailsWith<IllegalArgumentException> {
                TerritoryActorBundle(
                    frozen.path,
                    frozen.sha256,
                    frozen.configuration,
                    frozen.baselineSha256,
                    frozen,
                    TerritoryActorInput(
                        input.currentActor.path,
                        input.currentActor.sha256,
                        input.current,
                    ),
                    null,
                )
            }
        }
    }

    @Test
    fun explicitTokenActorRejectsAnUnspecifiedEvaluationSide() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val actor =
                TerritoryActorInput(
                    input.currentActor.path,
                    input.currentActor.sha256,
                    input.current,
                )
            val arena = input.arena.copy(tokenActor = ArenaTokenActor.from(actor, actor))
            assertFailsWith<IllegalArgumentException> {
                arena.controllers(4, 0, null, arena.candidate, List(4) { 0 })
            }
        }
    }

    @Test
    fun nonTokenDecisionsRemainTheExactExistingReferenceResult() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            var calls = 0
            val reference =
                object : SimulationController {
                    override fun controls(decision: String) = true

                    override fun choose(decision: SimulationDecision): String {
                        calls++
                        return "unchanged-reference"
                    }
                }
            val decision =
                fixture.decision().let {
                    it.copy(observation = it.observation.copy(decision = "choose-turn-position"))
                }
            val result =
                TokenPairedController(reference, input.current, input.original).choose(decision)
            assertThat(result).isEqualTo("unchanged-reference")
            assertThat(calls).isEqualTo(1)
        }
    }
}
