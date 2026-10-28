package dev.aragos.aor

/**
 * EDIT THIS FILE to describe the European map. It currently inherits the complete standard map.
 * Uncomment/adapt the examples below; they illustrate the DSL, not proposed European rules. Run
 * ./gradlew :server:describeEuropeanMap to validate and print the complete differences. Authoring
 * this draft does not enable it for play; rules and drawing geometry still need review.
 */
object EuropeanMapData {
    val definition: BoardMapDefinition by lazy {
        boardMap(StandardMapData.definition) {
            // 1. Remove provinces. All their inherited land/coast/sea/support links go with them.
            // removeProvince("west-africa")

            // 2. Change existing province facts; other fields and links are preserved.
            // changeProvince("vienna") {
            //     copy(marketNumber = 3, commodities = setOf(Commodity.METAL))
            // }

            // 3. Add provinces using the standard catalog DSL.
            // addProvinces {
            //     area(GeographicalArea.VII) {
            //         market("trieste", number = 3, commodity = Commodity.METAL)
            //         satellite("istria")
            //     }
            // }

            // 4. Remove named inherited links; add new links in a separate block.
            // removeConnections {
            //     land("venice", "milan")
            //     coast("venice", "florence")
            //     supports("durazzo", "belgrade")
            //     sea(SeaArea.ADRIATIC_SEA, "belgrade")
            // }
            // addConnections {
            //     land("trieste", "venice", "istria")
            //     coast("trieste", "venice", "istria")
            //     supports("istria", "trieste")
            //     sea(SeaArea.ADRIATIC_SEA, "trieste", "istria")
            // }

            // 5. Optional setup/geographical changes.
            // capitals("venice", "genoa", "barcelona", "paris", "london", "hamburg")
            // minimumPlayers(GeographicalArea.V, count = 4)
            // seaArea(SeaArea.ADRIATIC_SEA, GeographicalArea.VIII)
        }
    }
}
