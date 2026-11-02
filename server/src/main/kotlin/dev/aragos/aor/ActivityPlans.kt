package dev.aragos.aor

import java.security.MessageDigest
import java.util.UUID

enum class PlanPause {
    CHANGED,
    INFORMATION,
    INCOMPLETE,
    INVALID,
    EDITED,
    MANUAL,
}

/** One private, phase-scoped authorization. Empty actions never authorize an implicit Pass. */
data class SavedActivityPlan(
    val turnNumber: Int,
    val stage: Stage,
    val actions: List<PlannedAction>,
    /** Latest save/authorization position; ordinary public appends do not invalidate it. */
    val baseRevision: Int,
    val baseEntryCount: Int,
    val signature: String? = null,
    val pause: PlanPause? = null,
    val authorizationOperationId: String? = null,
) {
    init {
        require(turnNumber > 0) { "A saved plan requires a positive turn number." }
        require(stage in setOf(Stage.PURCHASE, Stage.BUY_CARD)) {
            "Plans belong to Purchase or Buy Card."
        }
        require(actions.size <= 100) { "A plan may contain at most 100 actions." }
        require(baseRevision > 0 && baseEntryCount >= 0) {
            "A plan requires its committed revision and nonnegative effective entry count."
        }
        require(signature == null || signature.matches(Regex("[0-9a-f]{64}"))) {
            "An automatic plan requires a SHA-256 consequence signature."
        }
        require(signature == null || (actions.isNotEmpty() && pause == null)) {
            "Only a nonempty, unpaused plan may be authorized."
        }
        require(
            signature == null ||
                authorizationOperationId?.matches(GameDocuments.GAME_ID_PATTERN) == true
        ) {
            "An automatic plan requires its authenticated authorization operation UUID."
        }
    }

    val armed: Boolean
        get() = signature != null
}

/** Entry range attribution is private; public Activity still consists of normal engine actions. */
data class PlanExecution(
    val version: String,
    val fromRevision: Int,
    val toRevision: Int,
    val operationId: String,
    val baseRevision: Int,
    val baseEntryCount: Int,
) {
    init {
        require(
            version.matches(GameDocuments.GAME_ID_PATTERN) &&
                operationId.matches(GameDocuments.GAME_ID_PATTERN)
        ) {
            "Plan execution receipts require canonical version and authorization UUIDs."
        }
        require(
            baseRevision > 0 &&
                baseEntryCount >= 0 &&
                fromRevision > baseRevision &&
                toRevision >= fromRevision
        ) {
            "Plan execution ranges must follow their committed authorization position."
        }
    }
}

data class ActivityPlanningView(
    val available: Boolean,
    val version: String?,
    val saved: SavedActivityPlan?,
    val preview: ActivityPlanPreview?,
    val receipts: List<String>,
    val executions: List<PlanExecution>,
    val canArm: Boolean,
    val canExecute: Boolean,
    val previewToken: String?,
)

enum class PlanOperation {
    SAVE,
    ARM,
    DISARM,
    DELETE,
    EXECUTE,
}

internal object ActivityPlans {
    fun view(game: StoredGame, owner: PlayerId): ActivityPlanningView {
        val state = game.state()
        val available = game.status == GameStatus.ACTIVE && ActivityPlanning.available(state, owner)
        val saved = game.plans[owner]
        val preview =
            if (available) ActivityPlanning.preview(state, owner, saved?.actions.orEmpty())
            else null
        return ActivityPlanningView(
            available = available,
            version = game.planVersions[owner],
            saved = saved,
            preview = preview,
            receipts = game.planReceipts[owner].orEmpty(),
            executions = game.planExecutions[owner].orEmpty(),
            canArm =
                available &&
                    (saved == null || inScope(saved, state)) &&
                    state.flow.decisions.none { it.choice == "renaissance" },
            canExecute =
                available &&
                    (state.prompts().singleOrNull() as? Prompt.SinglePlayer)?.let {
                        it.player == owner && it.choice != "renaissance"
                    } == true,
            previewToken = preview?.let(::signature),
        )
    }

