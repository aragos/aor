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
    BLACK_DEATH("black-death"),
    GUNPOWDER("gunpowder"),
    LONG_BOW("long-bow"),
    MONGOL_ARMIES("mongol-armies"),
    RELIGIOUS_STRIFE("religious-strife"),
}

enum class LeaderId(val cardId: String) {
    CHARLEMAGNE("charlemagne"),
    DIONYSUS_EXIGUUS("dionysus-exiguus"),
    RASHID_AD_DIN("rashid-ad-din"),
    ST_BENEDICT("st-benedict"),
    WALTER_THE_PENNILESS("walter-the-penniless"),
    CHRISTOPHER_COLUMBUS("christopher-columbus"),
    DESIDERIUS_ERASMUS("desiderius-erasmus"),
    IBN_MAJID("ibn-majid"),
    JOHANN_GUTENBERG("johann-gutenberg"),
    MARCO_POLO("marco-polo"),
    NICOLAUS_COPERNICUS("nicolaus-copernicus"),
    PRINCE_HENRY("prince-henry"),
    WILLIAM_CAXTON("william-caxton"),
    ANDREAS_VESALIUS("andreas-vesalius"),
    BARTOLOME_DE_LAS_CASAS("bartolome-de-las-casas"),
    GALILEO_GALILEI("galileo-galilei"),
    HENRY_OLDENBURG("henry-oldenburg"),
    LEONARDO_DA_VINCI("leonardo-da-vinci"),
    SIR_ISAAC_NEWTON("sir-isaac-newton"),
}

/** The event timing that enables a leader's printed increased credit. */
enum class LeaderDiscountCondition {
    CRUSADES_THIS_TURN,
    MONGOL_ARMIES_BEFORE_PLAY,
}

/** Dollar credits; [increasedAmount] replaces [amount] when the printed [condition] applies. */
data class LeaderDiscount(
    val advance: AdvanceId,
    val amount: Int,
    val condition: LeaderDiscountCondition? = null,
    val increasedAmount: Int? = null,
) {
    init {
        require(amount > 0) { "A leader discount must be positive." }
        require((condition == null) == (increasedAmount == null)) {
            "An increased leader discount requires both an amount and a condition."
        }
        require(increasedAmount == null || increasedAmount > amount) {
            "A conditional leader discount must exceed its ordinary amount."
        }
    }

    fun amountFor(crusadesPlayed: Boolean, mongolArmiesPlayed: Boolean = false): Int {
        val applies =
            when (condition) {
                LeaderDiscountCondition.CRUSADES_THIS_TURN -> crusadesPlayed
                LeaderDiscountCondition.MONGOL_ARMIES_BEFORE_PLAY -> mongolArmiesPlayed
                null -> false
            }
        return if (applies)
            requireNotNull(increasedAmount) {
                "A conditional leader discount has an increased amount."
            }
        else amount
    }
}

data class LeaderDefinition(val id: LeaderId, val discounts: List<LeaderDiscount>) {
    init {
        require(discounts.isNotEmpty()) { "A leader must discount at least one advance: $id." }
        require(discounts.map(LeaderDiscount::advance).distinct().size == discounts.size) {
            "A leader must not repeat a discounted advance: $id."
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
            "The leader catalog requires all nineteen leaders."
        }
        return definitions.toList()
    }
}

