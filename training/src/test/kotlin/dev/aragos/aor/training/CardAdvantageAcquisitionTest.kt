package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageAcquisitionTest {
    @Test
    fun independentUnsignedSamplerMatchesSignedSeedGoldens() {
        val seeds = listOf(0, 1, -1, 42, 71, Int.MAX_VALUE, Int.MIN_VALUE, 123456789)
        val acquisitions = seeds.map {
            CardAdvantageAcquisition.select(listOf(0, 2, 4, 7), 2, it, sampled = true)
        }

        assertThat(acquisitions.map { it.sampledActionIndex })
            .containsExactly(4, 7, 0, 4, 7, 0, 4, 0)
            .inOrder()
        assertThat(acquisitions.map { it.probability }.distinct()).containsExactly(1.0 / 3)
        assertThat(acquisitions.map { it.seed }).isEqualTo(seeds)
    }

    @Test
    fun everyNonReferenceProposalHasBroadSamplingCoverage() {
        val counts =
            (0 until 6000)
                .map {
                    CardAdvantageAcquisition.select(listOf(0, 2, 4, 7), 2, it, sampled = true)
                        .sampledActionIndex
                }
                .groupingBy { it }
                .eachCount()

        assertThat(counts.keys).containsExactly(0, 4, 7)
        counts.values.forEach { assertThat(it).isIn(1800..2200) }
    }

    @Test
    fun trivialProposalRemainsAnExplicitObservedReference() {
        val acquisition = CardAdvantageAcquisition.select(listOf(9), 9, 71, sampled = true)

        assertThat(acquisition.sampledActionIndex).isNull()
        assertThat(acquisition.probability).isEqualTo(1.0)
        assertThat(acquisition.observedIndices(listOf(9), 9)).containsExactly(9)
    }

    @Test
    fun exhaustiveAcquisitionRetainsCanonicalFullProposals() {
        val proposals = listOf(0, 2, 4, 7)
        val acquisition = CardAdvantageAcquisition.select(proposals, 4, 71, sampled = false)

        assertThat(acquisition.sampledActionIndex).isNull()
        assertThat(acquisition.probability).isEqualTo(1.0)
        assertThat(acquisition.observedIndices(proposals, 4)).isEqualTo(proposals)
    }

    @Test
    fun invalidRootPairPlansFailAtTheirOwner() {
        assertFailsWith<IllegalArgumentException> {
            CardRootPairCollection("unknown", emptyList(), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CardRootPairCollection("root-pairs-1", listOf("train", "train"), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CardRootPairCollection("root-pairs-1", listOf("unknown"), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CardRootPairCollection("root-pairs-1", listOf("train"), 2)
        }
        assertFailsWith<IllegalArgumentException> {
            CardRootPairCollection("root-pairs-1", emptyList(), 3)
        }
    }

    @Test
    fun twoStreamsAreAllowedForUniformlyDenseRoots() {
        val plan = CardRootPairCollection("root-pairs-1", emptyList(), 2)

        assertThat(plan.replicates).isEqualTo(2)
        assertThat(plan.sampledSplits).isEmpty()
    }
}
