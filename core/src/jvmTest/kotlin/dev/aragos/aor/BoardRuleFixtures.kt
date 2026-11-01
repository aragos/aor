package dev.aragos.aor

/** Coloured markers on active three-player markets, always including Venice. */
internal fun markerSupplyBoard(owner: PlayerId, count: Int): BoardState {
    require(count in 1..25) { "A marker-supply fixture requires one through 25 markers." }
    val venice = ProvinceId("venice")
    val provinces =
        listOf(venice) +
            CatalogConfiguration(3)
                .provinces
                .filter { province -> !province.isSatellite && province.id != venice }
                .take(count - 1)
                .map(ProvinceDefinition::id)
    return provinces.fold(BoardState()) { board, province ->
        board.placeMarker(province, owner).board
    }
}
