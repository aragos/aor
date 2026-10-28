package dev.aragos.aor

/**
 * Leaders discounting an advance that have not been publicly played, grouped by printed epoch.
 *
 * Held cards and future decks count. Private discards also count, so changes never disclose their
 * identities. This is public play information, not a statement about which cards remain drawable.
 */
data class AdvanceLeaderAvailabilityView(
    val advance: AdvanceId,
    val epochs: List<LeaderEpochCountView>,
) {
    companion object {
        internal fun from(state: GameState): List<AdvanceLeaderAvailabilityView> {
            val unplayed = LeaderData.definitions.filterNot { it.id in state.playedLeaders }
            return AdvanceId.entries.map { advance ->
                val leaders = unplayed.filter { leader ->
                    leader.discounts.any { it.advance == advance }
                }
                AdvanceLeaderAvailabilityView(
                    advance,
                    Epoch.entries.map { epoch ->
                        LeaderEpochCountView(
                            epoch.number,
                            leaders.count { CardData.definition(it.id.cardId).epoch == epoch },
                        )
                    },
                )
            }
        }
    }
}

/** Number of unplayed matching leaders in the printed deck for [epoch], numbered 1 through 3. */
data class LeaderEpochCountView(val epoch: Int, val count: Int)
