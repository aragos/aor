package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.BoardPlacementRules
import dev.aragos.aor.ExpansionBoundary
import dev.aragos.aor.ExpansionPosition
import dev.aragos.aor.ExpansionResponseFrontier
import dev.aragos.aor.ExpansionResponseRole
import dev.aragos.aor.ExpansionTacticalFrontier
import dev.aragos.aor.ExpansionTransition
import dev.aragos.aor.GameView
import dev.aragos.aor.PlacementPlan
import dev.aragos.aor.ProvincePieces
import dev.aragos.aor.RuleViolation
import dev.aragos.aor.TextCodec

/** Deterministic research diagnostics; every returned command was offered at the actual root. */
data class BotCathedralChoice(
    val command: String,
    val activated: Boolean = false,
    val rootValues: Map<String, Double> = emptyMap(),
    val legalNonpassRoots: Int = 0,
    val retainedNonpassRoots: Int = 0,
    val truncatedFollowupOptions: Int = 0,
    val resolvedLeaves: Int = 0,
    val stopEvaluations: Int = 0,
    val responseQueries: Int = 0,
    val firstActionVictoriesStopped: Int = 0,
)

/** Native-only two-action research selector; no live model or ordinary policy invokes it. */
object BotCathedralTactics {
    const val VERSION = "public-cathedral-two-action-1"
    private const val LIMIT = 16

