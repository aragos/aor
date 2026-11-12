package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlannedAction
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/** Source-only diagnostic profile; these limits are not a playing-policy qualification. */
internal object PurchaseSequenceProtocol {
    const val VERSION = "purchase-sequence-teacher-1"
    const val ORIGINS = 32
    const val PLANS = 8
    const val FIRST_ACTIONS = 4
    const val STREAMS = 2
    const val MAXIMUM_TAILS = ORIGINS * PLANS * 2 * STREAMS
    const val MAXIMUM_TERMINALS = MAXIMUM_TAILS + ORIGINS
    const val DECISION = "purchase-advances"
}

internal enum class PurchaseRootExclusion {
    WRONG_DECISION,
    INCUMBENT_CONTINUES,
    NO_POSITIVE_MR,
    NO_POSITIVE_MISERY,
    NOT_ONE_MISSING_CATEGORY,
    NO_OFFERED_COMPLETION,
}

/** Eligibility reads only an ordinary actor view and the command actually selected by its actor. */
internal object PurchaseSequenceEligibility {
    fun missingCategory(view: GameView, actor: PlayerId): AdvanceCategory? {
        val owned = view.players.single { it.id == actor }.advances
        return AdvanceCategory.entries.singleOrNull { category ->
            owned.none { AdvanceData.definition(it).category == category }
        }
    }

    fun exclusion(
        view: GameView,
        observation: BotObservation,
        chosen: String,
    ): PurchaseRootExclusion? {
        if (
            view.stage != Stage.PURCHASE ||
                observation.decision != PurchaseSequenceProtocol.DECISION
        ) {
            return PurchaseRootExclusion.WRONG_DECISION
        }
        if (chosen != "pass") return PurchaseRootExclusion.INCUMBENT_CONTINUES
        if (view.reliefRatingAvailable <= 0) return PurchaseRootExclusion.NO_POSITIVE_MR
        val actor = PlayerId(observation.playerId)
        val misery = view.players.single { it.id == actor }.misery
        if (misery == MiseryStep.ZERO || misery == MiseryStep.CHAOS) {
            return PurchaseRootExclusion.NO_POSITIVE_MISERY
        }
        val missing =
            missingCategory(view, actor) ?: return PurchaseRootExclusion.NOT_ONE_MISSING_CATEGORY
        return if (observation.candidates.none { completion(it.command, missing) }) {
            PurchaseRootExclusion.NO_OFFERED_COMPLETION
        } else {
            null
        }
    }

    fun completion(command: String, category: AdvanceCategory): Boolean =
        advance(command)?.let { AdvanceData.definition(it).category == category } == true

    fun advance(command: String): AdvanceId? =
        (TextCodec.parseCommand(command) as? TurnCommand.BuyAdvance)?.let {
            AdvanceId.fromLetter(it.letter)
        }
}

/** Forced advance prefix; later stabilization/relief always belong to the unchanged controller. */
internal data class PurchaseSequencePlan(
    val commands: List<String>,
    val previewBoundary: PlanBoundary,
    val previewDecision: String?,
) {
    init {
        require(commands.size in 1..3) { "A plan contains at most two advances and one pass." }
        require(commands.all { it == "pass" || PurchaseSequenceEligibility.advance(it) != null }) {
            "Purchase sequence plans may force only advance purchases and pass."
        }
        require(commands.count { it != "pass" } <= 2) {
            "A forced plan may buy at most two advances."
        }
        require(commands.dropLast(1).none { it == "pass" }) {
            "Pass ends the forced advance prefix."
        }
        require(commands.distinct().size == commands.size) { "A plan cannot repeat a command." }
    }
}

internal data class PurchaseCompletionCoverage(val nonChaos: Int, val chaos: Int)

internal data class PurchaseSequenceProposals(
    val plans: List<PurchaseSequencePlan>,
    val availableFirstActions: Int,
    val selectedFirstActions: List<String>,
    val legalTwoAdvancePrefixes: Int,
    val excludedFirstActions: List<String>,
    val truncatedTwoAdvancePrefixes: Int,
    val completionCoverage: PurchaseCompletionCoverage,
)

