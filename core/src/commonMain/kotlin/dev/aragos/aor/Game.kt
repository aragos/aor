package dev.aragos.aor

const val RULES_VERSION = "ah1996-turn-1"
const val DATA_VERSION = "ah1996-setup-1"

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
    val boardMarkers: Int = 0,
    val misery: MiseryStep = MiseryStep.ZERO,
    val ships: ShippingCapability = ShippingCapability.NONE,
)

/**
 * Pure engine state. Every registered player has an empty hand before the initial deal.
 *
 * [turnNumber] is null during setup and begins at 1 after all capitals are chosen.
 * [turnOrderSelection] ranks purchases for the current turn independently of capital choice.
 */
data class GameState(
    val header: GameHeader,
    val epochs: Int = 3,
    val stage: Stage = Stage.CONFIGURATION,
    val deck: List<String> = emptyList(),
    val players: Map<PlayerId, PlayerState> =
        header.players.associate { player ->
            player.id to PlayerState(player = player)
        },
    val capitalSelection: CapitalSelection = CapitalSelection(),
    val turnNumber: Int? = null,
    val turnOrderSelection: TurnOrderSelection = TurnOrderSelection(),
) {
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
            return CapitalData.availableFor(playerIds.size).filterNot(assigned::contains)
        }

    fun prompts(): List<Prompt> = listOfNotNull(stage.prompt(this))

    fun legalChoices(player: PlayerId): LegalChoices {
        playerState(player)
        return stage.legalChoices(state = this, player = player)
    }

    fun viewForPlayer(player: PlayerId): GameView =
        GameView.forPlayer(state = this, viewer = playerState(player))

    fun step(entry: Entry): GameState = entry.applyTo(this)

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
