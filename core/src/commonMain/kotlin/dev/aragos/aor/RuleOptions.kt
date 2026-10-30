package dev.aragos.aor

/** The supported game variants, resolved and recorded before play begins. */
data class RuleOptions(
    val deckModification: Boolean = false,
    val initialCard: InitialCardRule = InitialCardRule.ANY,
    val immediateMasterArtDiscard: Boolean = false,
    val map: MapRule = MapRule.STANDARD,
) {
    /** Physical cards assigned to an epoch before recycled discards are added. */
    fun cardsForEpoch(epoch: Epoch): List<String> {
        val printed = CardData.forEpoch(epoch).map(CardDefinition::id)
        if (!deckModification || epoch == Epoch.THREE) return printed
        return if (epoch == Epoch.ONE) printed.filterNot { it == "war" } + "timber-2-1"
        else printed.filterNot { it == "timber-2-1" } + "war"
    }

    /** An explicit canonical snapshot for durable creation histories. */
    fun configurationCommands(): List<RuleOptionCommand> =
        listOf(
            RuleOptionCommand("deck-modification", deckModification.commandValue()),
            RuleOptionCommand("initial-card", initialCard.commandValue),
            RuleOptionCommand(
                "immediate-master-art-discard",
                immediateMasterArtDiscard.commandValue(),
            ),
            RuleOptionCommand("map", map.commandValue),
        )

    companion object {
        val AH1996: RuleOptions = RuleOptions()
    }
}

/** Selects the province catalog, topology and setup metadata recorded for this game. */
enum class MapRule(val commandValue: String) {
    STANDARD("standard"),
    EUROPEAN("european");

    val definition: BoardMapDefinition
        get() =
            when (this) {
                STANDARD -> StandardMapData.definition
                EUROPEAN -> EuropeanMapData.definition
            }
}

enum class InitialCardRule(val commandValue: String) {
    ANY("any"),
    NO_CRUSADES("no-crusades"),
    NO_EVENTS("no-events"),
    COMMODITY_ONLY("commodity-only");

    fun accepts(card: CardDefinition): Boolean =
        when (this) {
            ANY -> true
            NO_CRUSADES -> card.id != "the-crusades"
            NO_EVENTS -> card.kind != CardKind.EVENT
            COMMODITY_ONLY -> card.kind == CardKind.COMMODITY
        }
}

private fun Boolean.commandValue(): String = if (this) "enabled" else "disabled"
