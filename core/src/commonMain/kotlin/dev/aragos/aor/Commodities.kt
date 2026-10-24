package dev.aragos.aor

enum class Commodity {
    STONE,
    WOOL,
    TIMBER,
    GRAIN,
    CLOTH,
    WINE,
    METAL,
    FUR,
    SILK,
    SPICE,
    GOLD,
    IVORY,
}

/** [payoutBoxes] counts printed boxes, excluding a zero-payout position. */
data class CommodityDefinition(
    val id: Commodity,
    val baseValue: Int,
    val diceTotal: Int,
    val payoutBoxes: Int,
) {
    val messageId: String
        get() = "commodity.${id.name.lowercase()}"

    val printedPayouts: List<Int>
        get() = (1..payoutBoxes).map { box -> baseValue * box * box }

    init {
        require(baseValue in 1..10) { "Commodity base value must be 1 through 10: $id." }
        require(diceTotal in 2..12) { "Commodity dice total must be 2 through 12: $id." }
        require(payoutBoxes > 0) { "A commodity requires at least one printed payout box: $id." }
    }
}

@GameDataDsl
class CommodityCatalogBuilder {
    private val definitions = mutableListOf<CommodityDefinition>()

    fun commodity(id: Commodity, baseValue: Int, diceTotal: Int, payoutBoxes: Int) {
        require(definitions.none { definition -> definition.id == id }) {
            "Commodity identifiers must be unique: $id."
        }
        definitions += CommodityDefinition(id, baseValue, diceTotal, payoutBoxes)
    }

    internal fun build(): List<CommodityDefinition> {
        require(definitions.map(CommodityDefinition::id).toSet() == Commodity.entries.toSet()) {
            "The commodity catalog requires all twelve commodities."
        }
        val byRoll = definitions.groupBy(CommodityDefinition::diceTotal)
        require(byRoll.keys == (2..12).toSet()) {
            "Commodity rolls must cover every two-dice total."
        }
        val sharedRollCommodities = byRoll[12].orEmpty().map(CommodityDefinition::id).toSet()
        require(
            sharedRollCommodities == setOf(Commodity.GOLD, Commodity.IVORY) &&
                byRoll.filterKeys { roll -> roll != 12 }.values.all { row -> row.size == 1 }
        ) {
            "Only Gold and Ivory share a commodity roll, at twelve."
        }
        return definitions.toList()
    }
}

fun commodities(declare: CommodityCatalogBuilder.() -> Unit): List<CommodityDefinition> =
    CommodityCatalogBuilder().apply(declare).build()

/** Printed commodity log on board photograph BGG 192678; see docs/data-inventory.md. */
object CommodityData {
    val definitions: List<CommodityDefinition> = commodities {
        commodity(Commodity.STONE, baseValue = 1, diceTotal = 2, payoutBoxes = 8)
        commodity(Commodity.WOOL, baseValue = 2, diceTotal = 3, payoutBoxes = 9)
        commodity(Commodity.TIMBER, baseValue = 3, diceTotal = 4, payoutBoxes = 7)
        commodity(Commodity.GRAIN, baseValue = 4, diceTotal = 5, payoutBoxes = 8)
        commodity(Commodity.CLOTH, baseValue = 5, diceTotal = 6, payoutBoxes = 8)
        commodity(Commodity.WINE, baseValue = 5, diceTotal = 7, payoutBoxes = 6)
        commodity(Commodity.METAL, baseValue = 6, diceTotal = 8, payoutBoxes = 7)
        commodity(Commodity.FUR, baseValue = 7, diceTotal = 9, payoutBoxes = 5)
        commodity(Commodity.SILK, baseValue = 8, diceTotal = 10, payoutBoxes = 6)
        commodity(Commodity.SPICE, baseValue = 9, diceTotal = 11, payoutBoxes = 7)
        commodity(Commodity.GOLD, baseValue = 10, diceTotal = 12, payoutBoxes = 5)
        commodity(Commodity.IVORY, baseValue = 10, diceTotal = 12, payoutBoxes = 4)
    }
}
