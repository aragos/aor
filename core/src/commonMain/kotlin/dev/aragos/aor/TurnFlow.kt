package dev.aragos.aor

/** A decision frame preserves the outer phase actor while another player answers. Top is last. */
interface TurnDecision {
    val player: PlayerId?
    val choice: String

    fun choices(state: GameState): List<String> = emptyList()

    /**
     * Explicit recorded dice outcome; implementations validate their own frame before resolving.
     */
    interface Roll : TurnDecision {
        val kind: GameRollKind
        val diceCount: Int

        /** Participants receiving one die each; empty for dice without player attribution. */
        val players: List<PlayerId>
            get() = emptyList()

        override val player: PlayerId?
            get() = null

        fun resolve(state: GameState, values: List<DieRoll>): GameState
    }

    /** A uniformly selected hand reference, visible only to the two trading participants. */
    interface RandomCard : TurnDecision {
        val owner: PlayerId
        val recipient: PlayerId
        override val player: PlayerId?
            get() = null

        fun resolve(state: GameState, reference: String): GameState
    }
}

enum class GameRollKind {
    CAPITAL_TIE,
    COMPETITION,
    WAR,
    SHORTAGE,
    WINDMILL,
}

enum class PurchaseStep {
    SHIPPING,
    ADVANCES,
    STABILIZATION,
    RELIEF,
}

/** Bookkeeping scoped to a turn; permanent War state and card voids live on GameState. */
data class TurnFlow(
    val actor: PlayerId? = null,
    val cursor: Int = 0,
    val phaseStarted: Boolean = false,
    /** Phase-opening automatic effects run once, after every Renaissance window is answered. */
    val phaseBookkeepingApplied: Boolean = false,
    val decisions: List<TurnDecision> = emptyList(),
    val purchaseStep: PurchaseStep = PurchaseStep.SHIPPING,
    val drawn: Set<PlayerId> = emptySet(),
    val boughtCards: Set<PlayerId> = emptySet(),
    val discardedCards: Set<PlayerId> = emptySet(),
    val cancelComplete: Boolean = false,
    val marketAdjustmentComplete: Boolean = false,
    val writtenRecordUsed: Set<PlayerId> = emptySet(),
    val renaissanceUsed: Set<PlayerId> = emptySet(),
    val shipped: Map<PlayerId, Map<ProvinceId, Int>> = emptyMap(),
    val expansionCards: Set<PlayerId> = emptySet(),
    val expansionCardCount: Int = 0,
    val markerOrigins: Map<ProvinceId, MarkerPlacementOrigin> = emptyMap(),
    val eventReduced: Set<ProvinceId> = emptySet(),
    val voluntaryReduced: Set<ProvinceId> = emptySet(),
    val cathedralUses: Set<Pair<PlayerId, PlayerId>> = emptySet(),
    val windmillUsed: Set<PlayerId> = emptySet(),
    val shortageRolls: Int = 0,
)

internal data class DrawDecision(val owner: PlayerId) : TurnDecision {
    override val player: PlayerId?
        get() = null

    override val choice: String
        get() = "draw-card"
}

internal data class RenaissanceDecision(override val player: PlayerId) : TurnDecision {
    override val choice: String
        get() = "renaissance"

    override fun choices(state: GameState): List<String> =
        listOf("pass") +
            state.turnOrder
                .filter { target ->
                    kotlin.math.abs(
                        state.turnOrder.indexOf(player) - state.turnOrder.indexOf(target)
                    ) == 1 &&
                        !state.playerState(target).advances.owns(AdvanceId.RENAISSANCE) &&
                        state.playerState(target).misery != MiseryStep.CHAOS
                }
                .map { target -> "swap-order $target" }
}

internal data object ShortageDecision : TurnDecision.Roll {
    override val choice: String
        get() = "shortages"

    override val kind: GameRollKind
        get() = GameRollKind.SHORTAGE

    override val diceCount: Int
        get() = 3

    override fun resolve(state: GameState, values: List<DieRoll>): GameState =
        TurnEngine.shortage(state, values)
}

/** The highest unresolved capital-choice tie uses the same recorded roll as turn decisions. */
internal data class CapitalTieDecision(override val players: List<PlayerId>) : TurnDecision.Roll {
    override val choice: String
        get() = "capital-tie"

    override val kind: GameRollKind
        get() = GameRollKind.CAPITAL_TIE

    override val diceCount: Int
        get() = players.size

    override fun resolve(state: GameState, values: List<DieRoll>): GameState =
        state.resolveCapitalTie(players.zip(values).toMap())
}
