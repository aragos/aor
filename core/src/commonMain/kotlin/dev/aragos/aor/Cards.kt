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
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Card id must use lowercase letters, digits or hyphens, starting with a letter."
        }
        require(messageId.isNotBlank()) { "A card requires a presentation message id." }
    }
}

@GameDataDsl
class CardCatalogBuilder {
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
        definitions += CardDefinition(id = id, kind = kind, messageId = messageId, shape = shape)
    }
}

fun cards(declare: CardCatalogBuilder.() -> Unit): List<CardDefinition> =
    CardCatalogBuilder().apply(declare).build()

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
}
