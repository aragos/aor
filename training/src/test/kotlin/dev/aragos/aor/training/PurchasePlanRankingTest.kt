package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.CardData
import dev.aragos.aor.HandCard
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchasePlanRankingTest {
    private val owner = PlayerId("p1")
    private val seeds = (1L..6L).toList()
    private val pass =
        object : PurchasePlanPolicy {
            override fun normal(decision: SimulationDecision): String =
                when (decision.observation.decision) {
                    "purchase-stabilization" ->
                        if (
                            decision.observation.candidates.any { it.command == "stabilize cash" }
                        ) {
                            "stabilize cash"
                        } else {
                            "stabilize misery"
                        }
                    "purchase-relief" -> "pass"
                    else -> "pass"
                }

            override fun sampled(decision: SimulationDecision, uniform: Double): String =
                normal(decision)
        }

    private fun state() =
        jointStateFixture().let { initial ->
            initial.copy(
                stage = Stage.PURCHASE,
                flow = initial.flow.copy(purchaseStep = PurchaseStep.ADVANCES),
                players =
                    initial.players.mapValues { (_, player) -> player.copy(hand = emptyList()) },
            )
        }

    private fun plans(policy: PurchasePlanPolicy = pass) =
        PurchasePlanProposals(policy, Long.MAX_VALUE).propose(state(), 0, 123, 41, "pass", seeds)

    private fun obligationState() =
        state().let { current ->
            current.copy(
                players =
                    current.players.mapValues { (id, player) ->
                        if (id == owner) {
                            player.copy(
                                hand = List(3) { HandCard("held-$it", CardData.epochOne[it]) }
                            )
                        } else {
                            player
                        }
                    }
            )
        }

    @Test
    fun logicalBankHasFixedOrderAndRetainsExactDuplicateAliases() {
        val plans = plans()
        assertThat(plans.map { it.kind })
            .containsExactly(
                PurchasePlanKind.INCUMBENT,
                PurchasePlanKind.ADVANCE_PASS,
                PurchasePlanKind.SAMPLED,
                PurchasePlanKind.SAMPLED,
                PurchasePlanKind.SAMPLED,
                PurchasePlanKind.UNIFORM,
                PurchasePlanKind.UNIFORM,
                PurchasePlanKind.UNIFORM,
            )
            .inOrder()
        assertThat(plans[1].aliasOf).isEqualTo(0)
        assertThat(plans.all { it.actions.size <= 64 }).isTrue()
    }

    @Test
    fun completedLeafUsesTheAuthoritativeOwnerBoundary() {
        val plan = plans().first()
        val preview = ActivityPlanning.preview(state(), owner, plan.actions)
        assertThat(plan.status).isEqualTo(PurchasePlanStatus.SUPPORTED)
        assertThat(plan.leaf?.stateFeatures)
            .isEqualTo(
                AfterstateValueEncoder.from(
                    preview.game,
                    owner,
                    AfterstateContext.OWNER_EXIT,
                )
            )
    }

    @Test
    fun completeProjectedCommandsExecuteWithIdenticalOwnResources() {
        val plan = plans().first()
        val actual =
            plan.actions.fold(state()) { current, action ->
                current.step(TextCodec.parseAction(action.command, current, actor = owner))
            }
        val preview = ActivityPlanning.preview(state(), owner, plan.actions).game
        assertThat(actual.playerState(owner).cash).isEqualTo(preview.players.first().cash)
        assertThat(actual.playerState(owner).misery).isEqualTo(preview.players.first().misery)
        assertThat(actual.playerState(owner).advances.owned)
            .isEqualTo(preview.players.first().advances)
    }

    @Test
    fun policySeedUsesOriginalAbsoluteCommandNumberNotProposalStream() {
        val seen = mutableListOf<SimulationDecision>()
        val recording =
            object : PurchasePlanPolicy {
                override fun normal(decision: SimulationDecision): String {
                    seen.add(decision)
                    return pass.normal(decision)
                }

                override fun sampled(decision: SimulationDecision, uniform: Double): String {
                    seen.add(decision)
                    return pass.normal(decision)
                }
            }
        plans(recording)
        assertThat(seen.all { it.seed == simulationPolicySeed(123, it.number, 0) }).isTrue()
        assertThat(seen.map { it.number }).contains(41)
        assertThat(seen.all { it.batchStartNumber == it.number }).isTrue()
    }

    @Test
    fun rivalHandIdentityAndDeckOrderCannotAlterPublicPlans() {
        val original = state().copy(deck = CardData.epochOne.map { it.id })
        val hidden =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == owner) {
                            player
                        } else {
                            player.copy(
                                hand = listOf(HandCard("private", CardData.epochOne.last()))
                            )
                        }
                    },
            )
        val equivalent =
            hidden.copy(
                players =
                    hidden.players.mapValues { (id, player) ->
                        if (id == owner) {
                            player
                        } else {
                            player.copy(hand = listOf(HandCard("other", CardData.epochOne.first())))
                        }
                    },
                deck = original.deck,
            )
        val proposer = PurchasePlanProposals(pass, Long.MAX_VALUE)
        assertThat(proposer.propose(hidden, 0, 123, 41, "pass", seeds))
            .isEqualTo(proposer.propose(equivalent, 0, 123, 41, "pass", seeds))
    }

    @Test
    fun rootControllerMismatchIsRejectedBeforeAnyTail() {
        assertFailsWith<IllegalArgumentException> {
            PurchasePlanProposals(pass, Long.MAX_VALUE)
                .propose(
                    state(),
                    0,
                    123,
                    41,
                    "buy-advance written-record",
                    seeds,
                )
        }
    }

    @Test
    fun harmfulNaturalChaosRemainsAnExactSupportedLeaf() {
        val initial =
            state().let { state ->
                state.copy(
                    players =
                        state.players.mapValues { (id, player) ->
                            if (id != owner) {
                                player
                            } else {
                                player.copy(
                                    cash = 30,
                                    misery = MiseryStep.entries[MiseryStep.entries.size - 2],
                                    hand = List(3) { HandCard("held-$it", CardData.epochOne[it]) },
                                )
                            }
                        }
                )
            }
        val misery =
            object : PurchasePlanPolicy {
                override fun normal(decision: SimulationDecision): String =
                    if (decision.observation.decision == "purchase-stabilization") {
                        "stabilize misery"
                    } else {
                        "pass"
                    }

                override fun sampled(decision: SimulationDecision, uniform: Double): String =
                    normal(decision)
            }
        val result =
            PurchasePlanProposals(misery, Long.MAX_VALUE)
                .propose(initial, 0, 123, 41, "pass", seeds)
        assertThat(result[0].leaf?.exactReason).isEqualTo("OWN_CHAOS")
        assertThat(result[0].leaf?.exactValue).isEqualTo(0.0)
        assertThat(result[0].status).isEqualTo(PurchasePlanStatus.SUPPORTED)
    }

    @Test
    fun projectedCommandsRemainActualOfferedChoicesAtEveryPrefix() {
        val initial = state()
        for (plan in plans()) {
            plan.actions.forEachIndexed { index, action ->
                val preview = ActivityPlanning.preview(initial, owner, plan.actions.take(index))
                assertThat(preview.decision).isEqualTo(action.decision)
                assertThat(preview.choices.commands).contains(action.command)
            }
        }
    }

    @Test
    fun defaultContinuationRetainsOriginalPolicySeed() {
        val state = state()
        val observation = BotObservations.from(state, owner)
        val seen = mutableListOf<SimulationDecision>()
        val controller = SimulationController { decision ->
            seen.add(decision)
            "pass"
        }
        val models = List(4) { BotModels.default }
        FrozenPolicyContinuation(models, mapOf(0 to controller), 123, Long.MAX_VALUE)
            .commands(ExperimentSession(state, 999L), mapOf(owner to observation))
        assertThat(seen.single().seed).isEqualTo(123 xor (0 * 65537 + 0 * 8191))
        assertThat(seen.single().number).isEqualTo(0)
    }

    @Test
    fun forkContinuationRetainsAbsolutePolicyOffset() {
        val state = state()
        val seen = mutableListOf<SimulationDecision>()
        val controller = SimulationController { decision ->
            seen.add(decision)
            "pass"
        }
        FrozenPolicyContinuation(
                List(4) { BotModels.default },
                mapOf(0 to controller),
                123,
                Long.MAX_VALUE,
                41,
            )
            .commands(
                ExperimentSession(state, 999L),
                mapOf(owner to BotObservations.from(state, owner)),
            )
        assertThat(seen.single().number).isEqualTo(41)
        assertThat(seen.single().seed).isEqualTo(simulationPolicySeed(123, 41, 0))
    }

    @Test
    fun sealedBatchUsesOneAbsoluteSeedOffsetForAllSeats() {
        val state = state().copy(stage = Stage.TOKEN_PURCHASE)
        val session = ExperimentSession(state, 999L)
        val seen = mutableListOf<SimulationDecision>()
        val controllers =
            (0 until 4).associateWith {
                SimulationController { decision ->
                    seen.add(decision)
                    decision.observation.candidates.first().command
                }
            }
        FrozenPolicyContinuation(
                List(4) { BotModels.default },
                controllers,
                123,
                Long.MAX_VALUE,
                41,
            )
            .commands(session, session.actors().associateWith(session::observation))
        assertThat(seen.map { it.number }).containsExactly(41, 42, 43, 44).inOrder()
        assertThat(
                seen.all {
                    it.batchStartNumber == 41 && it.seed == simulationPolicySeed(123, 41, it.seat)
                }
            )
            .isTrue()
    }

    @Test
    fun sampledProposalChangesOnlyTheDrawOfTheOriginalPurchaseActor() {
        JointStateFixture().use { fixture ->
            val input = fixture.input
            val state = state()
            val controller =
                input.bundle.controller(input.purchaseBaseline, ordinary = input.baseline.model)
            val policy =
                FrozenPurchasePlanPolicy(
                    input.baseline.model,
                    controller,
                    input.purchaseBaseline.model,
                    input.bundle.purchase.model,
                )
            val observation = BotObservations.from(state, owner)
            val decision =
                SimulationDecision(41, 0, 71, state.viewForPlayer(owner), observation, 41)
            val recommendation =
                BotPolicy.choose(
                    observation,
                    BotDifficulty.HARD,
                    71,
                    input.purchaseBaseline.model,
                )
            val projected =
                PurchaseObservationEncoder.from(
                    decision.view,
                    owner,
                    observation,
                    recommendation,
                )
            assertThat(policy.sampled(decision, .371))
                .isEqualTo(
                    PurchasePolicy.choose(
                            projected,
                            input.bundle.purchase.model,
                            .371,
                        )
                        .command
                )
            assertThat(policy.normal(decision)).isEqualTo(controller.choose(decision))
        }
    }

    @Test
    fun executionRefusesAnAlteredFrozenEndpointBeforeContinuing() {
        val plan = plans().first()
        val changed =
            plan.copy(
                leaf =
                    requireNotNull(plan.leaf) { "The pass fixture requires a leaf." }
                        .let { leaf ->
                            leaf.copy(
                                stateFeatures =
                                    leaf.stateFeatures + ("purchase.role.0.cash" to -1.0)
                            )
                        }
            )
        assertFailsWith<IllegalArgumentException> {
            PurchasePlanExecution.force(
                ExperimentSession(state(), 999L),
                owner,
                changed,
                Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun completeLiveForcingMatchesItsFrozenPublicLeaf() {
        val session = ExperimentSession(state(), 999L)
        val plan = plans().first()
        PurchasePlanExecution.force(session, owner, plan, Long.MAX_VALUE)
        assertThat(session.decisions).isEqualTo(plan.actions.size)
        assertThat(session.state.flow.actor).isNotEqualTo(owner)
    }

    @Test
    fun completeIncumbentMatchesEveryCanonicalOwnerCommand() {
        val initial = obligationState()
        val plan =
            PurchasePlanProposals(pass, Long.MAX_VALUE)
                .propose(initial, 0, 123, 41, "pass", seeds)
                .first()
        assertThat(plan.actions.size).isAtLeast(2)
        val entries = plan.actions.map { "${owner.value}: ${it.command}" }
        PurchasePlanReference.verify(initial, owner, plan, entries, 0, Long.MAX_VALUE)
    }

    @Test
    fun laterIncumbentCommandMismatchFailsInsteadOfExcludingTheRoot() {
        val initial = obligationState()
        val plan =
            PurchasePlanProposals(pass, Long.MAX_VALUE)
                .propose(initial, 0, 123, 41, "pass", seeds)
                .first()
        val entries =
            plan.actions.mapIndexed { index, action ->
                "${owner.value}: ${if (index == 1) "stabilize misery" else action.command}"
            }
        assertFailsWith<IllegalArgumentException> {
            PurchasePlanReference.verify(initial, owner, plan, entries, 0, Long.MAX_VALUE)
        }
    }

    @Test
    fun exhaustedAbsoluteDecisionAllowanceRetainsAnUnsupportedPlan() {
        val initial = obligationState()
        val plan =
            PurchasePlanProposals(pass, Long.MAX_VALUE)
                .propose(initial, 0, 123, 11999, "pass", seeds)
                .first()
        assertThat(plan.status).isEqualTo(PurchasePlanStatus.UNSUPPORTED)
        assertThat(plan.exclusion).isEqualTo(PurchasePlanExclusion.CHOICE_CAP)
    }
}
