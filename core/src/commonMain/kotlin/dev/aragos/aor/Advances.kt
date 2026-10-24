package dev.aragos.aor

/** AH1996 advance identifiers. Letters A–Z are the printed, locale-independent identifiers. */
enum class AdvanceId(val letter: Char) {
    THE_HEAVENS('A'),
    HUMAN_BODY('B'),
    LAWS_OF_MATTER('C'),
    ENLIGHTENMENT('D'),
    PATRONAGE('E'),
    HOLY_INDULGENCE('F'),
    PROSELYTISM('G'),
    CATHEDRAL('H'),
    CARAVAN('I'),
    WIND_WATERMILL('J'),
    IMPROVED_AGRICULTURE('K'),
    INTEREST_PROFIT('L'),
    INDUSTRY('M'),
    WRITTEN_RECORD('N'),
    PRINTED_WORD('O'),
    MASTER_ART('P'),
    RENAISSANCE('Q'),
    OVERLAND_EAST('R'),
    SEAWORTHY_VESSELS('S'),
    OCEAN_NAVIGATION('T'),
    NEW_WORLD('U'),
    URBAN_ASCENDANCY('V'),
    NATIONALISM('W'),
    INSTITUTIONAL_RESEARCH('X'),
    COSMOPOLITAN('Y'),
    MIDDLE_CLASS('Z'),
}

enum class AdvanceCategory(val totalCost: Int) {
    SCIENCE(300),
    RELIGION(300),
    COMMERCE(300),
    COMMUNICATION(300),
    EXPLORATION(400),
    CIVICS(500),
}

/** All amounts are dollars; [miseryRelief] is a discount, not a number of misery spaces. */
data class AdvanceDefinition(
    val id: AdvanceId,
    val category: AdvanceCategory,
    val cost: Int,
    val credit: Int,
    val miseryRelief: Int,
    val prerequisites: Set<AdvanceId> = emptySet(),
) {
    val messageId: String
        get() = "advance.${id.letter}"

    /** Immediate change in misery spaces on purchase; negative values improve misery. */
    val purchaseMisery: Int
        get() =
            when {
                category == AdvanceCategory.RELIGION -> 1
                id == AdvanceId.HUMAN_BODY || id == AdvanceId.IMPROVED_AGRICULTURE -> -1
                else -> 0
            }

    init {
        require(cost > 0) { "Advance cost must be positive: $id." }
        require(credit >= 0) { "Advance credit cannot be negative: $id." }
        require(miseryRelief >= 0) { "Advance misery relief cannot be negative: $id." }
        require(id !in prerequisites) { "An advance cannot require itself: $id." }
    }
}

@GameDataDsl
class AdvanceCatalogBuilder {
    private val definitions = mutableListOf<AdvanceDefinition>()

    fun advance(
        id: AdvanceId,
        category: AdvanceCategory,
        cost: Int,
        credit: Int,
        miseryRelief: Int,
        vararg prerequisites: AdvanceId,
    ) {
        require(definitions.none { definition -> definition.id == id }) {
            "Advance identifiers must be unique: $id."
        }
        require(prerequisites.distinct().size == prerequisites.size) {
            "Advance prerequisites cannot repeat: $id."
        }
        definitions +=
            AdvanceDefinition(id, category, cost, credit, miseryRelief, prerequisites.toSet())
    }

    internal fun build(): List<AdvanceDefinition> {
        val byId = definitions.associateBy(AdvanceDefinition::id)
        require(byId.keys == AdvanceId.entries.toSet()) { "The catalog requires all 26 advances." }
        for (category in AdvanceCategory.entries) {
            require(
                definitions
                    .filter { advance -> advance.category == category }
                    .sumOf(AdvanceDefinition::cost) == category.totalCost
            ) {
                "Advance costs must match the printed category total for $category."
            }
        }
        fun visit(id: AdvanceId, path: Set<AdvanceId>) {
            require(id !in path) { "Advance prerequisites contain a cycle at $id." }
            for (prerequisite in byId.getValue(id).prerequisites) {
                visit(prerequisite, path + id)
            }
        }
        byId.keys.forEach { id -> visit(id, emptySet()) }
        return definitions.toList()
    }
}

fun advances(declare: AdvanceCatalogBuilder.() -> Unit): List<AdvanceDefinition> =
    AdvanceCatalogBuilder().apply(declare).build()

