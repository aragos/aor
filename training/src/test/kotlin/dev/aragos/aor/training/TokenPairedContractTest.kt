package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenPairedContractTest {
    @Test
    fun anchoredLayoutHasAnExplicitSplitAndSmallBoundedShards() {
        val layout = TokenPairedLayout(originCount = 24, trainCount = 16, anchored = true)

        assertThat(TokenPairedRequest(15, layout).split).isEqualTo(TokenPairedSplit.TRAIN)
        assertThat(TokenPairedRequest(16, layout).split).isEqualTo(TokenPairedSplit.VALIDATION)
        assertThat(TokenPairedRange(16, 8, layout).indices.toList())
            .containsExactlyElementsIn(16..23)
    }

    @Test
    fun bothProbeSplitsCoverAllOrdinals() {
        val layout = TokenPairedLayout(originCount = 48, trainCount = 32, anchored = true)
        val requests = (0 until layout.originCount).map { TokenPairedRequest(it, layout) }

        for (split in TokenPairedSplit.entries) {
            val ordinals = requests.filter { it.split == split }.map(TokenPairedRequest::ordinal)
            assertThat(ordinals.toSet()).containsExactly(1, 2, 3, 4)
        }
    }

    @Test
    fun anchoredLayoutCannotRelaxLegacyRequestAndRangeInvariants() {
        assertFailsWith<IllegalArgumentException> { TokenPairedLayout(24, 16) }
        assertFailsWith<IllegalArgumentException> { TokenPairedRange(16, 8) }
        assertFailsWith<IllegalArgumentException> { TokenPairedRequest(640) }
    }

    @Test
    fun layoutParametersNeverBecomePublicObservationOrRequestFeatures() {
        val legacy =
            PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                TokenPairedRequest(3)
            )
        val anchored =
            PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                TokenPairedRequest(3, TokenPairedLayout(24, 16, anchored = true))
            )

        assertThat(legacy).isEqualTo(anchored)
        assertThat(legacy.has("layout")).isFalse()
    }

    @Test
    fun portableEcologySeparatesRelocatedWrappersFromActualHeadIdentity() {
        TokenPairedFixture().use { fixture ->
            val original = fixture.input.policyBindings
            val relocated = original.deepCopy<ObjectNode>()
            val joint = relocated["jointPolicy"] as ObjectNode
            (joint["source"] as ObjectNode).put("sha256", "a".repeat(64))
            (joint["candidateBundle"] as ObjectNode).put("sha256", "b".repeat(64))
            (joint["candidateBundle"] as ObjectNode).put("path", "/different/host/bundle.json")
            assertThat(TokenPairedEcology.portableBindings(relocated))
                .isEqualTo(TokenPairedEcology.portableBindings(original))
        }
    }

    @Test
    fun portableEcologyStillBindsEachActualCardHead() {
        TokenPairedFixture().use { fixture ->
            val original = fixture.input.policyBindings
            val changed = original.deepCopy<ObjectNode>()
            (changed["jointPolicy"]["candidateBundle"]["card"] as ObjectNode).put(
                "sha256",
                "b".repeat(64),
            )
            assertThat(TokenPairedEcology.portableBindings(changed))
                .isNotEqualTo(TokenPairedEcology.portableBindings(original))
        }
    }

    @Test
    fun chunkBoundariesNeverResetTheGlobalRequest() {
        assertThat(TokenPairedRange(512, 64).indices.first).isEqualTo(512)
        assertThat(TokenPairedRequest(512).split).isEqualTo(TokenPairedSplit.VALIDATION)
        assertThat(TokenPairedRequest(511).split).isEqualTo(TokenPairedSplit.TRAIN)
        assertThat(TokenPairedRequest(639).seat).isEqualTo(3)
        assertThat(TokenPairedRequest(639).epoch).isEqualTo(1 + (639 / 4) % 3)
        assertThat(TokenPairedRequest(639).ordinal).isEqualTo(1 + (639 / 12) % 4)
    }

    @Test
    fun fractionalOrOverlappingChunkShapesAreNotAccepted() {
        assertFailsWith<IllegalArgumentException> { TokenPairedRange(1, 64) }
        assertFailsWith<IllegalArgumentException> { TokenPairedRange(0, 65) }
    }

    @Test
    fun fullOffersHaveEightDistinctUniformAlternativesAndAnExactReference() {
        val result = TokenPairedProposals.sample(75, 13, -719)
        assertThat(result.indices).hasSize(9)
        assertThat(result.indices.first()).isEqualTo(13)
        assertThat(result.indices.distinct()).isEqualTo(result.indices)
        assertThat(result.indices.drop(1)).isInOrder()
        assertThat(result.inclusionProbability).isEqualTo(8.0 / 74.0)
        assertThat(TokenPairedProposals.sample(75, 13, -719)).isEqualTo(result)
    }

    @Test
    fun smallOffersHaveCompleteSupportWithoutSamplingDuplicates() {
        val result = TokenPairedProposals.sample(4, 2, 71)
        assertThat(result.indices).containsExactly(2, 0, 1, 3).inOrder()
        assertThat(result.inclusionProbability).isEqualTo(1.0)
    }

    @Test
    fun shardSourcesShareScientificIdentityButRetainDifferentRawSignatures() {
        TokenPairedFixture().use { fixture ->
            val first = fixture.input
            fixture.source(576)
            val last = fixture.input
            assertThat(first.scienceSha256).isEqualTo(last.scienceSha256)
            assertThat(first.signature).isNotEqualTo(last.signature)
            assertThat(first.ownedReplayIndices).containsExactly(0)
            assertThat(last.ownedReplayIndices).containsExactly(639)
        }
    }

    @Test
    fun currentTReferenceIsDifferentFromTheOneSampledTokenCommand() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val decision = fixture.decision()
            val referenceCommand = "buy-tokens 15"
            var calls = 0
            val reference =
                object : SimulationController {
                    override fun controls(decision: String) = true

                    override fun choose(decision: SimulationDecision): String {
                        calls++
                        return referenceCommand
                    }
                }
            val seed =
                (0..10000).first {
                    TerritoryPolicy.uniform(TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, it)) >
                        .999
                }
            val records = mutableListOf<TokenPairedDecision>()
            val command =
                TokenPairedController(reference, input.current, input.original, records::add)
                    .choose(decision.copy(seed = seed))
            val row = records.single()
            assertThat(calls).isEqualTo(1)
            assertThat(row.observation.actions[row.observation.baselineIndex].command)
                .isEqualTo(referenceCommand)
            assertThat(command).isEqualTo(row.sampled.command)
            assertThat(command).isNotEqualTo(referenceCommand)
            assertThat(exp(row.sampled.logProbability))
                .isWithin(1e-12)
                .of(row.currentProbabilities[row.sampled.index])
            assertThat(row.originalProbabilities).isEqualTo(row.currentProbabilities)
        }
    }

    @Test
    fun allSealedSeatsKeepTheCommonBatchSeedAndTheirDistinctAbsoluteNumber() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val records =
                (0..3).map { seat ->
                    val rows = mutableListOf<TokenPairedDecision>()
                    requireNotNull(input.controllers(seat, rows::add)[seat]) {
                            "Controlled token actor is required."
                        }
                        .choose(fixture.decision(seat))
                    rows.single()
                }
            assertThat(records.map { it.batchStartNumber }).containsExactly(19, 19, 19, 19)
            assertThat(records.map { it.number }).containsExactly(19, 20, 21, 22).inOrder()
            assertThat(records.map { it.botSeed })
                .containsExactlyElementsIn((0..3).map { simulationPolicySeed(123, 19, it) })
                .inOrder()
        }
    }
}
