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

/**
 * [ownershipTrackBoxes] counts the board's commodity ownership boxes, excluding zero. It is not a
 * payment limit. Commodity cards print ten quadratic payment boxes (AH1996 section 7.11).
 */
data class CommodityDefinition(
    val id: Commodity,
    val baseValue: Int,
    val diceTotal: Int,
    val ownershipTrackBoxes: Int,
) {
    val messageId: String
        get() = "commodity.${id.name.lowercase()}"

    /** Cards introduced by each epoch, in epoch order; shared cards count for both choices. */
    fun cardsByEpoch(rules: RuleOptions): List<Int> {
        val matching = cardIds()
        return Epoch.entries.map { epoch ->
            rules.cardsForEpoch(epoch).count { card -> card in matching }
        }
    }

    internal fun cardIds(): Set<String> =
        CardData.all
            .filter { card ->
                val effect = CardEffectData.forCard(card.id) as? CardEffectSpec.CommodityChoices
                effect?.choices?.contains(id) == true
            }
            .map(CardDefinition::id)
            .toSet()

    val printedPayouts: List<Int>
        get() = (1..10).map { box -> baseValue * box * box }

    init {
        require(baseValue in 1..10) { "Commodity base value must be 1 through 10: $id." }
        require(diceTotal in 2..12) { "Commodity dice total must be 2 through 12: $id." }
        require(ownershipTrackBoxes > 0) {
            "A commodity requires at least one printed ownership box: $id."
        }
    }
}

@GameDataDsl
class CommodityCatalogBuilder {
    private val definitions = mutableListOf<CommodityDefinition>()

    fun commodity(id: Commodity, baseValue: Int, diceTotal: Int, ownershipTrackBoxes: Int) {
        require(definitions.none { definition -> definition.id == id }) {
            "Commodity identifiers must be unique: $id."
        }
        definitions += CommodityDefinition(id, baseValue, diceTotal, ownershipTrackBoxes)
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
        commodity(Commodity.STONE, baseValue = 1, diceTotal = 2, ownershipTrackBoxes = 8)
        commodity(Commodity.WOOL, baseValue = 2, diceTotal = 3, ownershipTrackBoxes = 9)
        commodity(Commodity.TIMBER, baseValue = 3, diceTotal = 4, ownershipTrackBoxes = 7)
        commodity(Commodity.GRAIN, baseValue = 4, diceTotal = 5, ownershipTrackBoxes = 8)
        commodity(Commodity.CLOTH, baseValue = 5, diceTotal = 6, ownershipTrackBoxes = 8)
        commodity(Commodity.WINE, baseValue = 5, diceTotal = 7, ownershipTrackBoxes = 6)
        commodity(Commodity.METAL, baseValue = 6, diceTotal = 8, ownershipTrackBoxes = 7)
        commodity(Commodity.FUR, baseValue = 7, diceTotal = 9, ownershipTrackBoxes = 5)
        commodity(Commodity.SILK, baseValue = 8, diceTotal = 10, ownershipTrackBoxes = 6)
        commodity(Commodity.SPICE, baseValue = 9, diceTotal = 11, ownershipTrackBoxes = 7)
        commodity(Commodity.GOLD, baseValue = 10, diceTotal = 12, ownershipTrackBoxes = 5)
        commodity(Commodity.IVORY, baseValue = 10, diceTotal = 12, ownershipTrackBoxes = 4)
    }
}
