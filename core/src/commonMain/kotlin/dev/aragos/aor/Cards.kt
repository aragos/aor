package dev.aragos.aor

/** The finite presentation and rules categories for history cards. */
enum class CardKind {
    COMMODITY,
    EVENT,
    LEADER,
}

/** The printed epoch symbol determines whether a used card joins the next epoch's deck. */
enum class CardShape(val recycledOnEpochChange: Boolean) {
    CIRCLE(true),
    SQUARE(false),
}

/**
 * One physical card, identified by lowercase letters, digits and hyphens starting with a letter.
 *
 * [messageId] is the translation resource key; copies of a commodity share that key but have
 * distinct [id] values. [shape] specifies the printed epoch symbol and its recycling behavior.
 */
data class CardDefinition(
    val id: String,
    val kind: CardKind,
    val messageId: String,
    val shape: CardShape,
    val epoch: Epoch = Epoch.ONE,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Card id must use lowercase letters, digits or hyphens, starting with a letter."
        }
        require(messageId.isNotBlank()) { "A card requires a presentation message id." }
    }
}

@GameDataDsl
class CardCatalogBuilder(private val epoch: Epoch = Epoch.ONE) {
    private val definitions = mutableListOf<CardDefinition>()

    /** Declares one commodity instance; copies have distinct ids and share [messageId]. */
    fun commodity(id: String, messageId: String = "card.$id") {
        add(id = id, kind = CardKind.COMMODITY, messageId = messageId, shape = CardShape.CIRCLE)
    }

    fun event(id: String, shape: CardShape) {
        add(id = id, kind = CardKind.EVENT, messageId = "card.$id", shape = shape)
    }

    fun leader(id: String, shape: CardShape) {
        add(id = id, kind = CardKind.LEADER, messageId = "card.$id", shape = shape)
    }

    internal fun build(): List<CardDefinition> = definitions.toList()

    private fun add(id: String, kind: CardKind, messageId: String, shape: CardShape) {
        require(definitions.none { definition -> definition.id == id }) {
            "Card instance id must be unique: $id."
        }
        definitions +=
            CardDefinition(
                id = id,
                kind = kind,
                messageId = messageId,
                shape = shape,
                epoch = epoch,
            )
    }
}

fun cards(
    epoch: Epoch = Epoch.ONE,
    declare: CardCatalogBuilder.() -> Unit,
): List<CardDefinition> = CardCatalogBuilder(epoch).apply(declare).build()

object CardData {
    val epochOne: List<CardDefinition> = cards {
        commodity("stone-1", messageId = "card.stone")
        commodity("stone-2", messageId = "card.stone")
        commodity("wool-1", messageId = "card.wool")
        commodity("wool-2", messageId = "card.wool")
        commodity("timber")
        commodity("cloth-wine")
        commodity("ivory-gold")
        commodity("metal")
        commodity("fur")
        commodity("silk")
        commodity("spice")
        event("alchemists-gold", shape = CardShape.CIRCLE)
        event("armor", shape = CardShape.SQUARE)
        event("stirrups", shape = CardShape.SQUARE)
        event("civil-war", shape = CardShape.CIRCLE)
        event("enlightened-ruler", shape = CardShape.CIRCLE)
        event("famine", shape = CardShape.CIRCLE)
        event("mysticism-abounds", shape = CardShape.CIRCLE)
        event("papal-decree", shape = CardShape.CIRCLE)
        event("pirates-vikings", shape = CardShape.CIRCLE)
        event("rebellion", shape = CardShape.CIRCLE)
        event("revolutionary-uprisings", shape = CardShape.CIRCLE)
        event("the-crusades", shape = CardShape.CIRCLE)
        event("war", shape = CardShape.CIRCLE)
        leader("charlemagne", shape = CardShape.SQUARE)
        leader("dionysus-exiguus", shape = CardShape.SQUARE)
        leader("rashid-ad-din", shape = CardShape.SQUARE)
        leader("st-benedict", shape = CardShape.SQUARE)
        leader("walter-the-penniless", shape = CardShape.SQUARE)
    }
        .also { definitions ->
            require(definitions.size == 29) { "The standard epoch-one deck must contain 29 cards." }
        }

    val epochTwo: List<CardDefinition> =
        cards(Epoch.TWO) {
            commodity("timber-2-1", messageId = "card.timber")
            commodity("timber-2-2", messageId = "card.timber")
            commodity("grain-2-1", messageId = "card.grain")
            commodity("grain-2-2", messageId = "card.grain")
            commodity("cloth-2", messageId = "card.cloth")
            commodity("wine-2", messageId = "card.wine")
            commodity("metal-2", messageId = "card.metal")
            commodity("silk-2", messageId = "card.silk")
            commodity("spice-2", messageId = "card.spice")
            event("black-death", shape = CardShape.CIRCLE)
            event("gunpowder", shape = CardShape.SQUARE)
            event("long-bow", shape = CardShape.SQUARE)
            event("mongol-armies", shape = CardShape.SQUARE)
            event("religious-strife", shape = CardShape.CIRCLE)
            leader("christopher-columbus", shape = CardShape.SQUARE)
            leader("desiderius-erasmus", shape = CardShape.SQUARE)
            leader("ibn-majid", shape = CardShape.SQUARE)
            leader("johann-gutenberg", shape = CardShape.SQUARE)
            leader("marco-polo", shape = CardShape.SQUARE)
            leader("nicolaus-copernicus", shape = CardShape.SQUARE)
            leader("prince-henry", shape = CardShape.SQUARE)
            leader("william-caxton", shape = CardShape.SQUARE)
        }

    val epochThree: List<CardDefinition> =
        cards(Epoch.THREE) {
            commodity("cloth-3", messageId = "card.cloth")
            commodity("wine-3", messageId = "card.wine")
            commodity("metal-3", messageId = "card.metal")
            commodity("fur-3", messageId = "card.fur")
            commodity("silk-3", messageId = "card.silk")
            commodity("spice-3", messageId = "card.spice")
            commodity("gold-3", messageId = "card.gold")
            leader("andreas-vesalius", shape = CardShape.SQUARE)
            leader("bartolome-de-las-casas", shape = CardShape.SQUARE)
            leader("galileo-galilei", shape = CardShape.SQUARE)
            leader("henry-oldenburg", shape = CardShape.SQUARE)
            leader("leonardo-da-vinci", shape = CardShape.SQUARE)
            leader("sir-isaac-newton", shape = CardShape.SQUARE)
        }

    val all: List<CardDefinition> =
        (epochOne + epochTwo + epochThree).also { cards ->
            require(cards.size == 64 && cards.map(CardDefinition::id).distinct().size == 64) {
                "The complete history deck requires 64 distinct physical cards."
            }
            require(listOf(epochOne.size, epochTwo.size, epochThree.size) == listOf(29, 22, 13)) {
                "Printed epoch decks must contain 29, 22 and 13 cards."
            }
        }

    fun forEpoch(epoch: Epoch): List<CardDefinition> =
        when (epoch) {
            Epoch.ONE -> epochOne
            Epoch.TWO -> epochTwo
            Epoch.THREE -> epochThree
        }

    fun definition(id: String): CardDefinition =
        requireNotNull(all.find { card -> card.id == id }) {
            "Unknown physical card: $id."
        }
}
