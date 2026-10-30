package dev.aragos.aor

/** Printed track endpoints for a known public consequence; null denotes Chaos. */
data class MiseryChangeView(
    val player: PlayerId,
    val fromValue: Int?,
    val toValue: Int?,
) {
    companion object {
        internal fun between(
            player: PlayerId,
            before: MiseryStep,
            after: MiseryStep,
        ): MiseryChangeView? =
            MiseryChangeView(player, before.value, after.value).takeIf { before != after }
    }
}

/** No dice, target choices or private card locations are invented by a consequence preview. */
internal data class CardMiseryPreview(
    val changes: List<MiseryChangeView> = emptyList(),
    val uncertainPlayers: List<PlayerId> = emptyList(),
    val cashChanges: List<CashChangeView> = emptyList(),
) {
    companion object {
        fun from(context: CardContext, resolution: CardResolution): CardMiseryPreview {
            val spaces =
                when (resolution) {
                    is CardResolution.CivilWar ->
                        mapOf(resolution.victim to CardRules.CIVIL_WAR_MISERY_SPACES)
                    is CardResolution.CrusadesPlacement ->
                        mapOf(resolution.player to CardRules.CRUSADES_MISERY_SPACES)
                    is CardResolution.MiseryEvent -> resolution.spaces
                    is CardResolution.ReligiousStrife -> resolution.spaces
                    else -> emptyMap()
                }
            return CardMiseryPreview(
                spaces.mapNotNull { (player, movement) ->
                    val before = context.player(player).misery
                    MiseryChangeView.between(player, before, MiseryRules.shift(before, movement))
                },
                if (resolution is CardResolution.WarBattle) {
                    listOf(resolution.war.initiator, resolution.war.opponent)
                } else {
                    emptyList()
                },
                CardCashPreview.from(context, resolution),
            )
        }
    }
}
