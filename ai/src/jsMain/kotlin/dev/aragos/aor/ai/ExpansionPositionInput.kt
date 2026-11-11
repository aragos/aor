package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardPlayerProfile
import dev.aragos.aor.CatalogVersion
import dev.aragos.aor.ExpansionPositionView
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.MarkerView
import dev.aragos.aor.MilitaryCard
import dev.aragos.aor.MilitaryPlay
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvinceView
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.ShippingFleet
import dev.aragos.aor.TokenView
import kotlin.js.jsTypeOf

/** Decode optional public facts; legality and reconstruction remain with the core owner. */
internal fun readExpansionPosition(raw: dynamic): ExpansionPositionView? {
    if (raw == null) return null
    val rules = raw.rules
    val result =
        ExpansionPositionView(
            playerCount = planningInteger(raw.playerCount, "initial player count"),
            rules =
                RuleOptions(
                    deckModification = expansionBoolean(rules.deckModification),
                    initialCard = InitialCardRule.valueOf(rules.initialCard as String),
                    immediateMasterArtDiscard = expansionBoolean(rules.immediateMasterArtDiscard),
                    map = MapRule.valueOf(rules.map as String),
                    drawThreeKeepTwo = expansionBoolean(rules.drawThreeKeepTwo),
                ),
            catalogVersion = CatalogVersion.valueOf(raw.catalogVersion as String),
            board =
                planningArray(raw.board, "public board").map { province ->
                    ProvinceView(
                        id = ProvinceId(province.id as String),
                        marker =
                            if (province.marker == null) null
                            else
                                MarkerView(
                                    PlayerId(province.marker.owner as String),
                                    PieceSide.valueOf(province.marker.side as String),
                                ),
                        tokens =
                            planningArray(province.tokens, "public tokens").map { token ->
                                TokenView(
                                    PlayerId(token.owner as String),
                                    planningInteger(token.coloured, "coloured tokens"),
                                    planningInteger(token.white, "white tokens"),
                                )
                            },
                    )
                },
            retainedColouredBases =
                planningArray(raw.retainedColouredBases, "retained bases")
                    .map { ProvinceId(it as String) }
                    .toSet(),
            players =
                planningArray(raw.players, "public player profiles").map { profile ->
                    BoardPlayerProfile(
                        player = PlayerId(profile.player as String),
                        capital = ProvinceId(profile.capital as String),
                        advances =
                            planningArray(profile.advances, "public advances")
                                .map { AdvanceId.valueOf(it as String) }
                                .toSet(),
                        ships = expansionShips(profile.ships),
                        chaos = expansionBoolean(profile.chaos),
                    )
                },
            shipped =
                expansionKeys(raw.shipped).associate { owner ->
                    PlayerId(owner) to
                        expansionKeys(raw.shipped[owner]).associate { destination ->
                            ProvinceId(destination) to
                                planningInteger(raw.shipped[owner][destination], "shipment")
                        }
                },
            militaryPlays =
                planningArray(raw.militaryPlays, "public military plays").map {
                    MilitaryPlay(
                        PlayerId(it.player as String),
                        MilitaryCard.valueOf(it.card as String),
                    )
                },
            actor = PlayerId(raw.actor as String),
            poolTokens = planningInteger(raw.poolTokens, "pool tokens"),
            stockTokens =
                expansionKeys(raw.stockTokens).associate {
                    PlayerId(it) to planningInteger(raw.stockTokens[it], "stock tokens")
                },
            orderBox = planningInteger(raw.orderBox, "printed order box"),
            turnOrder = planningArray(raw.turnOrder, "turn order").map { PlayerId(it as String) },
            cathedralUsedAgainst =
                expansionKeys(raw.cathedralUsedAgainst).associate { owner ->
                    PlayerId(owner) to
                        planningArray(raw.cathedralUsedAgainst[owner], "Cathedral opponents")
                            .map { PlayerId(it as String) }
                            .toSet()
                },
        )
    result.toPosition()
    return result
}

private fun expansionBoolean(raw: dynamic): Boolean {
    require(jsTypeOf(raw) == "boolean") { "Public expansion flags must be boolean." }
    return raw as Boolean
}

private fun expansionKeys(raw: dynamic): Array<String> {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Public expansion mappings must be objects."
    }
    return js("Object.keys(raw)") as Array<String>
}

private fun expansionShips(raw: dynamic): ShippingCapability {
    val fleet = ShippingFleet.valueOf(raw.fleet as String)
    val level = planningInteger(raw.level, "ship level")
    return requireNotNull(
        ShippingCapability.entries.singleOrNull { it.fleet == fleet && it.level == level }
    ) {
        "Public expansion shipping must name a legal fleet space."
    }
}
