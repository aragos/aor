package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanPreview
import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.EventId
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlannedAction
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations

/** Public execution-contract exclusions, decided before any continuation return is read. */
internal enum class PurchaseMacroSupport {
    SUPPORTED,
    INFORMATION_BOUNDARY,
    INTERVENING_CHOICE,
    CHANGED_OFFER,
    MISSING_CLOSING_PASS,
    EXTRA_COMMAND_AFTER_FINISH,
    INVALID_PREVIEW,
}

/** A prospective teacher plan; null features mean unsupported, never a zero-valued return. */
internal data class PurchaseMacroPlanFacts(
    val planIndex: Int,
    val commands: List<String>,
    val support: PurchaseMacroSupport,
    val naturalFinish: Boolean,
    val features: List<Double>?,
)

/** Only ordinary own/public facts; no authority state, hidden outcomes, seeds or return labels. */
internal data class PurchaseMacroFacts(
    val version: String = PurchaseMacroProjection.VERSION,
    val featureNames: List<String>,
    val plans: List<PurchaseMacroPlanFacts>,
    val proposals: PurchaseSequenceProposals,
)

/**
 * Offline-only factual projection for the fixed complete-prefix advantage student. This owns no
 * selector, learned weights, simulator rollout or live queue. Every prefix and conditional quote
 * delegates to [ActivityPlanning]; no player state is fabricated to estimate a consequence.
 */
internal object PurchaseMacroProjection {
    const val VERSION = "purchase-macro-public-1"
    private const val ADVANCES = "purchase-advances"
    private const val STABILIZATION = "purchase-stabilization"
    private const val RELIEF = "purchase-relief"
    private val endpoints =
        listOf(
            "cash",
            "advancePoints",
            "miseryValue",
            "chaos",
            "unusedMR",
            "tiers",
            "handCount",
            "burdenCount",
            "stabilizationCost",
            "stabilizationMiseryValue",
            "stabilizationChaos",
            "cashStabilizationAffordable",
            "ordinaryIncome",
            "interestIncome",
            "maxQuotedReliefSpaces",
            "maxAffordableReliefSpaces",
            "reliefOneCashCost",
            "reliefOneRatingSpent",
            "reliefOneMiseryAfter",
            "ownerFinished",
            "incomeKnown",
        )
    private val conditional =
        listOf(
            "available",
            "chaos",
            "maxQuotedReliefSpaces",
            "maxAffordableReliefSpaces",
            "reliefOneCashCost",
            "reliefOneRatingSpent",
            "reliefOneMiseryAfter",
        )
    private val contexts =
        listOf(
            "turn",
            "epoch",
            "ending",
            "cash",
            "miseryValue",
            "unusedMR",
            "handCount",
            "markers",
            "ordinaryIncome",
        )
    private val crossContexts =
        listOf("cash", "miseryValue", "unusedMR", "turn", "ending", "handCount")
    private val crossDeltas =
        listOf(
            "cash",
            "miseryValue",
            "unusedMR",
            "tiers",
            "stabilizationCost",
            "conditional.cash.maxAffordableReliefSpaces",
        )

    /** Catalog-owned vocabulary fixed before feature data or labels are inspected. */
    val featureNames: List<String> = buildList {
        for (slot in 1..2) {
            AdvanceId.entries.forEach { add("plan.slot$slot.${it.name}") }
        }
        endpoints.forEach { add("delta.$it") }
        AdvanceCategory.entries.forEach { add("delta.category.${it.name}") }
        ShippingCapability.entries.forEach { add("delta.ships.${it.name}") }
        for (payment in listOf("cash", "misery")) {
            conditional.forEach { add("delta.conditional.$payment.$it") }
        }
        contexts.forEach { add("root.ifIntervening.$it") }
        EventId.entries.forEach { add("ownEvent.ifIntervening.${it.cardId}") }
        for (root in crossContexts) {
            crossDeltas.forEach { add("cross.root.$root.delta.$it") }
        }
    }
        .sorted()

