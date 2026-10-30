package dev.aragos.aor

const val RULES_VERSION = "ah1996-three-epoch-3"
const val DATA_VERSION = "ah1996-catalog-13"

/**
 * A registered participant, independent of their capital or creator role.
 *
 * [id] is a stable log identifier. [name] is a nonblank display name of at most 60 characters; the
 * application trims user input before registration.
 */
data class Player(val id: PlayerId, val name: String) {
    init {
        require(name.isNotBlank()) { "Player name must not be blank." }
        require(name.length <= 60) { "Player name must contain at most 60 characters." }
    }
}

/**
 * Immutable registration order and compatibility versions for an authoritative game log.
 *
 * [creatorId] identifies the registered player authorized to configure and start this game; it is
 * independent of the player's position in [players].
 */
data class GameHeader(
    val players: List<Player>,
    val creatorId: PlayerId,
    val rulesVersion: String = RULES_VERSION,
    val dataVersion: String = DATA_VERSION,
) {
    init {
        require(players.size in 3..6) { "A game requires 3 through 6 players." }
        require(players.map(Player::id).distinct().size == players.size) {
            "Registered player ids must be unique."
        }
        require(players.any { player -> player.id == creatorId }) {
            "The game creator must be a registered player."
        }
        require(rulesVersion == RULES_VERSION) { "Unsupported rules version: $rulesVersion." }
        require(dataVersion == DATA_VERSION) { "Unsupported data version: $dataVersion." }
    }
}

/**
 * Authoritative player-owned information; projections determine what another player may see.
 *
 * [tokenPurchase] is the signed amount submitted for the current turn, hidden until all players
 * submit. Its absolute amount is paid and only a positive amount buys expansion tokens.
 */
data class PlayerState(
    val player: Player,
    val hand: List<HandCard> = emptyList(),
    val capitalBid: Int? = null,
    val tokenPurchase: Int? = null,
    val capital: String? = null,
    val cash: Int = 40,
    val stockTokens: Int = 36,
    val poolTokens: Int = 0,
    /** Cash recorded after the current turn's token purchase. */
    val writtenCash: Int = 40,
    val misery: MiseryStep = MiseryStep.ZERO,
    val ships: ShippingCapability = ShippingCapability.NONE,
    val advances: PlayerAdvances = PlayerAdvances(),
    /** Turn of the last paid shipping upgrade; advance-driven fleet changes do not count. */
    val lastShipUpgradeTurn: Int? = null,
    val miseryReliefUsage: MiseryReliefUsage? = null,
)

/**
 * Pure engine state. Every registered player has an empty hand before the initial deal.
 *
 * [turnNumber] is null during setup and begins at 1 after all capitals are chosen.
 * [turnOrderSelection] ranks purchases for the current turn independently of capital choice.
 */
