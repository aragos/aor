package dev.aragos.aor

/** The decision name binds otherwise ambiguous commands such as Pass to their intended step. */
data class PlannedAction(val decision: String, val command: String) {
    init {
        require(decision.length in 1..80) { "A planned action requires a decision name." }
        require(command.length in 1..200 && '\n' !in command && '\r' !in command) {
            "A planned action requires one command of at most 200 characters."
        }
    }
}

enum class PlanBoundary {
    INCOMPLETE,
    FINISHED,
    INFORMATION,
    INVALID,
}

/** Owner-visible resources used to authorize exact consequences, independent of public revision. */
data class PlanResources(
    val cash: Int,
    val misery: MiseryStep,
    val ships: ShippingCapability,
    val advances: PlayerAdvances,
    val hand: List<HandCard>,
    val reliefUsage: MiseryReliefUsage?,
)

data class PlanCashChange(val player: PlayerId, val before: Int, val after: Int)

data class PlannedEffect(
    val action: PlannedAction,
    val before: PlanResources,
    val after: PlanResources,
    val cashChanges: List<PlanCashChange>,
    val advanceQuote: AdvanceQuote?,
)

/** No outcome, hidden deck position, or another player's hand is included in a plan preview. */
data class ActivityPlanPreview(
    val effects: List<PlannedEffect>,
    val boundary: PlanBoundary,
    val diagnostic: RuleDiagnostic?,
    val game: GameView,
    val choices: LegalChoices,
    val decision: String?,
    val hypothetical: Boolean,
) {
    val invalidIndex: Int?
        get() = if (boundary == PlanBoundary.INVALID) effects.size else null
}

/** Narrow projections for a player's remaining Purchase or Buy Card activity. */
object ActivityPlanning {
    fun available(state: GameState, owner: PlayerId): Boolean =
        state.stage in setOf(Stage.PURCHASE, Stage.BUY_CARD) &&
            state.playerState(owner).misery != MiseryStep.CHAOS &&
            state.turnOrder.indexOf(owner) >= state.flow.cursor &&
            (state.stage == Stage.PURCHASE ||
                CardPurchases.offer(state, state.playerState(owner)).blockers.isEmpty() ||
                (state.playerState(owner).advances.owns(AdvanceId.MASTER_ART) &&
                    owner !in state.flow.discardedCards &&
                    state.playerState(owner).hand.isNotEmpty()))

    fun preview(
        initial: GameState,
        owner: PlayerId,
        actions: List<PlannedAction>,
    ): ActivityPlanPreview {
        checkRule(available(initial, owner), ViolationCode.PLAN_UNAVAILABLE)
        require(actions.size <= 100) { "A plan may contain at most 100 actions." }
        val hypothetical =
            initial.flow.actor != owner ||
                initial.flow.decisions.any {
                    it is RenaissanceDecision
                }
        // Opaque placeholders retain public counts without consulting future card identities.
        var state =
            initial.copy(
                deck = List(initial.deck.size) { "unseen" },
                players =
                    initial.players.mapValues { (id, player) ->
                        if (id == owner) player
                        else
                            player.copy(
                                hand =
                                    List(player.hand.size) { index ->
                                        HandCard("unseen-$index", CardData.all.first())
                                    }
                            )
                    },
                flow =
                    initial.flow.copy(
                        actor = owner,
                        cursor = initial.turnOrder.indexOf(owner),
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                        purchaseStep =
                            if (initial.flow.actor == owner) initial.flow.purchaseStep
                            else PurchaseStep.SHIPPING,
                        decisions = if (hypothetical) emptyList() else initial.flow.decisions,
                    ),
            )
        state = TurnEngine.normalise(state, planningActor = owner)
        val effects = mutableListOf<PlannedEffect>()
        var diagnostic: RuleDiagnostic? = null
        for (action in actions) {
            if (boundary(state, owner) != PlanBoundary.INCOMPLETE) break
            try {
                val prompt = state.prompts().singleOrNull() as? Prompt.SinglePlayer
                checkRule(prompt?.choice == action.decision, ViolationCode.PLAN_DECISION_CHANGED)
                val command = TextCodec.parseCommand(action.command)
                checkRule(allowed(command), ViolationCode.INVALID_COMMAND)
                checkRule(command.toText() == action.command, ViolationCode.INVALID_COMMAND)
                val before = state
                val quote =
                    if (command is TurnCommand.BuyAdvance) {
                        AdvancePurchases.quote(
                            state.playerState(owner),
                            AdvanceId.fromLetter(command.letter),
                            state.purchaseContext(owner),
                        )
                    } else null
                state =
                    TurnEngine.normalise(
                        command.applyTo(state, owner),
                        planningActor = owner,
                    )
                effects.add(
                    PlannedEffect(
                        action = action,
                        before = resources(before.playerState(owner)),
                        after = resources(state.playerState(owner)),
                        cashChanges =
                            state.playerIds.mapNotNull { id ->
                                val from = before.playerState(id).cash
                                val to = state.playerState(id).cash
                                if (from == to) null else PlanCashChange(id, from, to)
                            },
                        advanceQuote = quote,
                    )
                )
            } catch (failure: RuleViolation) {
                diagnostic = failure.diagnostic
                break
            } catch (failure: CardRuleViolation) {
                diagnostic = failure.diagnostic
                break
            }
        }
        val boundary = if (diagnostic != null) PlanBoundary.INVALID else boundary(state, owner)
        val prompt =
            if (boundary == PlanBoundary.INCOMPLETE) {
                state.prompts().singleOrNull() as? Prompt.SinglePlayer
            } else null
        return ActivityPlanPreview(
            effects = effects.toList(),
            boundary = boundary,
            diagnostic = diagnostic,
            game = state.viewForPlayer(owner),
            choices = if (prompt != null) state.legalChoices(owner) else LegalChoices(),
            decision = prompt?.choice,
            hypothetical = hypothetical,
        )
    }

    private fun boundary(state: GameState, owner: PlayerId): PlanBoundary =
        when {
            state.flow.actor != owner || state.playerState(owner).misery == MiseryStep.CHAOS ->
                PlanBoundary.FINISHED
            state.prompts().singleOrNull() is Prompt.System -> PlanBoundary.INFORMATION
            else -> PlanBoundary.INCOMPLETE
        }

    private fun allowed(command: Command): Boolean =
        command is TurnCommand.UpgradeShips ||
            command is TurnCommand.BuyAdvance ||
            command is TurnCommand.Stabilize ||
            command is TurnCommand.RelieveMisery ||
            command is TurnCommand.BuyCard ||
            command is TurnCommand.DiscardCard ||
            command is TurnCommand.Pass ||
            command is CardCommand.PatronizeLeader

    private fun resources(player: PlayerState): PlanResources =
        PlanResources(
            cash = player.cash,
            misery = player.misery,
            ships = player.ships,
            advances = player.advances,
            hand = player.hand,
            reliefUsage = player.miseryReliefUsage,
        )
}