    fun from(state: GameState, actor: PlayerId, observation: BotObservation): PurchaseMacroFacts {
        val proposals = PurchaseSequencePlans.propose(state, actor, observation)
        val pass = inspect(state, actor, proposals.plans.first().commands)
        check(pass.support == PurchaseMacroSupport.SUPPORTED) {
            "An eligible macro root requires its actual supported PASS control."
        }
        val reference = facts(state, actor, proposals.plans.first().commands, pass.preview)
        val root = rootFacts(state.viewForPlayer(actor), actor)
        val hand = state.viewForPlayer(actor).hand.groupingBy { it.definition.id }.eachCount()
        val plans =
            proposals.plans.mapIndexed { index, plan ->
                val checked = inspect(state, actor, plan.commands)
                val features =
                    if (checked.support != PurchaseMacroSupport.SUPPORTED) {
                        null
                    } else if (index == 0) {
                        List(featureNames.size) { 0.0 }
                    } else {
                        val endpoint = facts(state, actor, plan.commands, checked.preview)
                        val deltas = endpoint.mapValues { (key, value) ->
                            value - (reference[key] ?: 0.0)
                        }
                        val values = buildMap {
                            plan.commands
                                .mapNotNull(PurchaseSequenceEligibility::advance)
                                .forEachIndexed { slot, advance ->
                                    put("plan.slot${slot + 1}.${advance.name}", 1.0)
                                }
                            deltas.forEach { (key, value) -> put("delta.$key", value) }
                            root.forEach { (key, value) -> put("root.ifIntervening.$key", value) }
                            EventId.entries.forEach {
                                put(
                                    "ownEvent.ifIntervening.${it.cardId}",
                                    (hand[it.cardId] ?: 0).toDouble(),
                                )
                            }
                            for (context in crossContexts) {
                                for (delta in crossDeltas) {
                                    put(
                                        "cross.root.$context.delta.$delta",
                                        (root[context] ?: 0.0) * (deltas[delta] ?: 0.0),
                                    )
                                }
                            }
                        }
                        check(
                            values.keys.all { it in featureNames } &&
                                values.values.all(Double::isFinite)
                        ) {
                            "Macro features must use only finite values in their fixed dictionary."
                        }
                        featureNames.map { values[it] ?: 0.0 }
                    }
                PurchaseMacroPlanFacts(
                    index,
                    plan.commands,
                    checked.support,
                    checked.support == PurchaseMacroSupport.SUPPORTED &&
                        checked.preview.boundary == PlanBoundary.FINISHED,
                    features,
                )
            }
        return PurchaseMacroFacts(featureNames = featureNames, plans = plans, proposals = proposals)
    }

    private data class Checked(val support: PurchaseMacroSupport, val preview: ActivityPlanPreview)

    private fun inspect(state: GameState, actor: PlayerId, commands: List<String>): Checked {
        for (index in commands.indices) {
            val before = preview(state, actor, commands.take(index))
            val failure =
                when (before.boundary) {
                    PlanBoundary.INFORMATION -> PurchaseMacroSupport.INFORMATION_BOUNDARY
                    PlanBoundary.INVALID -> PurchaseMacroSupport.INVALID_PREVIEW
                    PlanBoundary.FINISHED -> PurchaseMacroSupport.EXTRA_COMMAND_AFTER_FINISH
                    PlanBoundary.INCOMPLETE ->
                        if (before.decision != ADVANCES) PurchaseMacroSupport.INTERVENING_CHOICE
                        else null
                }
            if (failure != null) return Checked(failure, before)
            val offered =
                BotObservations.from(
                    before.game,
                    before.choices,
                    actor,
                    ADVANCES,
                    state.header.rulesVersion,
                )
            if (offered.candidates.none { it.command == commands[index] }) {
                return Checked(PurchaseMacroSupport.CHANGED_OFFER, before)
            }
        }
        val end = preview(state, actor, commands)
        val support =
            when {
                end.boundary == PlanBoundary.INFORMATION ->
                    PurchaseMacroSupport.INFORMATION_BOUNDARY
                end.boundary == PlanBoundary.INVALID -> PurchaseMacroSupport.INVALID_PREVIEW
                end.boundary == PlanBoundary.FINISHED -> PurchaseMacroSupport.SUPPORTED
                commands.last() == "pass" && end.decision in setOf(STABILIZATION, RELIEF) ->
                    PurchaseMacroSupport.SUPPORTED
                end.decision != ADVANCES -> PurchaseMacroSupport.INTERVENING_CHOICE
                else -> PurchaseMacroSupport.MISSING_CLOSING_PASS
            }
        return Checked(support, end)
    }

    private fun preview(state: GameState, actor: PlayerId, commands: List<String>) =
        ActivityPlanning.preview(state, actor, commands.map(PurchaseSequencePlans::action))