/** Transcribed from the AH1996 Advance Log, reference bundle pp. 14 and 22–24. */
object AdvanceData {
    val definitions: List<AdvanceDefinition> = advances {
        advance(
            AdvanceId.THE_HEAVENS,
            AdvanceCategory.SCIENCE,
            cost = 30,
            credit = 20,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.HUMAN_BODY,
            AdvanceCategory.SCIENCE,
            cost = 60,
            credit = 20,
            miseryRelief = 10,
        )
        advance(
            AdvanceId.LAWS_OF_MATTER,
            AdvanceCategory.SCIENCE,
            cost = 90,
            credit = 20,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.ENLIGHTENMENT,
            AdvanceCategory.SCIENCE,
            cost = 120,
            credit = 20,
            miseryRelief = 50,
        )
        advance(
            AdvanceId.PATRONAGE,
            AdvanceCategory.RELIGION,
            cost = 30,
            credit = 20,
            miseryRelief = 10,
        )
        advance(
            AdvanceId.HOLY_INDULGENCE,
            AdvanceCategory.RELIGION,
            cost = 60,
            credit = 20,
            miseryRelief = 0,
        )
        advance(
            AdvanceId.PROSELYTISM,
            AdvanceCategory.RELIGION,
            cost = 90,
            credit = 20,
            miseryRelief = 0,
        )
        advance(
            AdvanceId.CATHEDRAL,
            AdvanceCategory.RELIGION,
            cost = 120,
            credit = 20,
            miseryRelief = 25,
            AdvanceId.HOLY_INDULGENCE,
        )
        advance(
            AdvanceId.CARAVAN,
            AdvanceCategory.COMMERCE,
            cost = 20,
            credit = 10,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.WIND_WATERMILL,
            AdvanceCategory.COMMERCE,
            cost = 40,
            credit = 10,
            miseryRelief = 5,
            AdvanceId.CARAVAN,
        )
        advance(
            AdvanceId.IMPROVED_AGRICULTURE,
            AdvanceCategory.COMMERCE,
            cost = 50,
            credit = 10,
            miseryRelief = 25,
            AdvanceId.WIND_WATERMILL,
        )
        advance(
            AdvanceId.INTEREST_PROFIT,
            AdvanceCategory.COMMERCE,
            cost = 80,
            credit = 10,
            miseryRelief = 0,
            AdvanceId.IMPROVED_AGRICULTURE,
        )
        advance(
            AdvanceId.INDUSTRY,
            AdvanceCategory.COMMERCE,
            cost = 110,
            credit = 0,
            miseryRelief = 5,
            AdvanceId.INTEREST_PROFIT,
        )
        advance(
            AdvanceId.WRITTEN_RECORD,
            AdvanceCategory.COMMUNICATION,
            cost = 30,
            credit = 10,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.PRINTED_WORD,
            AdvanceCategory.COMMUNICATION,
            cost = 60,
            credit = 10,
            miseryRelief = 10,
            AdvanceId.WRITTEN_RECORD,
        )
        advance(
            AdvanceId.MASTER_ART,
            AdvanceCategory.COMMUNICATION,
            cost = 90,
            credit = 10,
            miseryRelief = 5,
            AdvanceId.PRINTED_WORD,
        )
        advance(
            AdvanceId.RENAISSANCE,
            AdvanceCategory.COMMUNICATION,
            cost = 120,
            credit = 0,
            miseryRelief = 100,
            AdvanceId.MASTER_ART,
        )
        advance(
            AdvanceId.OVERLAND_EAST,
            AdvanceCategory.EXPLORATION,
            cost = 40,
            credit = 20,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.SEAWORTHY_VESSELS,
            AdvanceCategory.EXPLORATION,
            cost = 80,
            credit = 20,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.OCEAN_NAVIGATION,
            AdvanceCategory.EXPLORATION,
            cost = 120,
            credit = 20,
            miseryRelief = 5,
            AdvanceId.THE_HEAVENS,
            AdvanceId.SEAWORTHY_VESSELS,
        )
        advance(
            AdvanceId.NEW_WORLD,
            AdvanceCategory.EXPLORATION,
            cost = 160,
            credit = 0,
            miseryRelief = 25,
            AdvanceId.OCEAN_NAVIGATION,
            AdvanceId.URBAN_ASCENDANCY,
        )
        advance(
            AdvanceId.URBAN_ASCENDANCY,
            AdvanceCategory.CIVICS,
            cost = 20,
            credit = 10,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.NATIONALISM,
            AdvanceCategory.CIVICS,
            cost = 60,
            credit = 20,
            miseryRelief = 5,
        )
        advance(
            AdvanceId.INSTITUTIONAL_RESEARCH,
            AdvanceCategory.CIVICS,
            cost = 100,
            credit = 30,
            miseryRelief = 10,
        )
        advance(
            AdvanceId.COSMOPOLITAN,
            AdvanceCategory.CIVICS,
            cost = 150,
            credit = 40,
            miseryRelief = 25,
            AdvanceId.OVERLAND_EAST,
        )
        advance(
            AdvanceId.MIDDLE_CLASS,
            AdvanceCategory.CIVICS,
            cost = 170,
            credit = 50,
            miseryRelief = 50,
            AdvanceId.IMPROVED_AGRICULTURE,
        )
    }

    fun definition(advance: AdvanceId): AdvanceDefinition = definitions.single { candidate ->
        candidate.id == advance
    }
}
