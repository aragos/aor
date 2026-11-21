package dev.aragos.aor

/**
 * Leaders discounting an advance that have not been publicly played or discarded, grouped by
 * printed epoch.
 *
 * Held cards and future decks count. Private discards also count, so changes never disclose their
 * identities. This is public card information, not a statement about which cards remain drawable.
 */
data class AdvanceLeaderAvailabilityView(
    val advance: AdvanceId,
    val epochs: List<LeaderEpochView>,
) {
    companion object {
        internal fun from(state: GameState): List<AdvanceLeaderAvailabilityView> {
            val unplayed =
                LeaderData.definitions.filterNot { it.id.cardId in state.publiclyDiscardedCards }
            return AdvanceId.entries.map { advance ->
                val leaders = unplayed.filter { leader ->
                    leader.discounts.any { it.advance == advance }
                }
                AdvanceLeaderAvailabilityView(
                    advance,
                    Epoch.entries.map { epoch ->
                        LeaderEpochView(
                            epoch.number,
                            leaders
                                .filter { CardData.definition(it.id.cardId).epoch == epoch }
                                .map { it.id },
                        )
                    },
                )
            }
        }
    }
}

/** Matching leaders not publicly played or discarded in printed [epoch], numbered 1 through 3. */
data class LeaderEpochView(val epoch: Int, val leaders: List<LeaderId>)
