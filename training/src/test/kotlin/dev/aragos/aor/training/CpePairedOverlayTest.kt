package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CpePairedOverlayTest {
    @Test
    fun actorOnlyEvolutionCannotChangeAnyFrozenValueHead() {
        CpePairedFixture().use { fixture ->
            val original = fixture.input.original.models
            val changed = original.copy(card = original.card.copy(valueBias = .25))
            assertFailsWith<IllegalArgumentException> { changed.validateFrozenValues(original) }
        }
    }

    @Test
    fun absentCompleteBundlePreservesLegacyIdentityAndMetadata() {
        CpePairedFixture().use { fixture ->
            val original = fixture.input.arena
            assertThat(original.copy(cpeBundle = null).identity()).isEqualTo(original.identity())
            assertThat(original.overlayMetadata()).doesNotContainKey("cpeBundle")
        }
    }

    @Test
    fun optionalCompleteBundleWrapsOnlyTheEvaluatedSeat() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val overlay = ArenaCpeBundle.from(input.current, input.original, input.purchaseBaseline)
            val normal =
                input.arena.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    input.arena.candidate,
                    List(4) { 0 },
                )
            val wrapped = overlay.controllers(normal, 0, ArenaSide.CANDIDATE, input.arena.candidate)
            assertThat(wrapped[0]).isNotSameInstanceAs(normal[0])
            (1..3).forEach { assertThat(wrapped[it]).isSameInstanceAs(normal[it]) }
            val decision = fixture.decision(CpeHead.EXPANSION)
            assertThat(requireNotNull(wrapped[0]) { "Fixture seat." }.choose(decision))
                .isEqualTo(
                    requireNotNull(input.controllers(0)[0]) { "Fixture seat." }.choose(decision)
                )
        }
    }

    @Test
    fun nonzeroPurchaseHeadRetainsItsOriginalFrozenF0PriorAndLikelihood() {
        CpePairedFixture(nonzeroCardPurchase = true).use { fixture ->
            fixture.source(CpeHead.PURCHASE)
            val input = fixture.input
            val rows = mutableListOf<CpePairedDecision>()
            val decision = fixture.decision(CpeHead.PURCHASE)
            val expected =
                CardActorController(
                        input.arena.candidate,
                        input.current.models.card,
                        input.current.models.purchase,
                        purchaseBaseline = input.purchaseBaseline.model,
                    )
                    .choose(decision)
            val actual =
                requireNotNull(input.controllers(0, rows::add)[0]) { "Fixture seat." }
                    .choose(decision)
            assertThat(actual).isEqualTo(expected)
            assertThat(input.original.models.purchase.actorWeights.any { it != 0.0 }).isTrue()
            assertThat(rows.single().actorSeed).isEqualTo(PurchasePolicy.actorSeed(decision.seed))
            assertThat(rows.single().currentProbabilities)
                .isEqualTo(rows.single().originalProbabilities)
        }
    }
}
