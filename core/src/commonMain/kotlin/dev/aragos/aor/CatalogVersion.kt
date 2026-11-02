package dev.aragos.aor

/** Recorded catalog identity. Existing games retain their topology throughout replay and play. */
enum class CatalogVersion(val wireValue: String) {
    CATALOG_13("ah1996-catalog-13"),
    CATALOG_14(DATA_VERSION);

    fun mapDefinition(map: MapRule): BoardMapDefinition =
        when (this) {
            CATALOG_13 -> legacyMaps[map] ?: error("Missing catalog-13 map: $map.")
            CATALOG_14 -> map.definition
        }

    companion object {
        fun fromWire(value: String): CatalogVersion =
            requireNotNull(entries.singleOrNull { it.wireValue == value }) {
                "Unsupported data version: $value."
            }

        private val legacyMaps: Map<MapRule, BoardMapDefinition> by lazy {
            MapRule.entries.associateWith { map ->
                boardMap(map.definition) {
                    removeConnections { coast("cornwall", "chester") }
                    addConnections { land("wales", "cornwall") }
                }
            }
        }
    }
}