    fun write(
        game: StoredGame,
        owner: PlayerId,
        operation: PlanOperation,
        actions: List<PlannedAction>,
        expectedPreview: String?,
        driver: GameDriver,
        operationId: String,
    ): StoredGame {
        val view = view(game, owner)
        val previous = game.plans[owner]
        val next =
            when (operation) {
                PlanOperation.DELETE -> null
                PlanOperation.DISARM -> previous?.copy(signature = null, pause = null)
                PlanOperation.SAVE -> {
                    if (!view.available) throw ServiceFailure(422, "PLAN_UNAVAILABLE")
                    if (previous?.armed == true) throw ServiceFailure(409, "PLAN_ARMED")
                    SavedActivityPlan(
                        requireNotNull(game.state().turnNumber) {
                            "Planning requires a running turn."
                        },
                        game.state().stage,
                        actions,
                        baseRevision = game.revision,
                        baseEntryCount = game.state().entryCount,
                    )
                }
                PlanOperation.ARM,
                PlanOperation.EXECUTE -> {
                    if (!view.canArm || (operation == PlanOperation.EXECUTE && !view.canExecute)) {
                        throw ServiceFailure(422, "PLAN_UNAVAILABLE")
                    }
                    val plan = previous ?: throw ServiceFailure(422, "NO_SAVED_PLAN")
                    val preview =
                        requireNotNull(view.preview) { "An available plan has a preview." }
                    if (preview.effects.isEmpty() || preview.boundary == PlanBoundary.INVALID) {
                        throw ServiceFailure(
                            422,
                            "PLAN_INVALID",
                            entryIndex = preview.invalidIndex,
                            diagnostic = preview.diagnostic,
                        )
                    }
                    val signature = signature(preview)
                    if (expectedPreview != signature)
                        throw ServiceFailure(409, "STALE_PLAN_PREVIEW")
                    plan.copy(
                        signature = signature,
                        pause = null,
                        baseRevision = game.revision,
                        baseEntryCount = game.state().entryCount,
                        authorizationOperationId = operationId,
                    )
                }
            }
        val updated =
            game.copy(
                plans = if (next == null) game.plans - owner else game.plans + (owner to next),
                planVersions = game.planVersions + (owner to UUID.randomUUID().toString()),
            )
        return if (operation == PlanOperation.EXECUTE) execute(updated, owner, driver) else updated
    }

    fun signature(preview: ActivityPlanPreview): String =
        planDigest(json.writeValueAsString(listOf(preview.effects, preview.boundary)))

    fun execute(game: StoredGame, owner: PlayerId, driver: GameDriver): StoredGame {
        val plan = game.plans[owner] ?: return game
        if (!plan.armed || game.status != GameStatus.ACTIVE) return game
        val state = game.state()
        val prompt = state.prompts().singleOrNull() as? Prompt.SinglePlayer
        if (prompt?.player != owner || prompt.choice == "renaissance") return game
        if (!inScope(plan, state) || !ActivityPlanning.available(state, owner)) {
            return game.copy(
                plans = game.plans - owner,
                planVersions = game.planVersions + (owner to UUID.randomUUID().toString()),
            )
        }
        val preview = ActivityPlanning.preview(state, owner, plan.actions)
        val pause =
            when {
                preview.boundary == PlanBoundary.INVALID -> PlanPause.INVALID
                signature(preview) != plan.signature -> PlanPause.CHANGED
                else -> null
            }
        if (pause != null) return replace(game, owner, plan.copy(signature = null, pause = pause))
        // Preflight contains no authority outcomes. All revisions and progress publish together.
        val committed =
            preview.effects.fold(game) { current, effect ->
                driver.submit(current, owner, effect.action.command)
            }
        val remaining = plan.actions.drop(preview.effects.size)
        val next =
            plan.copy(
                actions = remaining,
                signature = null,
                pause =
                    when (preview.boundary) {
                        PlanBoundary.INFORMATION -> PlanPause.INFORMATION
                        else -> PlanPause.INCOMPLETE
                    },
            )
        val version =
            requireNotNull(game.planVersions[owner]) {
                "An authorized plan requires an owner version."
            }
        val receipt =
            PlanExecution(
                version,
                game.revision + 1,
                committed.revision,
                operationId =
                    requireNotNull(plan.authorizationOperationId) {
                        "An authorized execution requires its authenticated operation."
                    },
                baseRevision = plan.baseRevision,
                baseEntryCount = plan.baseEntryCount,
            )
        return reconcile(replace(committed, owner, next))
            .copy(
                planExecutions =
                    committed.planExecutions +
                        (owner to
                            (committed.planExecutions[owner].orEmpty() + receipt).takeLast(16))
            )
    }

    fun disarm(game: StoredGame, pause: PlanPause): StoredGame =
        game.copy(
            plans =
                game.plans.mapValues { (_, plan) -> plan.copy(signature = null, pause = pause) },
            planVersions = game.planVersions.mapValues { UUID.randomUUID().toString() },
        )

    fun afterManual(game: StoredGame, owner: PlayerId): StoredGame {
        val plan = game.plans[owner] ?: return reconcile(game)
        return reconcile(
            replace(game, owner, plan.copy(signature = null, pause = PlanPause.MANUAL))
        )
    }

    private fun replace(game: StoredGame, owner: PlayerId, plan: SavedActivityPlan): StoredGame =
        game.copy(
            plans = game.plans + (owner to plan),
            planVersions = game.planVersions + (owner to UUID.randomUUID().toString()),
        )

    private fun reconcile(game: StoredGame): StoredGame {
        val state = game.state()
        val remaining =
            game.plans.filter { (owner, plan) ->
                game.status == GameStatus.ACTIVE &&
                    inScope(plan, state) &&
                    ActivityPlanning.available(state, owner)
            }
        val expired = game.plans.keys - remaining.keys
        return game.copy(
            plans = remaining,
            planVersions =
                game.planVersions + expired.associateWith { UUID.randomUUID().toString() },
        )
    }

    private fun inScope(plan: SavedActivityPlan, state: GameState): Boolean =
        plan.stage == state.stage && plan.turnNumber == state.turnNumber
}

internal fun planDigest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