/**
 * Deterministic bounded coverage: incumbent pass, at most four single purchases, then two-purchase
 * children. First-tier completions sort first, followed by canonical command; children sort by
 * their selected parent order then canonical command. No return, model score or hidden fact orders
 * proposals. Every child is re-quoted by the core's existing private-information-safe owner.
 */
internal object PurchaseSequencePlans {
    fun propose(
        state: GameState,
        actor: PlayerId,
        observation: BotObservation,
    ): PurchaseSequenceProposals {
        require(
            PurchaseSequenceEligibility.exclusion(
                state.viewForPlayer(actor),
                observation,
                "pass",
            ) == null
        ) {
            "Purchase sequence proposals require the declared relief-capacity root."
        }
        val missing =
            requireNotNull(
                PurchaseSequenceEligibility.missingCategory(state.viewForPlayer(actor), actor)
            ) {
                "An eligible root has exactly one missing category."
            }
        val first =
            observation.candidates
                .map { it.command }
                .filter { PurchaseSequenceEligibility.advance(it) != null }
                .sortedWith(
                    compareBy<String> { !PurchaseSequenceEligibility.completion(it, missing) }
                        .thenBy { it }
                )
        val selected = first.take(PurchaseSequenceProtocol.FIRST_ACTIONS)
        val singles = selected.map { complete(state, actor, listOf(it)) }
        val doubles = selected.flatMap { command ->
            val preview = ActivityPlanning.preview(state, actor, listOf(action(command)))
            if (
                preview.boundary != PlanBoundary.INCOMPLETE ||
                    preview.decision != PurchaseSequenceProtocol.DECISION
            ) {
                emptyList()
            } else {
                BotObservations.from(
                        preview.game,
                        preview.choices,
                        actor,
                        PurchaseSequenceProtocol.DECISION,
                        state.header.rulesVersion,
                    )
                    .candidates
                    .map { it.command }
                    .filter { PurchaseSequenceEligibility.advance(it) != null }
                    .sorted()
                    .map { next -> complete(state, actor, listOf(command, next)) }
            }
        }
        val initial = listOf(complete(state, actor, listOf("pass"))) + singles
        val retainedDoubles = doubles.take(PurchaseSequenceProtocol.PLANS - initial.size)
        return PurchaseSequenceProposals(
            plans = initial + retainedDoubles,
            availableFirstActions = first.size,
            selectedFirstActions = selected,
            legalTwoAdvancePrefixes = doubles.size,
            excludedFirstActions = first.drop(selected.size),
            truncatedTwoAdvancePrefixes = doubles.size - retainedDoubles.size,
            completionCoverage =
                observation.candidates
                    .filter { PurchaseSequenceEligibility.completion(it.command, missing) }
                    .let { completions ->
                        val chaos = completions.count { (it.features["chaos"] ?: 0.0) > 0.0 }
                        PurchaseCompletionCoverage(completions.size - chaos, chaos)
                    },
        )
    }

    private fun complete(
        state: GameState,
        actor: PlayerId,
        prefix: List<String>,
    ): PurchaseSequencePlan {
        val preview = ActivityPlanning.preview(state, actor, prefix.map(::action))
        check(preview.boundary != PlanBoundary.INVALID) {
            "An enumerated prefix must remain legal."
        }
        val commands =
            if (
                prefix.last() != "pass" &&
                    preview.boundary == PlanBoundary.INCOMPLETE &&
                    preview.decision == PurchaseSequenceProtocol.DECISION
            ) {
                prefix + "pass"
            } else {
                prefix
            }
        val end =
            if (commands == prefix) {
                preview
            } else {
                ActivityPlanning.preview(state, actor, commands.map(::action))
            }
        check(end.boundary != PlanBoundary.INVALID) {
            "Closing a legal advance prefix must remain legal."
        }
        return PurchaseSequencePlan(commands, end.boundary, end.decision)
    }

    fun action(command: String): PlannedAction =
        PlannedAction(PurchaseSequenceProtocol.DECISION, command)
}
