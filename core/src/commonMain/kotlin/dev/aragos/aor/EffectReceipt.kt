package dev.aragos.aor

/** Public automatic causes; private effect families must declare their own audience contract. */
enum class AutomaticCause {
    INCOME,
    HOLY_INDULGENCE,
    CIVIL_WAR,
}

/** Actual endpoints preserve clamps and offsetting direct/automatic resource movements. */
data class ResourceEndpoints(
    val player: PlayerId,
    val cashBefore: Int,
    val cashAfter: Int,
    val poolBefore: Int,
    val poolAfter: Int,
    val stockBefore: Int,
    val stockAfter: Int,
    val miseryBefore: MiseryStep,
    val miseryAfter: MiseryStep,
)

data class EffectReceipt(
    val cause: AutomaticCause,
    val stage: Stage,
    val turnNumber: Int?,
    val resources: List<ResourceEndpoints>,
    val advance: AdvanceId?,
    val orderBefore: List<PlayerId> = emptyList(),
    val orderAfter: List<PlayerId> = emptyList(),
) {
    companion object {
        internal fun between(
            cause: AutomaticCause,
            before: GameState,
            after: GameState,
        ): EffectReceipt =
            EffectReceipt(
                cause,
                after.stage,
                after.turnNumber,
                before.playerIds.mapNotNull { id ->
                    val previous = before.playerState(id)
                    val current = after.playerState(id)
                    ResourceEndpoints(
                            id,
                            previous.cash,
                            current.cash,
                            previous.poolTokens,
                            current.poolTokens,
                            previous.stockTokens,
                            current.stockTokens,
                            previous.misery,
                            current.misery,
                        )
                        .takeIf {
                            previous.cash != current.cash ||
                                previous.poolTokens != current.poolTokens ||
                                previous.stockTokens != current.stockTokens ||
                                previous.misery != current.misery
                        }
                },
                when (cause) {
                    AutomaticCause.INCOME -> AdvanceId.NEW_WORLD
                    AutomaticCause.HOLY_INDULGENCE -> AdvanceId.HOLY_INDULGENCE
                    AutomaticCause.CIVIL_WAR -> null
                },
                before.turnOrder,
                after.turnOrder,
            )
    }
}

/** Applied state precedes normalization; the final state owns decision/activity identities. */
data class GameTransition(
    val state: GameState,
    val applied: GameState,
    val receipts: List<EffectReceipt>,
)
