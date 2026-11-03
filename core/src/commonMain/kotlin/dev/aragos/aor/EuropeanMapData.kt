package dev.aragos.aor

/**
 * European map differences from the standard board, selected with `set-option map european`.
 * Commented examples illustrate additional authoring operations. Run ./gradlew
 * :server:describeEuropeanMap to validate and print the complete differences. Regenerate and
 * validate europeanBoardGeometry.json after editing; see docs/map-design.md.
 */
object EuropeanMapData {
    val definition: BoardMapDefinition by lazy {
        boardMap(StandardMapData.definition) {
            // 1. Remove provinces. All their inherited land/coast/sea/support links go with them.
            // removeProvince("west-africa")
            removeProvince("smolensk")

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

            addProvinces {
                area(GeographicalArea.III) {
                    market("loire", number = 3, commodity = Commodity.WINE, coastal = false)
                }
            }

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

            removeConnections {
                sea(SeaArea.EASTERN_MEDITERRANEAN, "crete", "libya", "barca")
                land(from = "st-malo", "bordeaux")
            }
            addConnections {
                land(from = "london", "chester")
                land(from = "loire", "st-malo", "paris", "bordeaux")
                land(from = "novgorod", "kiev")

                sea(SeaArea.CENTRAL_MEDITERRANEAN, "libya", "alexandria")
            }

            mergeSea(SeaArea.AEGEAN_SEA, into = SeaArea.CENTRAL_MEDITERRANEAN)

            // 5. Optional setup/geographical changes.
            // capitals("venice", "genoa", "barcelona", "paris", "london", "hamburg")
            // minimumPlayers(GeographicalArea.V, count = 4)
            // seaArea(SeaArea.ADRIATIC_SEA, GeographicalArea.VIII)
        }
    }
}
