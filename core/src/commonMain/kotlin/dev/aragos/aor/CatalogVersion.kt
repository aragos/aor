package dev.aragos.aor

/** Recorded catalog identity. Existing games retain their topology throughout replay and play. */
enum class CatalogVersion(val wireValue: String) {
    CATALOG_13("ah1996-catalog-13"),
    CATALOG_14("ah1996-catalog-14"),
    CATALOG_15(DATA_VERSION);

    fun mapDefinition(map: MapRule): BoardMapDefinition =
        when (this) {
            CATALOG_13 -> catalog13Maps[map] ?: error("Missing catalog-13 map: $map.")
            CATALOG_14 -> catalog14Maps[map] ?: error("Missing catalog-14 map: $map.")
            CATALOG_15 -> map.definition
        }

    companion object {
        fun fromWire(value: String): CatalogVersion =
            requireNotNull(entries.singleOrNull { it.wireValue == value }) {
                "Unsupported data version: $value."
            }

        private val catalog14Maps: Map<MapRule, BoardMapDefinition> by lazy {
            MapRule.entries.associateWith { map ->
                boardMap(map.definition) {
                    removeConnections { coast("chester", "portsmouth") }
                }
            }
        }

        private val catalog13Maps: Map<MapRule, BoardMapDefinition> by lazy {
            MapRule.entries.associateWith { map ->
                val previous = catalog14Maps[map] ?: error("Missing catalog-14 map: $map.")
                boardMap(previous) {
                    removeConnections { coast("cornwall", "chester") }
                    addConnections { land("wales", "cornwall") }
                }
            }
        }
    }
}