/** Printed leader discounts; AH1996 card text and rules reference section 11.3. */
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
                    LeaderDiscount(
                        AdvanceId.OVERLAND_EAST,
                        amount = 20,
                        condition = LeaderDiscountCondition.CRUSADES_THIS_TURN,
                        increasedAmount = 30,
                    ),
                )
                leader(
                    LeaderId.CHRISTOPHER_COLUMBUS,
                    LeaderDiscount(AdvanceId.NEW_WORLD, amount = 30),
                    LeaderDiscount(AdvanceId.OCEAN_NAVIGATION, amount = 30),
                )
                leader(
                    LeaderId.DESIDERIUS_ERASMUS,
                    LeaderDiscount(AdvanceId.PRINTED_WORD, amount = 20),
                    LeaderDiscount(AdvanceId.RENAISSANCE, amount = 20),
                )
                leader(
                    LeaderId.IBN_MAJID,
                    LeaderDiscount(AdvanceId.OCEAN_NAVIGATION, amount = 20),
                    LeaderDiscount(AdvanceId.COSMOPOLITAN, amount = 20),
                )
                leader(
                    LeaderId.JOHANN_GUTENBERG,
                    LeaderDiscount(AdvanceId.PRINTED_WORD, amount = 30),
                )
                leader(
                    LeaderId.MARCO_POLO,
                    LeaderDiscount(
                        AdvanceId.COSMOPOLITAN,
                        amount = 20,
                        condition = LeaderDiscountCondition.MONGOL_ARMIES_BEFORE_PLAY,
                        increasedAmount = 40,
                    ),
                    LeaderDiscount(
                        AdvanceId.OVERLAND_EAST,
                        amount = 20,
                        condition = LeaderDiscountCondition.MONGOL_ARMIES_BEFORE_PLAY,
                        increasedAmount = 40,
                    ),
                )
                leader(
                    LeaderId.NICOLAUS_COPERNICUS,
                    LeaderDiscount(AdvanceId.THE_HEAVENS, amount = 20),
                    LeaderDiscount(AdvanceId.INSTITUTIONAL_RESEARCH, amount = 20),
                )
                leader(
                    LeaderId.PRINCE_HENRY,
                    LeaderDiscount(AdvanceId.OCEAN_NAVIGATION, amount = 20),
                    LeaderDiscount(AdvanceId.INSTITUTIONAL_RESEARCH, amount = 20),
                )
                leader(LeaderId.WILLIAM_CAXTON, LeaderDiscount(AdvanceId.PRINTED_WORD, amount = 20))
                leader(
                    LeaderId.ANDREAS_VESALIUS,
                    LeaderDiscount(AdvanceId.HUMAN_BODY, amount = 20),
                    LeaderDiscount(AdvanceId.ENLIGHTENMENT, amount = 20),
                )
                leader(
                    LeaderId.BARTOLOME_DE_LAS_CASAS,
                    LeaderDiscount(AdvanceId.COSMOPOLITAN, amount = 30),
                )
                leader(
                    LeaderId.GALILEO_GALILEI,
                    LeaderDiscount(AdvanceId.THE_HEAVENS, amount = 20),
                    LeaderDiscount(AdvanceId.RENAISSANCE, amount = 20),
                )
                leader(
                    LeaderId.HENRY_OLDENBURG,
                    LeaderDiscount(AdvanceId.ENLIGHTENMENT, amount = 30),
                )
                leader(
                    LeaderId.LEONARDO_DA_VINCI,
                    LeaderDiscount(AdvanceId.MASTER_ART, amount = 20),
                    LeaderDiscount(AdvanceId.HUMAN_BODY, amount = 20),
                    LeaderDiscount(AdvanceId.RENAISSANCE, amount = 20),
                )
                leader(
                    LeaderId.SIR_ISAAC_NEWTON,
                    LeaderDiscount(AdvanceId.LAWS_OF_MATTER, amount = 20),
                    LeaderDiscount(AdvanceId.ENLIGHTENMENT, amount = 20),
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
            "Every physical card requires exactly one effect definition."
        }
        for (card in cards) {
            val expectedKind =
                when (
                    requireNotNull(definitions[card.id]) {
                        "Every card must have an effect definition: ${card.id}."
                    }
                ) {
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
    val all: Map<String, CardEffectSpec> =
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
                commodity("timber-2-1", Commodity.TIMBER)
                commodity("timber-2-2", Commodity.TIMBER)
                commodity("grain-2-1", Commodity.GRAIN)
                commodity("grain-2-2", Commodity.GRAIN)
                commodity("cloth-2", Commodity.CLOTH)
                commodity("wine-2", Commodity.WINE)
                commodity("metal-2", Commodity.METAL)
                commodity("silk-2", Commodity.SILK)
                commodity("spice-2", Commodity.SPICE)
                commodity("cloth-3", Commodity.CLOTH)
                commodity("wine-3", Commodity.WINE)
                commodity("metal-3", Commodity.METAL)
                commodity("fur-3", Commodity.FUR)
                commodity("silk-3", Commodity.SILK)
                commodity("spice-3", Commodity.SPICE)
                commodity("gold-3", Commodity.GOLD)
                EventId.entries.forEach(::event)
                LeaderId.entries.forEach(::leader)
            }
            .build(CardData.all)

    val epochOne: Map<String, CardEffectSpec> = all.filterKeys { id ->
        CardData.definition(id).epoch == Epoch.ONE
    }

    fun forCard(cardId: String): CardEffectSpec =
        requireNotNull(all[cardId]) {
            "Unknown physical card: $cardId."
        }
}
