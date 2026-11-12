package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** UNIT fixtures apply only short purchase prefixes, never full games or learned updates. */
class PurchaseSequenceTeacherTest {
    private val actor = PlayerId("p1")
    private val people = (1..4).map { Player(PlayerId("p$it"), "Synthetic $it") }
    private val acquisitions =
        listOf(
            AdvanceAcquisition(AdvanceId.THE_HEAVENS, 2),
            AdvanceAcquisition(AdvanceId.PATRONAGE, 1),
            AdvanceAcquisition(AdvanceId.CARAVAN, 1),
            AdvanceAcquisition(AdvanceId.OVERLAND_EAST, 1),
            AdvanceAcquisition(AdvanceId.URBAN_ASCENDANCY, 1),
        )

    private fun state(
        cash: Int = 90,
        advances: List<AdvanceAcquisition> = acquisitions,
    ): GameState =
        GameState(
            header = GameHeader(people, actor),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(people.map(Player::id)),
            deck = CardData.epochOne.map { it.id },
            flow =
                TurnFlow(
                    actor = actor,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
            players =
                people
                    .mapIndexed { index, person ->
                        person.id to
                            PlayerState(
                                player = person,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = cash,
                                misery = MiseryStep.HUNDRED,
                                advances =
                                    if (person.id == actor) {
                                        PlayerAdvances(advances)
                                    } else {
                                        PlayerAdvances()
                                    },
                                hand =
                                    listOf(
                                        HandCard("hand-${index + 1}", CardData.epochOne.first())
                                    ),
                            )
                    }
                    .toMap(),
        )

    @Test
    fun rootRequiresTheActualIncumbentPass() {
        val state = state()
        val observation = BotObservations.from(state, actor)
        assertThat(
                PurchaseSequenceEligibility.exclusion(
                    state.viewForPlayer(actor),
                    observation,
                    "pass",
                )
            )
            .isNull()
        assertThat(
                PurchaseSequenceEligibility.exclusion(
                    state.viewForPlayer(actor),
                    observation,
                    "buy-advance N",
                )
            )
            .isEqualTo(PurchaseRootExclusion.INCUMBENT_CONTINUES)
    }

    @Test
    fun lastTurnRatingDoesNotQualifyAsCurrentMr() {
        val state = state(advances = acquisitions.map { it.copy(turnNumber = 1) })
        assertThat(
                PurchaseSequenceEligibility.exclusion(
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                    "pass",
                )
            )
            .isEqualTo(PurchaseRootExclusion.NO_POSITIVE_MR)
    }

    @Test
    fun unavailableCategoryCompletionDoesNotQualify() {
        val state = state(cash = 0)
        assertThat(
                PurchaseSequenceEligibility.exclusion(
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                    "pass",
                )
            )
            .isEqualTo(PurchaseRootExclusion.NO_OFFERED_COMPLETION)
    }

    @Test
    fun twoMissingCategoriesDoNotQualify() {
        val state = state(advances = acquisitions.filter { it.advance != AdvanceId.PATRONAGE })
        assertThat(
                PurchaseSequenceEligibility.exclusion(
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                    "pass",
                )
            )
            .isEqualTo(PurchaseRootExclusion.NOT_ONE_MISSING_CATEGORY)
    }

    @Test
    fun proposalsRetainIncumbentAndPrioritizeActuallyOfferedCompletion() {
        val state = state()
        val proposals =
            PurchaseSequencePlans.propose(state, actor, BotObservations.from(state, actor))
        assertThat(proposals.plans.first().commands).containsExactly("pass")
        assertThat(proposals.selectedFirstActions.first()).isEqualTo("buy-advance N")
        assertThat(proposals.plans.size).isAtMost(8)
        assertThat(proposals.plans.map { it.commands }.distinct()).hasSize(proposals.plans.size)
        assertThat(proposals.truncatedTwoAdvancePrefixes).isGreaterThan(0)
    }

    @Test
    fun eachProposedChildUsesFreshAuthoritativeOffers() {
        val state = state()
        val proposals =
            PurchaseSequencePlans.propose(state, actor, BotObservations.from(state, actor))
        for (plan in proposals.plans) {
            for (index in plan.commands.indices) {
                val preview =
                    ActivityPlanning.preview(
                        state,
                        actor,
                        plan.commands.take(index).map(PurchaseSequencePlans::action),
                    )
                val observation =
                    BotObservations.from(
                        preview.game,
                        preview.choices,
                        actor,
                        PurchaseSequenceProtocol.DECISION,
                    )
                assertThat(observation.candidates.map { it.command }).contains(plan.commands[index])
            }
        }
    }

    @Test
    fun hiddenOpponentCardsAndDeckOrderDoNotChangeProposals() {
        val original = state()
        val changed =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == actor) {
                            player
                        } else {
                            player.copy(
                                hand =
                                    listOf(HandCard("other-${id.value}", CardData.epochOne.last()))
                            )
                        }
                    },
            )
        val first =
            PurchaseSequencePlans.propose(original, actor, BotObservations.from(original, actor))
        val second =
            PurchaseSequencePlans.propose(changed, actor, BotObservations.from(changed, actor))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun firstActionControlDoesNotForceTheSubsequentPass() {
        val session = ExperimentSession(state(), 11L)
        val result =
            PurchaseSequenceExecution.force(
                session,
                actor,
                42,
                listOf("buy-advance N"),
                Long.MAX_VALUE,
            )
        assertThat(result.stop).isEqualTo(PurchaseSequenceStop.PREFIX_COMPLETE)
        assertThat(session.observation(actor).decision).isEqualTo("purchase-advances")
        assertThat(result.steps.map { it.number }).containsExactly(42)
    }

    @Test
    fun completePrefixStopsBeforeNormalStabilization() {
        val session = ExperimentSession(state(), 11L)
        val result =
            PurchaseSequenceExecution.force(
                session,
                actor,
                42,
                listOf("buy-advance N", "pass"),
                Long.MAX_VALUE,
            )
        assertThat(session.observation(actor).decision).isEqualTo("purchase-stabilization")
        assertThat(result.steps.map { it.number }).containsExactly(42, 43).inOrder()
        assertThat(result.steps.all { it.forced }).isTrue()
    }

    @Test
    fun changedChildOfferStopsWithoutApplyingTheUnavailableCommand() {
        val session = ExperimentSession(state(), 11L)
        val result =
            PurchaseSequenceExecution.force(
                session,
                actor,
                42,
                listOf("buy-advance N", "buy-advance N"),
                Long.MAX_VALUE,
            )
        assertThat(result.stop).isEqualTo(PurchaseSequenceStop.CHANGED_OFFER)
        assertThat(session.decisions).isEqualTo(1)
        assertThat(result.steps).hasSize(1)
    }

    @Test
    fun changedDecisionStopsBeforeFollowingCommands() {
        val session = ExperimentSession(state(), 11L)
        val result =
            PurchaseSequenceExecution.force(
                session,
                actor,
                42,
                listOf("pass", "buy-advance N"),
                Long.MAX_VALUE,
            )
        assertThat(result.stop).isEqualTo(PurchaseSequenceStop.CHANGED_DECISION)
        assertThat(session.observation(actor).decision).isEqualTo("purchase-stabilization")
        assertThat(session.decisions).isEqualTo(1)
    }

    @Test
    fun immediateMasterArtDecisionStopsForcedPurchaseCommands() {
        val initial =
            state(
                    cash = 200,
                    advances = acquisitions + AdvanceAcquisition(AdvanceId.PRINTED_WORD, 1),
                )
                .copy(rules = RuleOptions(immediateMasterArtDiscard = true))
        val session = ExperimentSession(initial, 11L)
        val result =
            PurchaseSequenceExecution.force(
                session,
                actor,
                42,
                listOf("buy-advance P", "pass"),
                Long.MAX_VALUE,
            )
        assertThat(result.stop).isEqualTo(PurchaseSequenceStop.CHANGED_DECISION)
        assertThat(session.observation(actor).decision).isEqualTo("immediate-master-art-discard")
        assertThat(result.steps).hasSize(1)
    }

    @Test
    fun threeAdvancesExceedTheIntrinsicPlanLimit() {
        assertFailsWith<IllegalArgumentException> {
            PurchaseSequencePlan(
                listOf("buy-advance B", "buy-advance C", "buy-advance D"),
                dev.aragos.aor.PlanBoundary.INCOMPLETE,
                "purchase-advances",
            )
        }
    }

    @Test
    fun forcedPlansCannotContainStabilizationOrRelief() {
        assertFailsWith<IllegalArgumentException> {
            PurchaseSequencePlan(
                listOf("stabilize misery"),
                dev.aragos.aor.PlanBoundary.INCOMPLETE,
                "purchase-relief",
            )
        }
    }

    @Test
    fun streamCountIsOwnedByTheOrigin() {
        assertFailsWith<IllegalArgumentException> {
            PurchaseSequenceOrigin(1, 0, List(4) { 0 }, listOf(10L))
        }
        assertFailsWith<IllegalArgumentException> {
            PurchaseSequenceOrigin(1, 0, List(4) { 0 }, listOf(10L, 10L))
        }
        assertThat(PurchaseSequenceProtocol.MAXIMUM_TAILS).isEqualTo(1024)
        assertThat(PurchaseSequenceProtocol.MAXIMUM_TERMINALS).isEqualTo(1056)
    }

    @Test
    fun defaultContinuationMatchesExplicitZeroOffset() {
        val session = ExperimentSession(state(), 11L)
        val observations = mapOf(actor to session.observation(actor))
        val legacy =
            FrozenPolicyContinuation(List(4) { BotModels.default }, emptyMap(), 31, Long.MAX_VALUE)
        val explicit =
            FrozenPolicyContinuation(
                List(4) { BotModels.default },
                emptyMap(),
                31,
                Long.MAX_VALUE,
                0,
            )
        assertThat(explicit.commands(session, observations))
            .isEqualTo(legacy.commands(session, observations))
    }

    @Test
    fun absoluteOffsetUsesPlayerCommandsAndOneSeedBaseForSealedBatch() {
        val session = ExperimentSession(state().copy(stage = Stage.TOKEN_PURCHASE), 11L)
        val captured = mutableListOf<SimulationDecision>()
        val controller = SimulationController { decision ->
            captured.add(decision)
            "buy-tokens 0"
        }
        val observations = people.associate { it.id to session.observation(it.id) }
        val continuation =
            FrozenPolicyContinuation(
                List(4) { BotModels.default },
                people.indices.associateWith { controller },
                31,
                Long.MAX_VALUE,
                123,
            )
        continuation.commands(session, observations)
        assertThat(captured.map { it.number }).containsExactly(123, 124, 125, 126).inOrder()
        assertThat(captured.map { it.batchStartNumber }).containsExactly(123, 123, 123, 123)
        assertThat(captured.map { it.seed })
            .containsExactlyElementsIn(people.indices.map { simulationPolicySeed(31, 123, it) })
            .inOrder()
    }
}