    /**
     * [model] is the exact native expansion model; [incumbent] is the complete controller choice.
     */
    fun choose(
        view: GameView,
        observation: BotObservation,
        incumbent: String,
        model: BotModel,
        enabled: Boolean = false,
    ): BotCathedralChoice {
        val unchanged = BotCathedralChoice(incumbent)
        if (!enabled || observation.decision != "expand") return unchanged
        val candidate =
            observation.candidates.singleOrNull { it.command == incumbent } ?: return unchanged
        val command = TextCodec.parseCommand(incumbent) as? BoardCommand.Attack ?: return unchanged
        val public = view.expansionPosition ?: return unchanged
        if (public.actor.value != observation.playerId) return unchanged
        val position = public.toPosition()
        val pending =
            position.reserve(command.province, candidate.tokenCost)
                as? ExpansionTransition.PendingCompetition ?: return unchanged
        if (pending.cathedralChoices.none { it != position.actor }) return unchanged
        val legal =
            observation.candidates.mapNotNull { offered ->
                val action = TextCodec.parseCommand(offered.command)
                val destination =
                    when (action) {
                        is BoardCommand.Attack -> action.province
                        is BoardCommand.Place -> action.province
                        else -> return@mapNotNull null
                    }
                val plan =
                    try {
                        position.evaluate(destination, offered.tokenCost)
                    } catch (_: RuleViolation) {
                        return@mapNotNull null
                    }
                if (plan is PlacementPlan.Peaceful) {
                    val after = BoardPlacementRules.applyPeaceful(position.context, plan).board
                    val marker = after.pieces[destination] as? ProvincePieces.Marker
                    if (marker?.owner != position.actor) return@mapNotNull null
                }
                RootOption(offered, plan)
            }
        if (legal.none { it.candidate.command == incumbent }) return unchanged
        val retained = retainRoots(legal.map(RootOption::candidate), incumbent, model)
        val byCommand = legal.associateBy { it.candidate.command }
        val options = retained.map { candidate ->
            checkNotNull(byCommand[candidate.command]) { "Retained roots require a legal plan." }
        }
        val evaluator = Evaluation(view, position, model)
        val values =
            options.associate {
                it.candidate.command to evaluator.action(position, it.plan, 1, 0)
            } +
                listOfNotNull(
                        observation.candidates
                            .singleOrNull { it.command == "pass" }
                            ?.let { "pass" to 0.0 }
                    )
                    .toMap()
        val incumbentValue =
            checkNotNull(values[incumbent]) { "The tactical comparison retains its incumbent." }
        val best =
            values.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
                )
                .first()
        val selected = if (best.value > incumbentValue) best.key else incumbent
        return BotCathedralChoice(
            selected,
            true,
            values,
            legal.size,
            options.size,
            evaluator.truncated,
            evaluator.leaves,
            evaluator.stops,
            evaluator.queries,
            evaluator.victoriesStopped,
        )
    }

    /** Keep the incumbent, seven strongest alternatives, then eight cheapest remaining probes. */
    internal fun retainRoots(
        legal: List<BotCandidate>,
        incumbent: String,
        model: BotModel,
    ): List<BotCandidate> {
        val current =
            requireNotNull(legal.singleOrNull { it.command == incumbent }) {
                "The bounded root frontier requires its incumbent."
            }
        val scored =
            legal.sortedWith(
                compareByDescending<BotCandidate> { BotPolicy.score(it, model) }
                    .thenBy(BotCandidate::tokenCost)
                    .thenBy(BotCandidate::command)
            )
        val strong = listOf(current) + scored.filterNot { it.command == incumbent }.take(7)
        val retained = strong.map(BotCandidate::command).toSet()
        val cheap =
            legal
                .filterNot { it.command in retained }
                .sortedWith(compareBy(BotCandidate::tokenCost).thenBy(BotCandidate::command))
        return strong + cheap.take(LIMIT - strong.size)
    }

    private data class RootOption(val candidate: BotCandidate, val plan: PlacementPlan)

    private class Evaluation(view: GameView, root: ExpansionPosition, model: BotModel) {
        private val utility = BotExpansionOutcomeValue(view, root, model)
        var truncated = 0
            private set

        var leaves = 0
            private set

        var stops = 0
            private set

        var queries = 0
            private set

        var victoriesStopped = 0
            private set

        fun action(
            position: ExpansionPosition,
            plan: PlacementPlan,
            depth: Int,
            losses: Int,
        ): Double =
            when (val transition = position.reserve(plan.destination, plan.addedTokens)) {
                is ExpansionTransition.Resolved -> {
                    if (depth == 1) {
                        continuation(transition.position, losses)
                    } else {
                        leaf(transition.position, losses)
                    }
                }
                is ExpansionTransition.PendingCompetition -> {
                    queries++
                    val frontier = ExpansionTacticalFrontier.responses(transition)
                    val branchValues =
                        frontier.branches.map { branch ->
                            val lost =
                                losses +
                                    if (branch.result.competition?.attackerWon == false) {
                                        plan.addedTokens
                                    } else {
                                        0
                                    }
                            if (
                                depth == 1 &&
                                    branch.result.boundary ==
                                        ExpansionBoundary.VICTORY_CONTINUATIONS_UNRESOLVED
                            ) {
                                victoriesStopped++
                            }
                            if (
                                depth == 2 ||
                                    branch.result.boundary ==
                                        ExpansionBoundary.VICTORY_CONTINUATIONS_UNRESOLVED
                            ) {
                                leaf(branch.result.position, lost)
                            } else {
                                continuation(branch.result.position, lost)
                            }
                        }
                    aggregate(frontier, branchValues)
                }
            }

        private fun continuation(position: ExpansionPosition, losses: Int): Double {
            stops++
            check(stops <= 80) { "Tactical stop evaluations exceeded the fixed bound." }
            val options =
                ExpansionTacticalFrontier.attacks(position, limit = LIMIT, fullMarketsOnly = true)
            truncated += options.totalLegalOptions - options.options.size
            return maxOf(
                utility.value(position, losses),
                options.options.maxOfOrNull { action(position, it, 2, losses) }
                    ?: Double.NEGATIVE_INFINITY,
            )
        }

        private fun leaf(position: ExpansionPosition, losses: Int): Double {
            leaves++
            check(leaves <= 6400) { "Tactical resolved leaves exceeded the fixed bound." }
            return utility.value(position, losses)
        }
    }

    /**
     * Sequential defenders minimize the actor value, including declining into the exact chance
     * node.
     */
    internal fun aggregate(frontier: ExpansionResponseFrontier, values: List<Double>): Double {
        require(values.size == frontier.branches.size) {
            "Every response branch requires one utility."
        }
        val dice = frontier.branches.withIndex().filter { it.value.declaredBy == null }
        check(dice.sumOf { it.value.diceOutcomes } == 216) {
            "A chance frontier requires all 216 dice triples."
        }
        val chance = dice.sumOf { values[it.index] * it.value.diceOutcomes } / 216.0
        val alternatives =
            frontier.branches
                .withIndex()
                .filter { it.value.declaredBy != null }
                .map { values[it.index] } + chance
        return when (frontier.decisionRole) {
            ExpansionResponseRole.ACTOR_MAX -> alternatives.max()
            ExpansionResponseRole.DEFENDER_MIN -> alternatives.min()
            ExpansionResponseRole.CHANCE -> chance
        }
    }
}
