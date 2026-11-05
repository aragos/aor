package dev.aragos.aor

/**
 * A local, deterministic Purchase continuation built from owner-visible facts. The supplied players
 * contain public identity, capital and cash, plus the owner's private hand and acquisition history.
 * No deck or opponent hand is required. A continuation stops before the next actor.
 */
class PurchasePlanningPosition
private constructor(
    private val state: GameState,
    private val owner: PlayerId,
) {
    val player: PlayerState
        get() = state.playerState(owner)

    val finished: Boolean
        get() = state.flow.actor != owner || player.misery == MiseryStep.CHAOS

    val decision: String?
        get() =
            if (finished) null else (state.prompts().singleOrNull() as? Prompt.SinglePlayer)?.choice

    val commands: List<String>
        get() = if (finished) emptyList() else state.legalChoices(owner).commands

    /** Public claim history distinguishes equally priced routes with different future discounts. */
    val leaders: List<CardLeaderInPlay>
        get() = state.effects.leaders

    /** The same command application and normalization used by live Purchase and private plans. */
    fun step(command: String): PurchasePlanningPosition {
        require(!finished) { "A completed purchase continuation cannot accept another action." }
        val parsed = TextCodec.parseCommand(command)
        require(
            parsed is TurnCommand.UpgradeShips ||
                parsed is TurnCommand.BuyAdvance ||
                parsed is TurnCommand.Stabilize ||
                parsed is TurnCommand.RelieveMisery ||
                parsed is TurnCommand.Pass ||
                parsed is TurnCommand.DiscardCard ||
                parsed is CardCommand.PatronizeLeader
        ) {
            "A purchase continuation accepts only Purchase commands."
        }
        return PurchasePlanningPosition(
            TurnEngine.normalise(parsed.applyTo(state, owner), planningActor = owner),
            owner,
        )
    }

    companion object {
        /**
         * [decision] is the live Purchase decision, including the optional acquisition discard.
         * [players] supplies public seat facts; only [owner]'s hand may be present.
         */
        fun create(
            players: List<PlayerState>,
            owner: PlayerId,
            currentTurn: Int,
            decision: String,
            rules: RuleOptions,
            effects: CardTurnEffects,
            dataVersion: String,
        ): PurchasePlanningPosition {
            require(currentTurn > 0) { "A purchase continuation requires a running turn." }
            require(players.filter { it.player.id != owner }.all { it.hand.isEmpty() }) {
                "A purchase continuation must not contain opponent hands."
            }
            val step =
                when (decision) {
                    "purchase-shipping" -> PurchaseStep.SHIPPING
                    "purchase-advances",
                    "immediate-master-art-discard" -> PurchaseStep.ADVANCES
                    "purchase-stabilization" -> PurchaseStep.STABILIZATION
                    "purchase-relief" -> PurchaseStep.RELIEF
                    else -> error("Unsupported purchase continuation decision: $decision.")
                }
            val identities = players.map(PlayerState::player)
            val order = listOf(owner) + identities.map(Player::id).filterNot { it == owner }
            val initial =
                GameState(
                    header = GameHeader(identities, owner, dataVersion = dataVersion),
                    stage = Stage.PURCHASE,
                    rules = rules,
                    players = players.associateBy { it.player.id },
                    effects = effects,
                    turnNumber = currentTurn,
                    turnOrderSelection = TurnOrderSelection(order = order),
                    flow =
                        TurnFlow(
                            actor = owner,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                            purchaseStep = step,
                            decisions =
                                if (decision == "immediate-master-art-discard") {
                                    listOf(ImmediateMasterArtDecision(owner))
                                } else {
                                    emptyList()
                                },
                        ),
                )
            return PurchasePlanningPosition(initial, owner)
        }
    }
}
