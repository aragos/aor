package dev.aragos.aor

import kotlinx.collections.immutable.toPersistentList

/** The supported game variants, resolved and recorded before play begins. */
data class RuleOptions(
    val deckModification: Boolean = false,
    val initialCard: InitialCardRule = InitialCardRule.ANY,
    val immediateMasterArtDiscard: Boolean = false,
    val map: MapRule = MapRule.STANDARD,
    val drawThreeKeepTwo: Boolean = false,
) {
    /** Whether the opening deck can supply the configured draw and return sequence. */
    fun supportsInitialDeal(playerCount: Int): Boolean =
        !drawThreeKeepTwo ||
            cardsForEpoch(Epoch.ONE).count { initialCard.accepts(CardData.definition(it)) } >=
                playerCount * 2 + 1

    /** Physical cards assigned to an epoch before recycled discards are added. */
    fun cardsForEpoch(epoch: Epoch): List<String> = decks.assigned[epoch.ordinal]

    /** In deck-introduction order, regardless of later public discards or hidden locations. */
    internal fun introducedCards(through: Epoch): List<CardDefinition> =
        decks.introduced[through.ordinal]

    private val decks: DeckLists
        get() = if (deckModification) modifiedDecks else printedDecks

    /** An explicit canonical snapshot for durable creation histories. */
    fun configurationCommands(): List<RuleOptionCommand> =
        listOf(
            RuleOptionCommand("deck-modification", deckModification.commandValue()),
            RuleOptionCommand("initial-card", initialCard.commandValue),
            RuleOptionCommand("draw-three-keep-two", drawThreeKeepTwo.commandValue()),
            RuleOptionCommand(
                "immediate-master-art-discard",
                immediateMasterArtDiscard.commandValue(),
            ),
            RuleOptionCommand("map", map.commandValue),
        )

    companion object {
        val AH1996: RuleOptions = RuleOptions()

        private val printedDecks by lazy { DeckLists(modified = false) }
        private val modifiedDecks by lazy { DeckLists(modified = true) }
    }

    private class DeckLists(modified: Boolean) {
        val assigned =
            Epoch.entries.map { epoch ->
                val printed = CardData.forEpoch(epoch).map(CardDefinition::id)
                val ordered =
                    when {
                        !modified || epoch == Epoch.THREE -> printed
                        epoch == Epoch.ONE -> printed.filterNot { it == "war" } + "timber-2-1"
                        else -> printed.filterNot { it == "timber-2-1" } + "war"
                    }
                ordered.toPersistentList()
            }
        val introduced =
            Epoch.entries.map { through ->
                assigned
                    .take(through.ordinal + 1)
                    .flatten()
                    .map(CardData::definition)
                    .toPersistentList()
            }
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
