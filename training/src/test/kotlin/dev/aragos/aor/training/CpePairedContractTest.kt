package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.CardData
import dev.aragos.aor.HandCard
import dev.aragos.aor.ai.CardAction
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardPolicy
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CpePairedContractTest {
    @Test
    fun sampleReferenceNeverRewritesTheObservationPrior() {
        val observation =
            CpePairedObservation.Card(
                CardObservation(
                    stateFeatures = emptyMap(),
                    actions =
                        listOf(CardAction("pass", emptyMap()), CardAction("play held", emptyMap())),
                    baselineIndex = 0,
                )
            )
        val frame =
            CpePairedDecision(
                CpeHead.CARD,
                9,
                9,
                71,
                CardPolicy.actorSeed(71),
                observation,
                1,
                ln(.3),
                .4,
                listOf(.7, .3),
                listOf(.8, .2),
            )
        val root = CpePairedPublicRoot.from(frame, 17)
        assertThat(root.referenceIndex).isEqualTo(1)
        assertThat(root.sampledCommandIndex).isEqualTo(1)
        assertThat(root.observation.baselineIndex).isEqualTo(0)
        assertThat(root.proposedIndices).containsExactly(1, 0).inOrder()
    }

    @Test
    fun reservoirUsesOnlyAssignedBranchingWindowsAndKeepsLateWindowsEligible() {
        val request = CpePairedRequest(CpeHead.PURCHASE, 0)
        val reservoir = CpePairedReservoir(request, 512)
        val expected = Random(512)
        val replaced =
            (1..80).filter { ordinal ->
                reservoir.observe(2, "purchase-advances", 3)
                reservoir.observe(1, "play-cards", 3)
                reservoir.observe(1, "purchase-shipping", 1)
                val actual = reservoir.observe(1, "purchase-advances", 7)
                assertThat(actual).isEqualTo(expected.nextInt(ordinal) == 0)
                actual
            }
        assertThat(reservoir.observedWindows).isEqualTo(160)
        assertThat(reservoir.branchingWindows).isEqualTo(80)
        assertThat(reservoir.selectedOrdinal).isEqualTo(replaced.last())
    }

    @Test
    fun singletonOnlyWorldNeverInventsABranchingRoot() {
        val reservoir = CpePairedReservoir(CpePairedRequest(CpeHead.CARD, 0), 7)
        repeat(4) { assertThat(reservoir.observe(1, "play-cards", 1)).isFalse() }
        assertThat(reservoir.selectedOrdinal).isNull()
        assertThat(reservoir.branchingWindows).isEqualTo(0)
    }

    @Test
    fun complete1024OffersRetainUniformNonreferenceInclusion() {
        val proposed = CpePairedProposals.sample(1024, 917, 71)
        assertThat(proposed.indices.first()).isEqualTo(917)
        assertThat(proposed.indices).hasSize(9)
        assertThat(proposed.indices.distinct()).isEqualTo(proposed.indices)
        assertThat(proposed.indices.drop(1)).isInOrder()
        assertThat(proposed.inclusionProbability).isEqualTo(8.0 / 1023)
    }

    @Test
    fun streamKindsCannotAlias() {
        assertFailsWith<IllegalArgumentException> { CpePairedSeeds(1, 2, 3, listOf(4, 2)) }
    }

    @Test
    fun commonBundleScopeSurvivesHeadAndShardChangesButHeadScopeDoesNot() {
        CpePairedFixture().use { fixture ->
            val expansion = fixture.input
            fixture.source(CpeHead.CARD, 512)
            val card = fixture.input
            assertThat(expansion.bundleScopeSha256).isEqualTo(card.bundleScopeSha256)
            assertThat(expansion.scopeSha256).isNotEqualTo(card.scopeSha256)
            fixture.source(CpeHead.CARD, 576)
            val next = fixture.input
            assertThat(card.scienceSha256).isEqualTo(next.scienceSha256)
            assertThat(card.signature).isNotEqualTo(next.signature)
        }
    }

    @Test
    fun wrongDictionaryIsRejectedBeforePublishingABundle() {
        CpePairedFixture().use { fixture ->
            val node = PurchaseJson.read(fixture.bundle) as ObjectNode
            (node["heads"]["CARD"] as ObjectNode).put("dictionarySha256", "a".repeat(64))
            fixture.files.write("cpe-bundle.json", node)
            fixture.source()
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun legacyTwoHeadReaderRejectsTheNewDescriptor() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            assertFailsWith<IllegalArgumentException> {
                JointActorBundle.read(fixture.bundle, input.configuration, input.purchaseBaseline)
            }
        }
    }

    @Test
    fun expansionControllerMatchesTheExistingOuterSelectorAndRecordsItsExactSeed() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val decision = fixture.decision(CpeHead.EXPANSION)
            val rows = mutableListOf<CpePairedDecision>()
            val reference =
                input.arena.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    input.arena.candidate,
                    List(4) { 0 },
                )
            val expected =
                ExpansionOnPolicyController(
                        requireNotNull(reference[0]) { "Fixture reference." },
                        input.current.models.expansion,
                    )
                    .choose(decision)
            val actual =
                requireNotNull(input.controllers(0, rows::add)[0]) { "Fixture current." }
                    .choose(decision)
            assertThat(actual).isEqualTo(expected)
            assertThat(rows.single().number).isEqualTo(19)
            assertThat(rows.single().batchStartNumber).isEqualTo(19)
            assertThat(rows.single().originalProbabilities)
                .isEqualTo(rows.single().currentProbabilities)
        }
    }

    @Test
    fun cardOriginalProbabilitiesAndChoiceRetainTheQualifiedPriorOwner() {
        CpePairedFixture().use { fixture ->
            fixture.source(CpeHead.CARD)
            val input = fixture.input
            val decision = fixture.decision(CpeHead.CARD)
            val rows = mutableListOf<CpePairedDecision>()
            val expected =
                CardActorController(
                        input.arena.candidate,
                        input.current.models.card,
                        input.current.models.purchase,
                        purchaseBaseline = input.purchaseBaseline.model,
                    )
                    .choose(decision)
            val actual =
                requireNotNull(input.controllers(0, rows::add)[0]) { "Fixture current." }
                    .choose(decision)
            assertThat(actual).isEqualTo(expected)
            assertThat(rows.single().actorSeed).isEqualTo(CardPolicy.actorSeed(decision.seed))
            assertThat(rows.single().originalProbabilities)
                .isEqualTo(rows.single().currentProbabilities)
        }
    }

    @Test
    fun privateRivalCardsAndDeckOrderCannotChangeControlledRecords() {
        CpePairedFixture().use { fixture ->
            fixture.source(CpeHead.CARD)
            val input = fixture.input
            val state = fixture.state(CpeHead.CARD)
            val rival = state.playerIds[1]
            val changed =
                state.copy(
                    deck = state.deck.reversed(),
                    players =
                        state.players +
                            (rival to
                                state
                                    .playerState(rival)
                                    .copy(
                                        hand =
                                            listOf(
                                                HandCard("held", CardData.definition("marco-polo"))
                                            )
                                    )),
                )
            fun choose(position: dev.aragos.aor.GameState): CpePairedDecision {
                val rows = mutableListOf<CpePairedDecision>()
                requireNotNull(input.controllers(0, rows::add)[0]) { "Fixture current." }
                    .choose(fixture.decision(CpeHead.CARD, state = position))
                return rows.single()
            }
            assertThat(choose(state)).isEqualTo(choose(changed))
        }
    }
}
