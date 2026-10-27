package dev.aragos.aor

/** The supported game variants, resolved and recorded before play begins. */
data class RuleOptions(
    val deckModification: Boolean = false,
    val initialCard: InitialCardRule = InitialCardRule.ANY,
    val immediateMasterArtDiscard: Boolean = false,
    val map: MapRule = MapRule.STANDARD,
) {
    init {
        require(map == MapRule.STANDARD) {
            "The European map requires its reviewed province and adjacency specification."
        }
    }

    // Baseline mechanics are fixed; these are not configurable game variants.
    val setup: SetupRule
        get() = SetupRule.STANDARD

    val commerce: CommerceRule
        get() = CommerceRule.AH1996

    val windWatermill: WindWatermillRule
        get() = WindWatermillRule.TAKEOVER_TRADE

    val writtenRecord: WrittenRecordRule
        get() = WrittenRecordRule.LEADER_BONUS

    val incomeRelief: IncomeReliefRule
        get() = IncomeReliefRule.NEW_WORLD

    val credits: CreditRule
        get() = CreditRule.AH1996

    val miseryRelief: MiseryReliefRule
        get() = MiseryReliefRule.PURCHASED

    val patronage: PatronageRule
        get() = PatronageRule.PROTECTION_MONEY

    val stGallSupport: Boolean
        get() = false

    /** Physical cards assigned to an epoch before recycled discards are added. */
    fun cardsForEpoch(epoch: Epoch): List<String> {
        val printed = CardData.forEpoch(epoch).map(CardDefinition::id)
        if (!deckModification || epoch == Epoch.THREE) return printed
        return if (epoch == Epoch.ONE) printed.filterNot { it == "war" } + "timber-2-1"
        else printed.filterNot { it == "timber-2-1" } + "war"
    }

    val definitions: List<AdvanceDefinition>
        get() = AdvanceData.definitions.map { definition -> resolve(definition) }

    fun definition(advance: AdvanceId): AdvanceDefinition = resolve(AdvanceData.definition(advance))

    /** A printed letter is selected by the commerce table; domain identities remain stable. */
    fun letter(advance: AdvanceId): Char =
        when {
            commerce == CommerceRule.SECOND_EDITION && advance == AdvanceId.IMPROVED_AGRICULTURE ->
                'J'
            commerce == CommerceRule.SECOND_EDITION && advance == AdvanceId.WIND_WATERMILL -> 'K'
            else -> advance.letter
        }

    fun advance(letter: Char): AdvanceId =
        AdvanceId.entries.singleOrNull { id ->
            letter(id) == letter.uppercaseChar()
        } ?: throw RuleViolation(ViolationCode.INVALID_COMMAND, listOf(letter.toString()))

    /** An explicit canonical snapshot for durable creation histories and preset expansion. */
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

    private fun resolve(definition: AdvanceDefinition): AdvanceDefinition {
        val commerceDefinition =
            if (commerce == CommerceRule.SECOND_EDITION) {
                when (definition.id) {
                    AdvanceId.IMPROVED_AGRICULTURE ->
                        definition.copy(
                            cost = 40,
                            prerequisites = setOf(AdvanceId.CARAVAN),
                        )
                    AdvanceId.WIND_WATERMILL ->
                        definition.copy(
                            cost = 50,
                            prerequisites = setOf(AdvanceId.IMPROVED_AGRICULTURE),
                        )
                    AdvanceId.INTEREST_PROFIT,
                    AdvanceId.MIDDLE_CLASS ->
                        definition.copy(prerequisites = setOf(AdvanceId.WIND_WATERMILL))
                    else -> definition
                }
            } else {
                definition
            }
        val credit =
            if (credits == CreditRule.SECOND_EDITION) {
                when {
                    definition.id == AdvanceId.WRITTEN_RECORD -> 30
                    definition.id == AdvanceId.PRINTED_WORD -> 20
                    definition.id == AdvanceId.PROSELYTISM -> 30
                    definition.category == AdvanceCategory.CIVICS -> definition.credit + 10
                    else -> definition.credit
                }
            } else {
                definition.credit
            }
        return commerceDefinition.copy(credit = credit, letter = letter(definition.id))
    }

    companion object {
        val AH1996: RuleOptions = RuleOptions()
    }
}

/** European geometry is reserved until its province and adjacency changes are reviewed. */
enum class MapRule(val commandValue: String) {
    STANDARD("standard"),
    EUROPEAN("european"),
}

enum class SetupRule(val commandValue: String) {
    STANDARD("standard"),
    IMPROVED("improved"),
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

enum class CommerceRule(val commandValue: String) {
    AH1996("ah1996"),
    SECOND_EDITION("second-edition"),
}

enum class WindWatermillRule(val commandValue: String) {
    TAKEOVER_TRADE("takeover-trade"),
    MARKET_ADJUSTMENT("market-adjustment"),
}

enum class WrittenRecordRule(val commandValue: String) {
    LEADER_BONUS("leader-bonus"),
    TAKEOVER_TRADE("takeover-trade"),
}

enum class IncomeReliefRule(val commandValue: String) {
    NEW_WORLD("new-world"),
    ENLIGHTENMENT("enlightenment"),
}

enum class CreditRule(val commandValue: String) {
    AH1996("ah1996"),
    SECOND_EDITION("second-edition"),
}

enum class MiseryReliefRule(val commandValue: String) {
    PURCHASED("purchased"),
    CATEGORY_COMPLETION("category-completion"),
}

enum class PatronageRule(val commandValue: String) {
    PROTECTION_MONEY("protection-money"),
    THREE_BOX_DISPLAY("three-box-display"),
}

private fun Boolean.commandValue(): String = if (this) "enabled" else "disabled"
