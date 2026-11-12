package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanPreview
import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.GameState
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlannedAction
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel
import kotlin.random.Random

/** Fixed public proposal contract; values and terminal outcomes never affect proposal order. */
internal object PurchasePlanProtocol {
    const val VERSION = "purchase-plan-ranking-1"
    const val PLANS = 8
    const val EXPLORATORY_CHOICES = 4
    const val MAXIMUM_CHOICES = 64
    const val STREAMS = 4
    val decisions = setOf("purchase-advances", "purchase-stabilization", "purchase-relief")
}

internal enum class PurchasePlanKind {
    INCUMBENT,
    ADVANCE_PASS,
    SAMPLED,
    UNIFORM,
}

internal enum class PurchasePlanStatus {
    SUPPORTED,
    UNSUPPORTED,
}

internal enum class PurchasePlanExclusion {
    HYPOTHETICAL,
    INVALID,
    INFORMATION,
    INTERVENING_CHOICE,
    CHOICE_CAP,
}

/** Only authority-produced own/public state features are predictor inputs. */
internal data class PurchasePlanLeaf(
    val context: AfterstateContext,
    val stateFeatures: Map<String, Double>,
    val exactValue: Double?,
    val exactReason: String?,
) {
    init {
        require(
            context == AfterstateContext.OWNER_EXIT &&
                stateFeatures["purchase.decision.purchase-owner-exit"] == 1.0 &&
                stateFeatures.values.all { it.isFinite() && it in -16.0..16.0 }
        ) {
            "A plan leaf requires actual owner-exit features in the original encoder units."
        }
        require(
            exactReason in setOf(null, "OWN_CHAOS", "GAME_TERMINAL") &&
                (exactValue == null) == (exactReason == null) &&
                (exactValue == null || exactValue.isFinite() && exactValue in 0.0..1.0)
        ) {
            "Exact plan values require an explicit authoritative terminal reason."
        }
        require(
            exactReason != "OWN_CHAOS" ||
                exactValue == 0.0 && stateFeatures["purchase.role.0.chaos"] == 1.0
        ) {
            "Own Chaos must carry its exact zero value and actual owner feature."
        }
    }
}

/** Commands include normal obligations through owner exit, not just the exploratory prefix. */
internal data class PublicPurchasePlan(
    val index: Int,
    val kind: PurchasePlanKind,
    val actions: List<PlannedAction>,
    val status: PurchasePlanStatus,
    val exclusion: PurchasePlanExclusion?,
    val leaf: PurchasePlanLeaf?,
    val aliasOf: Int? = null,
) {
    init {
        require(index in 0 until PurchasePlanProtocol.PLANS) {
            "Plan index is outside the fixed bank."
        }
        require(actions.size <= PurchasePlanProtocol.MAXIMUM_CHOICES) {
            "The owner plan exceeds its fixed cap."
        }
        require(
            if (status == PurchasePlanStatus.SUPPORTED) {
                leaf != null && exclusion == null
            } else {
                leaf == null && exclusion != null
            }
        ) {
            "Supported plans require one complete leaf and no exclusion."
        }
        require(aliasOf == null || aliasOf in 0 until index) {
            "Plan aliases must name an earlier logical plan."
        }
    }
}

/** Normal uses the original policy seed; sampled proposals use a separate draw only. */
internal interface PurchasePlanPolicy {
    fun normal(decision: SimulationDecision): String

    fun sampled(decision: SimulationDecision, uniform: Double): String
}

internal class FrozenPurchasePlanPolicy(
    private val ordinary: BotModel,
    private val controller: SimulationController,
    private val purchasePrior: BotModel,
    private val purchase: PurchasePolicyModel,
) : PurchasePlanPolicy {
    override fun normal(decision: SimulationDecision): String =
        controller.takeIf { it.controls(decision.observation.decision) }?.choose(decision)
            ?: BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, ordinary)

    override fun sampled(decision: SimulationDecision, uniform: Double): String {
        if (decision.observation.decision != "purchase-advances") return normal(decision)
        val recommendation =
            BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, purchasePrior)
        val observation =
            PurchaseObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                recommendation,
            )
        return PurchasePolicy.choose(observation, purchase, uniform).command
    }
}

/**
 * Bounded lookahead over sanitized authoritative previews. No future canonical entries, rival
 * hands, deck positions, terminal labels, or chance draws are consulted to choose a command.
 */
