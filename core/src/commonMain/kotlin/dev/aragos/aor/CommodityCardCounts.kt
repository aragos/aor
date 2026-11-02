package dev.aragos.aor

/**
 * Public commodity counts. [epochs] lists newly introduced cards in epochs 1–3, including shared
 * choices. [unplayed] counts introduced physical cards not publicly played since the last epoch
 * shuffle, including held cards and private discards. Null means no live game context.
 *
 * Neither hidden locations nor the selected commodity of a shared card affect these counts.
 */
data class CommodityCardCountView(
    val commodity: Commodity,
    val epochs: List<Int>,
    val unplayed: Int? = null,
) {
    companion object {
        internal fun from(state: GameState): List<CommodityCardCountView> =
            state.catalog.commodities.map { commodity ->
                val epochs = commodity.cardsByEpoch(state.rules)
                val introduced =
                    if (state.stage == Stage.CONFIGURATION || state.stage == Stage.SHUFFLE) 0
                    else epochs.take(state.currentEpoch.number).sum()
                CommodityCardCountView(
                    commodity = commodity.id,
                    epochs = epochs,
                    unplayed =
                        introduced - commodity.cardIds().count(state.playedCommodities::contains),
                )
            }
    }
}