    private fun facts(
        state: GameState,
        actor: PlayerId,
        commands: List<String>,
        end: ActivityPlanPreview,
    ): Map<String, Double> {
        val resource =
            requireNotNull(end.effects.lastOrNull()?.after) {
                "A supported prefix has an authoritative effect."
            }
        val view = end.game
        val self = view.players.single { it.id == actor }
        val stabilization =
            requireNotNull(view.stabilizationCost) {
                "A purchase preview has an owner stabilization quote."
            }
        return buildMap {
            put("cash", resource.cash.toDouble())
            put("advancePoints", resource.advances.score.toDouble())
            put("miseryValue", (resource.misery.value ?: 0).toDouble())
            put("chaos", flag(resource.misery == MiseryStep.CHAOS))
            put("unusedMR", view.reliefRatingAvailable.toDouble())
            put("tiers", resource.advances.tierCount.toDouble())
            put("handCount", resource.hand.size.toDouble())
            put("burdenCount", view.burdenReferences.size.toDouble())
            put("stabilizationCost", stabilization.toDouble())
            put("stabilizationMiseryValue", (view.stabilizationMisery ?: 0).toDouble())
            put("stabilizationChaos", flag(view.stabilizationMisery == null))
            put("cashStabilizationAffordable", flag(resource.cash.toLong() >= stabilization))
            put("ordinaryIncome", (self.income?.ordinary ?: 0).toDouble())
            put("interestIncome", (self.income?.interestProfit ?: 0).toDouble())
            put("incomeKnown", flag(self.income != null))
            put("ownerFinished", flag(end.boundary == PlanBoundary.FINISHED))
            putAll(relief(view))
            AdvanceCategory.entries.forEach {
                put("category.${it.name}", resource.advances.categoryCount(it).toDouble())
            }
            ShippingCapability.entries.forEach {
                put("ships.${it.name}", flag(resource.ships == it))
            }
            for (payment in listOf("cash", "misery")) {
                val branch = conditional(state, actor, commands, end, payment)
                branch.forEach { (key, value) -> put("conditional.$payment.$key", value) }
            }
        }
    }

    /**
     * Conditional quotes describe available choices; neither payment nor relief is a forced plan.
     */
    private fun conditional(
        state: GameState,
        actor: PlayerId,
        commands: List<String>,
        end: ActivityPlanPreview,
        payment: String,
    ): Map<String, Double> {
        if (end.boundary != PlanBoundary.INCOMPLETE) return emptyMap()
        val branch =
            when (end.decision) {
                RELIEF -> end
                STABILIZATION -> {
                    val command = "stabilize $payment"
                    if (command !in end.choices.commands) return emptyMap()
                    ActivityPlanning.preview(
                        state,
                        actor,
                        commands.map(PurchaseSequencePlans::action) +
                            PlannedAction(STABILIZATION, command),
                    )
                }
                else -> return emptyMap()
            }
        check(
            branch.boundary != PlanBoundary.INVALID && branch.boundary != PlanBoundary.INFORMATION
        ) {
            "A legal stabilization payment is a deterministic owner-local consequence."
        }
        return mapOf(
            "available" to 1.0,
            "chaos" to
                flag(branch.game.players.single { it.id == actor }.misery == MiseryStep.CHAOS),
        ) + relief(branch.game)
    }

    private fun relief(view: GameView): Map<String, Double> {
        val one = view.reliefOffers.singleOrNull { it.spaces == 1 }
        return mapOf(
            "maxQuotedReliefSpaces" to
                (view.reliefOffers.maxOfOrNull { it.spaces } ?: 0).toDouble(),
            "maxAffordableReliefSpaces" to
                (view.reliefOffers.filter { it.affordable }.maxOfOrNull { it.spaces } ?: 0)
                    .toDouble(),
            "reliefOneCashCost" to (one?.cashCost ?: 0).toDouble(),
            "reliefOneRatingSpent" to (one?.ratingSpent ?: 0).toDouble(),
            "reliefOneMiseryAfter" to (one?.miseryAfter ?: 0).toDouble(),
        )
    }

    private fun rootFacts(view: GameView, actor: PlayerId): Map<String, Double> {
        val self = view.players.single { it.id == actor }
        return mapOf(
            "turn" to
                requireNotNull(view.turnNumber) { "A macro root has a game turn." }.toDouble(),
            "epoch" to view.currentEpoch.toDouble(),
            "ending" to flag(view.endingTriggered),
            "cash" to self.cash.toDouble(),
            "miseryValue" to (self.misery.value ?: 0).toDouble(),
            "unusedMR" to view.reliefRatingAvailable.toDouble(),
            "handCount" to self.handCount.toDouble(),
            "markers" to self.boardMarkers.toDouble(),
            "ordinaryIncome" to (self.income?.ordinary ?: 0).toDouble(),
        )
    }

    private fun flag(value: Boolean): Double = if (value) 1.0 else 0.0
}
