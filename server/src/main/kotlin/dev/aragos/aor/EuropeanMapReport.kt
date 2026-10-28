package dev.aragos.aor

/** Offline authoring report; reads static declarations and never opens game storage. */
fun main(args: Array<String>) {
    require(args.isEmpty() || args.toList() == listOf("--full")) {
        "Use no arguments for differences, or --full for the complete resolved draft."
    }
    val base = StandardMapData.definition
    val draft = EuropeanMapData.definition
    println("European map draft is structurally valid; game selection remains disabled.")
    println("Edit core/src/commonMain/kotlin/dev/aragos/aor/EuropeanMapData.kt")
    for (players in 3..draft.capitalOrder.size) {
        println(
            "$players players: ${draft.availableFor(players).size} provinces; " +
                "capitals ${draft.capitalOrder.take(players).joinToString()}"
        )
    }
    println()
    val baseProvinces = base.provinces.associateBy(ProvinceDefinition::id)
    val draftProvinces = draft.provinces.associateBy(ProvinceDefinition::id)
    for (province in draft.provinces) {
        val previous = baseProvinces[province.id] ?: continue
        if (previous == province) continue
        println("Changed province ${province.id}:")
        println("  - ${provinceText(previous)}")
        println("  + ${provinceText(province)}")
    }
    difference(
        "Provinces",
        baseProvinces.keys.map(ProvinceId::value).toSet(),
        draftProvinces.keys.map(ProvinceId::value).toSet(),
    )
    difference(
        "Land borders",
        base.landBorders.map(::edgeText).toSet(),
        draft.landBorders.map(::edgeText).toSet(),
    )
    difference(
        "Coastal-water connections",
        base.coastalConnections.map(::edgeText).toSet(),
        draft.coastalConnections.map(::edgeText).toSet(),
    )
    difference(
        "Support arrows",
        base.satelliteSupports.map(::supportText).toSet(),
        draft.satelliteSupports.map(::supportText).toSet(),
    )
    difference(
        "Sea coasts",
        base.seaCoasts.map(::seaText).toSet(),
        draft.seaCoasts.map(::seaText).toSet(),
    )
    if (base.capitalOrder != draft.capitalOrder) {
        println("Capital order: ${base.capitalOrder} -> ${draft.capitalOrder}")
    }
    for (area in GeographicalArea.entries) {
        if (base.areaMinimumPlayers[area] != draft.areaMinimumPlayers[area]) {
            println(
                "Area $area minimum players: ${base.areaMinimumPlayers[area]} -> " +
                    "${draft.areaMinimumPlayers[area]}"
            )
        }
    }
    for (sea in SeaArea.entries) {
        if (base.seaAreas[sea] != draft.seaAreas[sea]) {
            println("Sea $sea area: ${base.seaAreas[sea]} -> ${draft.seaAreas[sea]}")
        }
    }
    if (base == draft) println("No changes from the standard map yet.")
    if (args.isNotEmpty()) {
        println("\nComplete resolved European draft:")
        draft.provinces.forEach { println(provinceText(it)) }
        section("Land borders", draft.landBorders.map(::edgeText))
        section("Coastal-water connections", draft.coastalConnections.map(::edgeText))
        section("Support arrows", draft.satelliteSupports.map(::supportText))
        section("Sea coasts", draft.seaCoasts.map(::seaText))
        println("Capital order: ${draft.capitalOrder}")
        println("Area minimum players: ${draft.areaMinimumPlayers}")
        println("Sea areas: ${draft.seaAreas}")
    }
}

private fun difference(title: String, before: Set<String>, after: Set<String>) {
    val added = after - before
    val removed = before - after
    println("$title: +${added.size} / -${removed.size}")
    removed.sorted().forEach { println("  - $it") }
    added.sorted().forEach { println("  + $it") }
}

private fun section(title: String, entries: List<String>) {
    println("\n$title:")
    entries.sorted().forEach(::println)
}

private fun provinceText(province: ProvinceDefinition): String =
    "${province.id}: area=${province.area ?: province.overseas}, " +
        "market=${province.marketNumber}, commodities=${province.commodities}, " +
        "coastal=${province.coastal}, capital=${province.capital}, access=${province.access}"

private fun edgeText(edge: ProvinceConnection): String = "${edge.first} -- ${edge.second}"

private fun supportText(edge: SatelliteSupport): String = "${edge.satellite} -> ${edge.market}"

private fun seaText(edge: SeaCoastConnection): String = "${edge.sea} -- ${edge.province}"