internal class PurchasePlanProposals(
    private val policy: PurchasePlanPolicy,
    private val deadlineNanos: Long,
) {
    fun propose(
        initial: GameState,
        seat: Int,
        gameSeed: Int,
        number: Int,
        actualCommand: String,
        proposalSeeds: List<Long>,
    ): List<PublicPurchasePlan> {
        require(seat in initial.playerIds.indices && number in 0 until 12000) {
            "Plan roots require a real seat and absolute player-command number."
        }
        require(proposalSeeds.size == 6 && proposalSeeds.distinct().size == 6) {
            "The six exploratory plans require explicit distinct proposal streams."
        }
        val owner = initial.playerIds[seat]
        val root = BotObservations.from(initial, owner)
        require(root.decision == "purchase-advances" && root.candidates.size > 1) {
            "The root must be a branching advance purchase, without an outcome or hand filter."
        }
        val kinds =
            listOf(PurchasePlanKind.INCUMBENT, PurchasePlanKind.ADVANCE_PASS) +
                List(3) { PurchasePlanKind.SAMPLED } +
                List(3) { PurchasePlanKind.UNIFORM }
        val plans = kinds.mapIndexed { index, kind ->
            build(
                initial,
                seat,
                gameSeed,
                number,
                index,
                kind,
                proposalSeeds.getOrNull(index - 2),
            )
        }
        require(plans.first().actions.firstOrNull()?.command == actualCommand) {
            "The projected normal policy must reproduce the actual sampled root command."
        }
        return plans.mapIndexed { index, plan ->
            val alias =
                plans
                    .take(index)
                    .indexOfFirst {
                        it.status == plan.status &&
                            it.actions == plan.actions &&
                            it.leaf == plan.leaf &&
                            it.exclusion == plan.exclusion
                    }
                    .takeIf { it >= 0 }
            plan.copy(aliasOf = alias)
        }
    }

    private fun build(
        initial: GameState,
        seat: Int,
        gameSeed: Int,
        number: Int,
        index: Int,
        kind: PurchasePlanKind,
        proposalSeed: Long?,
    ): PublicPurchasePlan {
        val owner = initial.playerIds[seat]
        val random = proposalSeed?.let(::Random)
        val actions = mutableListOf<PlannedAction>()
        while (true) {
            checkTokenValueDeadline(deadlineNanos)
            val preview = ActivityPlanning.preview(initial, owner, actions)
            val exclusion = exclusion(preview, actions.size, number)
            if (exclusion != null) {
                return PublicPurchasePlan(
                    index,
                    kind,
                    actions.toList(),
                    PurchasePlanStatus.UNSUPPORTED,
                    exclusion,
                    null,
                )
            }
            if (preview.boundary == PlanBoundary.FINISHED) {
                return PublicPurchasePlan(
                    index,
                    kind,
                    actions.toList(),
                    PurchasePlanStatus.SUPPORTED,
                    null,
                    leaf(preview, seat, gameSeed),
                )
            }
            val decision =
                requireNotNull(preview.decision) {
                    "An incomplete owner preview requires a decision."
                }
            val observation =
                BotObservations.from(
                    preview.game,
                    preview.choices,
                    owner,
                    decision,
                    initial.header.rulesVersion,
                )
            val offset = number + actions.size
            val current =
                SimulationDecision(
                    offset,
                    seat,
                    simulationPolicySeed(gameSeed, offset, seat),
                    preview.game,
                    observation,
                    offset,
                )
            val command =
                when {
                    kind == PurchasePlanKind.ADVANCE_PASS && actions.isEmpty() -> "pass"
                    actions.size >= PurchasePlanProtocol.EXPLORATORY_CHOICES ->
                        policy.normal(current)
                    kind == PurchasePlanKind.SAMPLED ->
                        policy.sampled(
                            current,
                            requireNotNull(random) {
                                    "Sampled plans require their own random stream."
                                }
                                .nextDouble(),
                        )
                    kind == PurchasePlanKind.UNIFORM -> {
                        val offered = preview.choices.commands.distinct().sorted()
                        require(offered.isNotEmpty()) {
                            "Uniform proposals require actual legal offers."
                        }
                        offered[
                            requireNotNull(random) {
                                    "Uniform plans require their own random stream."
                                }
                                .nextInt(offered.size)]
                    }
                    else -> policy.normal(current)
                }
            require(command in preview.choices.commands) {
                "Every proposed command must be offered by the authoritative public preview."
            }
            actions.add(PlannedAction(decision, command))
        }
    }

    private fun exclusion(
        preview: ActivityPlanPreview,
        count: Int,
        offset: Int,
    ): PurchasePlanExclusion? =
        when {
            preview.hypothetical -> PurchasePlanExclusion.HYPOTHETICAL
            preview.boundary == PlanBoundary.INVALID -> PurchasePlanExclusion.INVALID
            preview.boundary == PlanBoundary.INFORMATION -> PurchasePlanExclusion.INFORMATION
            preview.boundary == PlanBoundary.FINISHED -> null
            preview.decision !in PurchasePlanProtocol.decisions ->
                PurchasePlanExclusion.INTERVENING_CHOICE
            count >= PurchasePlanProtocol.MAXIMUM_CHOICES || offset + count >= 12000 ->
                PurchasePlanExclusion.CHOICE_CAP
            else -> null
        }

    private fun leaf(preview: ActivityPlanPreview, seat: Int, seed: Int): PurchasePlanLeaf {
        val view = preview.game
        val owner = view.players[seat].id
        val reason =
            when {
                view.players[seat].misery == MiseryStep.CHAOS -> "OWN_CHAOS"
                view.stage == Stage.FINISHED -> "GAME_TERMINAL"
                else -> null
            }
        val exact =
            when (reason) {
                "OWN_CHAOS" -> 0.0
                "GAME_TERMINAL" ->
                    SimulatedGame(
                            "public-plan-terminal",
                            seed,
                            0,
                            view.scores,
                            GameCoverage(
                                emptyMap(),
                                emptyMap(),
                                emptySet(),
                                emptySet(),
                                emptySet(),
                                emptySet(),
                            ),
                            0,
                        )
                        .reward(seat)
                else -> null
            }
        return PurchasePlanLeaf(
            AfterstateContext.OWNER_EXIT,
            AfterstateValueEncoder.from(view, owner, AfterstateContext.OWNER_EXIT),
            exact,
            reason,
        )
    }
}
