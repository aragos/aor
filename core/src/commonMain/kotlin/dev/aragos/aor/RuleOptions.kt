package dev.aragos.aor

/** Functional rules, resolved before play. Presets are shorthand and never stored as editions. */
data class RuleOptions(
    val setup: SetupRule = SetupRule.STANDARD,
    val initialCard: InitialCardRule = InitialCardRule.ANY,
    val commerce: CommerceRule = CommerceRule.AH1996,
    val windWatermill: WindWatermillRule = WindWatermillRule.TAKEOVER_TRADE,
    val writtenRecord: WrittenRecordRule = WrittenRecordRule.LEADER_BONUS,
    val incomeRelief: IncomeReliefRule = IncomeReliefRule.NEW_WORLD,
    val credits: CreditRule = CreditRule.AH1996,
    val miseryRelief: MiseryReliefRule = MiseryReliefRule.PURCHASED,
    val patronage: PatronageRule = PatronageRule.PROTECTION_MONEY,
    val immediateMasterArtDiscard: Boolean = false,
    val stGallSupport: Boolean = false,
) {
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
            RuleOptionCommand("setup", setup.commandValue),
            RuleOptionCommand("initial-card", initialCard.commandValue),
            RuleOptionCommand("commerce", commerce.commandValue),
            RuleOptionCommand("wind-watermill", windWatermill.commandValue),
            RuleOptionCommand("written-record", writtenRecord.commandValue),
            RuleOptionCommand("income-relief", incomeRelief.commandValue),
            RuleOptionCommand("credits", credits.commandValue),
            RuleOptionCommand("misery-relief", miseryRelief.commandValue),
            RuleOptionCommand("patronage", patronage.commandValue),
            RuleOptionCommand(
                "immediate-master-art-discard",
                immediateMasterArtDiscard.commandValue(),
            ),
            RuleOptionCommand("st-gall-support", stGallSupport.commandValue()),
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
        val SECOND_EDITION: RuleOptions =
            RuleOptions(
                setup = SetupRule.IMPROVED,
                commerce = CommerceRule.SECOND_EDITION,
                windWatermill = WindWatermillRule.MARKET_ADJUSTMENT,
                writtenRecord = WrittenRecordRule.TAKEOVER_TRADE,
                incomeRelief = IncomeReliefRule.ENLIGHTENMENT,
                credits = CreditRule.SECOND_EDITION,
                miseryRelief = MiseryReliefRule.CATEGORY_COMPLETION,
                patronage = PatronageRule.THREE_BOX_DISPLAY,
                immediateMasterArtDiscard = true,
            )
        val TOURNAMENT: RuleOptions =
            RuleOptions(
                initialCard = InitialCardRule.COMMODITY_ONLY,
                immediateMasterArtDiscard = true,
                stGallSupport = true,
            )
    }
}

enum class SetupRule(val commandValue: String) {
    STANDARD("standard"),
    IMPROVED("improved"),
}

enum class InitialCardRule(val commandValue: String) {
    ANY("any"),
    COMMODITY_ONLY("commodity-only"),
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