data class GameState(
    val header: GameHeader,
    val currentEpoch: Epoch = Epoch.ONE,
    val rules: RuleOptions = RuleOptions(),
    val deckHistory: DeckHistory = DeckHistory(),
    val rebuiltMarkerBonus: RebuiltMarkerBonus = RebuiltMarkerBonus.EXCLUDE,
    val stage: Stage = Stage.CONFIGURATION,
    val deck: List<String> = emptyList(),
    val players: Map<PlayerId, PlayerState> =
        header.players.associate { player ->
            player.id to PlayerState(player = player)
        },
    val effects: CardTurnEffects = CardTurnEffects(),
    /** Public leader plays across all turns; private discards never change this information. */
    val playedLeaders: Set<LeaderId> = emptySet(),
    val activeWar: WarState? = null,
    val voids: CardVoids = CardVoids(),
    val board: BoardState = BoardState(definition = rules.map.definition),
    val flow: TurnFlow = TurnFlow(),
    val endingTriggered: Boolean = false,
    val entryCount: Int = 0,
    val activityStart: Int = 0,
    /**
     * Entry ordinal opening the current decision; replay-derived, never a private draft revision.
     */
    val decisionStart: Int = 0,
    val adjustments: Map<Commodity, List<CommodityAdjustment>> = emptyMap(),
    val capitalSelection: CapitalSelection = CapitalSelection(),
    val turnNumber: Int? = null,
    val turnOrderSelection: TurnOrderSelection = TurnOrderSelection(),
) {
    val catalog: CatalogConfiguration by lazy { CatalogConfiguration(playerIds.size, rules) }

    val playerIds: List<PlayerId>
        get() = header.players.map(Player::id)

    val capitalOrder: List<PlayerId>
        get() = capitalSelection.order

    val turnOrder: List<PlayerId>
        get() = if (turnOrderSelection.isResolved) turnOrderSelection.order else emptyList()

    val orderBoxes: Map<PlayerId, Int>
        get() = if (turnOrder.isEmpty()) emptyMap() else OrderBoxes.forOrder(turnOrder)

    val availableCapitals: List<String>
        get() {
            val assigned = players.values.mapNotNull(PlayerState::capital)
            return catalog.capitals.filterNot(assigned::contains)
        }

    fun prompts(): List<Prompt> {
        val need =
            pendingShuffle()?.let { request ->
                SystemNeed.Shuffle(request.cards, request.purpose)
            }
                ?: pendingDraw()
                ?: pendingRoll()?.let { decision ->
                    SystemNeed.GameRoll(decision.kind, decision.diceCount, decision.players)
                }
        return listOfNotNull(
            need?.let(Prompt::System) ?: TurnEngine.prompt(this) ?: stage.prompt(this)
        )
    }

    /** Draw requests share card references and validation across setup and repeatable turns. */
    internal fun pendingDraw(): SystemNeed.Draw? {
        if (pendingShuffle() != null) return null
        val owner =
            if (stage == Stage.INITIAL_DRAW) {
                playerIds.firstOrNull { player ->
                    playerState(player).hand.size < SetupDeal.cardsPerPlayer
                } ?: return null
            } else {
                (flow.decisions.lastOrNull() as? DrawDecision)?.owner ?: return null
            }
        return SystemNeed.Draw(owner, nextHandReference(), SetupDeal.nextCard(this) ?: return null)
    }

    /** Capital tie dice and interrupted turn dice use one request and recorded-outcome contract. */
    internal fun pendingRoll(): TurnDecision.Roll? =
        if (stage == Stage.CAPITAL_TIE) {
            CapitalTieDecision(capitalSelection.ties.first().players)
        } else {
            flow.decisions.lastOrNull() as? TurnDecision.Roll
        }

    fun legalChoices(player: PlayerId): LegalChoices {
        playerState(player)
        return if (stage.isTurnPhase || flow.decisions.isNotEmpty()) {
            TurnEngine.choices(this, player)
        } else {
            stage.legalChoices(state = this, player = player)
        }
    }

    fun viewForPlayer(player: PlayerId): GameView =
        GameView.forPlayer(state = this, viewer = playerState(player))

    fun step(entry: Entry): GameState = transition(entry).state

    /** Receipts are derived during replay and are never persisted as a second game truth. */
    fun transition(entry: Entry): GameTransition {
        val receipts = mutableListOf<EffectReceipt>()
        val applied = entry.applyTo(this)
        val next = TurnEngine.normalise(applied, receipts::add).copy(entryCount = entryCount + 1)
        val changed =
            next.stage != stage || next.flow.actor != flow.actor || next.turnNumber != turnNumber
        val decisionChanged =
            changed ||
                next.flow.purchaseStep != flow.purchaseStep ||
                next.flow.decisions != flow.decisions ||
                next.decisionPrompt() != decisionPrompt()
        val resolved =
            next.copy(
                activityStart = if (changed) entryCount + 1 else activityStart,
                decisionStart = if (decisionChanged) entryCount + 1 else decisionStart,
            )
        return GameTransition(resolved, applied, receipts.toList())
    }

    /** Private batch readiness changes eligibility without replacing the shared decision. */
    private fun decisionPrompt(): Prompt? =
        when (val prompt = prompts().singleOrNull()) {
            is Prompt.MultiPlayer -> prompt.copy(players = emptyList())
            else -> prompt
        }

    /** Looks up a registered [player] without exposing nullable player state to rule handlers. */
    fun playerState(player: PlayerId): PlayerState =
        players[player] ?: throw RuleViolation(ViolationCode.UNKNOWN_PLAYER, listOf(player.value))

    internal fun updatePlayer(
        player: PlayerId,
        update: (PlayerState) -> PlayerState,
    ): GameState = copy(players = players + (player to update(playerState(player))))

    /** Publishes the ranking after the complete bid batch has been accepted. */
    internal fun beginCapitalSelection(selection: CapitalSelection): GameState {
        checkRule(stage == Stage.BIDDING, ViolationCode.WRONG_PHASE)
        return advanceCapitalSelection(selection)
    }

    /** Applies [rolls] only to the pending capital tie in the capital tie stage. */
    internal fun resolveCapitalTie(rolls: Map<PlayerId, DieRoll>): GameState {
        checkRule(stage == Stage.CAPITAL_TIE, ViolationCode.WRONG_PHASE)
        return advanceCapitalSelection(capitalSelection.resolveNextTie(rolls))
    }

    private fun advanceCapitalSelection(selection: CapitalSelection): GameState =
        copy(
            capitalSelection = selection,
            stage = if (selection.isResolved) Stage.CAPITAL_CHOICE else Stage.CAPITAL_TIE,
        )
}
