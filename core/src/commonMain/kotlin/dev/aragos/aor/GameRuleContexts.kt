package dev.aragos.aor

internal fun GameState.cardContext(): CardContext =
    CardContext(
        players =
            players.mapValues { (_, owner) ->
                CardPlayerContext(
                    cash = owner.cash,
                    writtenCash = owner.writtenCash,
                    poolTokens = owner.poolTokens,
                    stockTokens = owner.stockTokens,
                    advances = owner.advances.owned,
                    capital =
                        catalog.provinces
                            .single { definition -> definition.capital == owner.capital }
                            .id,
                    misery = owner.misery,
                    handCardIds = owner.hand.map { card -> card.definition.id }.toSet(),
                    ships = owner.ships,
                    overseasPresence =
                        board.pieces
                            .filter { (province, _) ->
                                catalog.map.province(province).overseas != null &&
                                    board.hasPresence(owner.player.id, province)
                            }
                            .keys,
                )
            },
        markers =
            board.pieces
                .mapNotNull { (province, occupants) ->
                    (occupants as? ProvincePieces.Marker)?.let { marker ->
                        province to CardMarker(marker.owner, marker.side == PieceSide.COLOURED)
                    }
                }
                .toMap(),
        effects = effects,
        adjustments = adjustments,
        voids = voids,
        finalCardPlay = stage == Stage.FINAL_CARD_PLAY,
        turn = currentTurn(),
        epoch = currentEpoch,
        rules = rules,
        tokenProvinces =
            board.pieces.filterValues { pieces -> pieces is ProvincePieces.Tokens }.keys,
        provinces = catalog.provinces.associateBy(ProvinceDefinition::id),
    )

internal fun GameState.purchaseContext(actor: PlayerId): PurchaseContext =
    PurchaseContext(
        currentTurn(),
        effects.papalCategory,
        effects.leaders
            .filter { leader -> leader.owner == actor || actor in leader.patronageClaimants }
            .flatMap { played ->
                LeaderData.definition(played.leader).discounts.map { credit ->
                    AuthorizedLeaderDiscount(
                        played.leader.cardId,
                        credit.advance,
                        credit.amountFor(effects.crusadesPlayed, played.mongolEnhanced),
                    )
                }
            },
        rules = rules,
    )

internal fun GameState.patronageChoices(actor: PlayerId): List<String> {
    val player = playerState(actor)
    if (!player.advances.owns(AdvanceId.PATRONAGE)) return emptyList()
    val context = cardContext()
    return effects.leaders.mapNotNull { played ->
        try {
            LeaderRules.claim(context, actor, played.leader)
            "patronize ${played.leader.cardId}"
        } catch (_: CardRuleViolation) {
            null
        }
    }
}
