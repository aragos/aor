package dev.aragos.aor

/** Event identifiers are rules identities, independent of localized card names. */
enum class EventId(val cardId: String) {
    ALCHEMISTS_GOLD("alchemists-gold"),
    ARMOR("armor"),
    STIRRUPS("stirrups"),
    CIVIL_WAR("civil-war"),
    ENLIGHTENED_RULER("enlightened-ruler"),
    FAMINE("famine"),
    MYSTICISM_ABOUNDS("mysticism-abounds"),
    PAPAL_DECREE("papal-decree"),
    PIRATES_VIKINGS("pirates-vikings"),
    REBELLION("rebellion"),
    REVOLUTIONARY_UPRISINGS("revolutionary-uprisings"),
    THE_CRUSADES("the-crusades"),
    WAR("war"),
}

enum class LeaderId(val cardId: String) {
    CHARLEMAGNE("charlemagne"),
    DIONYSUS_EXIGUUS("dionysus-exiguus"),
    RASHID_AD_DIN("rashid-ad-din"),
    ST_BENEDICT("st-benedict"),
    WALTER_THE_PENNILESS("walter-the-penniless"),
}

/** Dollar discount; Walter's [crusadesAmount] replaces [amount] after Crusades this turn. */
data class LeaderDiscount(
    val advance: AdvanceId,
    val amount: Int,
    val crusadesAmount: Int? = null,
) {
    init {
        require(amount > 0) { "A leader discount must be positive." }
        require(crusadesAmount == null || crusadesAmount > amount) {
            "The Crusades must increase a conditional leader discount."
        }
    }

    fun amountFor(crusadesPlayed: Boolean): Int =
        if (crusadesPlayed) crusadesAmount ?: amount else amount
}

data class LeaderDefinition(val id: LeaderId, val discounts: List<LeaderDiscount>) {
    init {
        require(discounts.isNotEmpty()) { "A leader must discount at least one advance: $id." }
        require(discounts.map(LeaderDiscount::advance).distinct().size == discounts.size) {
            "A leader must not repeat a discounted advance: $id."
        }
        require(
            id == LeaderId.WALTER_THE_PENNILESS ||
                discounts.all { discount ->
                    discount.crusadesAmount == null
                }
        ) {
            "Only Walter the Penniless has a Crusades discount."
        }
    }
}

@GameDataDsl
class LeaderCatalogBuilder {
    private val definitions = mutableListOf<LeaderDefinition>()

    fun leader(id: LeaderId, vararg discounts: LeaderDiscount) {
        require(definitions.none { definition -> definition.id == id }) {
            "Leader identifiers must be unique: $id."
        }
        definitions += LeaderDefinition(id, discounts.toList())
    }

    internal fun build(): List<LeaderDefinition> {
        require(definitions.map(LeaderDefinition::id).toSet() == LeaderId.entries.toSet()) {
            "The epoch-one leader catalog requires all five leaders."
        }
        return definitions.toList()
    }
}

/** Printed epoch-one leader discounts; AH1996 card text and rules reference section 11.3. */
object LeaderData {
    val definitions: List<LeaderDefinition> =
        LeaderCatalogBuilder()
            .apply {
                leader(LeaderId.CHARLEMAGNE, LeaderDiscount(AdvanceId.NATIONALISM, amount = 20))
                leader(
                    LeaderId.DIONYSUS_EXIGUUS,
                    LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 20),
                )
                leader(
                    LeaderId.RASHID_AD_DIN,
                    LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 10),
                    LeaderDiscount(AdvanceId.OVERLAND_EAST, amount = 10),
                )
                leader(
                    LeaderId.ST_BENEDICT,
                    LeaderDiscount(AdvanceId.WRITTEN_RECORD, amount = 10),
                    LeaderDiscount(AdvanceId.PATRONAGE, amount = 10),
                )
                leader(
                    LeaderId.WALTER_THE_PENNILESS,
                    LeaderDiscount(AdvanceId.OVERLAND_EAST, amount = 20, crusadesAmount = 30),
                )
            }
            .build()

    fun definition(id: LeaderId): LeaderDefinition = definitions.first { leader -> leader.id == id }
}

/** A card's finite rules type. Duplicate physical commodity cards share the same choices. */
sealed interface CardEffectSpec {
    data class CommodityChoices(val choices: Set<Commodity>) : CardEffectSpec {
        init {
            require(choices.size in 1..2) { "A commodity card offers one or two commodities." }
        }
    }

    data class Event(val event: EventId) : CardEffectSpec

    data class Leader(val leader: LeaderId) : CardEffectSpec
}

@GameDataDsl
class CardEffectCatalogBuilder {
    private val definitions = linkedMapOf<String, CardEffectSpec>()

    fun commodity(id: String, vararg choices: Commodity) {
        require(choices.distinct().size == choices.size) {
            "A commodity card must not repeat a choice: $id."
        }
        add(id, CardEffectSpec.CommodityChoices(choices.toSet()))
    }

    fun event(event: EventId) = add(event.cardId, CardEffectSpec.Event(event))

    fun leader(leader: LeaderId) = add(leader.cardId, CardEffectSpec.Leader(leader))

    private fun add(id: String, effect: CardEffectSpec) {
        require(id !in definitions) { "Card effect identifiers must be unique: $id." }
        definitions[id] = effect
    }

    internal fun build(cards: List<CardDefinition>): Map<String, CardEffectSpec> {
        require(definitions.keys == cards.map(CardDefinition::id).toSet()) {
            "Every physical epoch-one card requires exactly one effect definition."
        }
        for (card in cards) {
            val expectedKind =
                when (definitions.getValue(card.id)) {
                    is CardEffectSpec.CommodityChoices -> CardKind.COMMODITY
                    is CardEffectSpec.Event -> CardKind.EVENT
                    is CardEffectSpec.Leader -> CardKind.LEADER
                }
            require(card.kind == expectedKind) {
                "The effect must match the physical card's kind: ${card.id}."
            }
        }
        return definitions.toMap()
    }
}

object CardEffectData {
    val epochOne: Map<String, CardEffectSpec> =
        CardEffectCatalogBuilder()
            .apply {
                commodity("stone-1", Commodity.STONE)
                commodity("stone-2", Commodity.STONE)
                commodity("wool-1", Commodity.WOOL)
                commodity("wool-2", Commodity.WOOL)
                commodity("timber", Commodity.TIMBER)
                commodity("cloth-wine", Commodity.CLOTH, Commodity.WINE)
                commodity("ivory-gold", Commodity.IVORY, Commodity.GOLD)
                commodity("metal", Commodity.METAL)
                commodity("fur", Commodity.FUR)
                commodity("silk", Commodity.SILK)
                commodity("spice", Commodity.SPICE)
                EventId.entries.forEach(::event)
                LeaderId.entries.forEach(::leader)
            }
            .build(CardData.epochOne)

    fun forCard(cardId: String): CardEffectSpec =
        requireNotNull(epochOne[cardId]) {
            "Unknown epoch-one card: $cardId."
        }
}
