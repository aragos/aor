package dev.aragos.aor

data class CapitalDefinition(val id: String, val messageId: String) {
    init {
        require(id.matches(Regex("[a-z][a-z-]*"))) {
            "Capital id must contain lowercase letters and hyphens, starting with a letter."
        }
        require(messageId.isNotBlank()) { "A capital requires a presentation message id." }
    }
}

@GameDataDsl
class CapitalCatalogBuilder {
    private val definitions = mutableListOf<CapitalDefinition>()

    fun capital(id: String) {
        require(definitions.none { definition -> definition.id == id }) {
            "Capital id must be unique: $id."
        }
        definitions += CapitalDefinition(id = id, messageId = "capital.$id")
    }

    internal fun build(): List<CapitalDefinition> = definitions.toList()
}

fun capitals(declare: CapitalCatalogBuilder.() -> Unit): List<CapitalDefinition> =
    CapitalCatalogBuilder().apply(declare).build()

object CapitalData {
    /** Registration-count order: the first three capitals are always in play. */
    val definitions: List<CapitalDefinition> = capitals {
        capital("venice")
        capital("genoa")
        capital("barcelona")
        capital("paris")
        capital("london")
        capital("hamburg")
    }

    fun availableFor(playerCount: Int): List<String> {
        require(playerCount in 3..6) { "Capital availability requires 3 through 6 players." }
        return definitions.take(playerCount).map(CapitalDefinition::id)
    }
}
