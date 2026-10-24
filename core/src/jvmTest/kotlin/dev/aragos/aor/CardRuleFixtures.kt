package dev.aragos.aor

internal val cardPlayerOne = PlayerId("p1")
internal val cardPlayerTwo = PlayerId("p2")
internal val cardPlayerThree = PlayerId("p3")

internal fun cardContext(card: String): CardContext =
    CardContext(
        players =
            listOf(
                    cardPlayerOne to "venice",
                    cardPlayerTwo to "genoa",
                    cardPlayerThree to "barcelona",
                )
                .associate { (id, capital) ->
                    id to
                        CardPlayerContext(
                            cash = 40,
                            writtenCash = 40,
                            poolTokens = 10,
                            stockTokens = 26,
                            advances = emptySet(),
                            capital = ProvinceId(capital),
                            misery = MiseryStep.ZERO,
                            handCardIds = if (id == cardPlayerOne) setOf(card) else emptySet(),
                        )
                }
    )

internal fun CardContext.withPlayer(
    id: PlayerId,
    update: (CardPlayerContext) -> CardPlayerContext,
): CardContext = copy(players = players + (id to update(player(id))))

internal fun CardContext.withAdvances(id: PlayerId, vararg advances: AdvanceId): CardContext =
    withPlayer(id) { player -> player.copy(advances = advances.toSet()) }

internal fun CardContext.withMarkers(vararg markers: Pair<String, PlayerId>): CardContext =
    copy(
        markers =
            markers.associate { (province, owner) -> ProvinceId(province) to CardMarker(owner) }
    )

internal fun cardEvent(event: EventId, choice: CardChoice = CardChoice.None): CardPlay =
    CardPlay(event.cardId, choice)
