package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The same typed/public arithmetic and deterministic transitions execute on JVM and JavaScript. */
class AdjustmentCancellationProjectionTest {
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
    private val actor = players.first().id
    private val shortage = CommodityAdjustment.SHORTAGE
    private val surplus = CommodityAdjustment.SURPLUS

    private fun state(queues: Map<Commodity, List<CommodityAdjustment>>): GameState {
        val provinces =
            CatalogConfiguration(3)
                .provinces
                .filter {
                    it.commodities.any { commodity -> commodity in queues.keys }
                }
                .take(6)
        val board =
            provinces.foldIndexed(BoardState()) { index, previous, province ->
                previous
                    .placeMarker(
                        province.id,
                        players[index % 2].id,
                        if (index % 2 == 0) PieceSide.WHITE else PieceSide.COLOURED,
                    )
                    .board
            }
        return GameState(
            header = GameHeader(players, actor),
            stage = Stage.DRAW_CARD,
            turnNumber = 2,
            adjustments = queues,
            board = board,
            deck = listOf("stone-1", "stone-2", "wool-1"),
            flow = TurnFlow(actor = actor, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = listOf("venice", "genoa", "barcelona")[index],
                                cash = 40,
                                advances =
                                    if (index == 1)
                                        PlayerAdvances(
                                            listOf(AdvanceAcquisition(AdvanceId.INDUSTRY, 1))
                                        )
                                    else PlayerAdvances(),
                            )
                    }
                    .toMap(),
        )
    }

    private fun context(state: GameState) =
        AdjustmentCancellationContext.from(state.viewForDecision(actor), actor)

    private fun verifyAllOffers(state: GameState) {
        val quotes = AdjustmentCancellationProjection.quotes(context(state))
        assertEquals(state.legalChoices(actor).commands.toSet(), quotes.map { it.command }.toSet())
        for (quote in quotes) {
            val after = state.step(TextCodec.parse("${actor.value}: ${quote.command}"))
            assertEquals(after.playerState(actor).cash, quote.cashAfter)
            val actual = after.viewForPlayer(actor).commodityPayouts.associateBy { it.commodity }
            for (change in quote.commodities) {
                val forecast =
                    requireNotNull(actual[change.commodity]) { "Quoted commodity exists." }
                assertEquals(forecast.adjustments, change.after)
                assertEquals(
                    forecast.owners.map { Triple(it.player, it.effectiveBox, it.payout) },
                    change.owners.map { Triple(it.player, it.boxAfter, it.payoutAfter) },
                )
            }
            if (quote.command == "pass") {
                assertEquals(
                    state.viewForPlayer(actor).commodityPayouts,
                    after.viewForPlayer(actor).commodityPayouts,
                )
                assertEquals(emptyList(), quote.commodities)
            }
        }
    }

    @Test
    fun allShortageOffersAndPassMatchDeterministicTransitions() {
        verifyAllOffers(state(mapOf(Commodity.CLOTH to listOf(shortage))))
    }

    @Test
    fun allSurplusOffersAndPassMatchDeterministicTransitions() {
        verifyAllOffers(state(mapOf(Commodity.CLOTH to listOf(surplus))))
    }

    @Test
    fun legacyHeaderRetainsTheSameCancellationTransitions() {
        val original = state(mapOf(Commodity.CLOTH to listOf(shortage, surplus)))
        verifyAllOffers(
            original.copy(
                header =
                    original.header.copy(
                        rulesVersion = LEGACY_RULES_VERSION,
                        drawRenaissanceFromTurn = Int.MAX_VALUE,
                    )
            )
        )
    }

    @Test
    fun doubleMarkersRemoveOnlyOneOccurrence() {
        val before = state(mapOf(Commodity.CLOTH to listOf(shortage, shortage)))
        val quote = AdjustmentCancellationProjection.quotes(context(before)).last()
        assertEquals(listOf(shortage), quote.commodities.single().after)
    }

    @Test
    fun doubledQueueTransitionsMatchEveryAuthoritativeOffer() {
        verifyAllOffers(state(mapOf(Commodity.CLOTH to listOf(shortage, shortage))))
    }

    @Test
    fun cancellingBehindQueueHeadDoesNotChangeCurrentPayout() {
        val before = state(mapOf(Commodity.CLOTH to listOf(shortage, surplus)))
        val quote =
            AdjustmentCancellationProjection.quote(
                context(before),
                TurnCommand.CancelAdjustment(Commodity.CLOTH, surplus),
            )
        assertEquals(listOf(shortage), quote.commodities.single().after)
        assertEquals(
            quote.commodities.single().owners.map { it.payoutBefore },
            quote.commodities.single().owners.map { it.payoutAfter },
        )
    }

    @Test
    fun mixedQueueTransitionsMatchForEveryOfferedKind() {
        verifyAllOffers(state(mapOf(Commodity.CLOTH to listOf(shortage, surplus))))
    }

    @Test
    fun sharedGoldIvoryActionChargesOnceAndPreservesBothQueues() {
        val before =
            state(
                mapOf(
                    Commodity.GOLD to listOf(shortage, shortage),
                    Commodity.IVORY to listOf(shortage, surplus),
                )
            )
        val quote =
            AdjustmentCancellationProjection.quotes(context(before)).single {
                it.command == "cancel-adjustment gold shortage"
            }
        assertEquals(10, quote.cost)
        assertEquals(30, quote.cashAfter)
        assertEquals(
            setOf(Commodity.GOLD, Commodity.IVORY),
            quote.commodities.map { it.commodity }.toSet(),
        )
        assertEquals(
            setOf(listOf(shortage), listOf(surplus)),
            quote.commodities.map { it.after }.toSet(),
        )
    }

    @Test
    fun asymmetricMetalQueuesMatchEveryAuthoritativeOffer() {
        verifyAllOffers(
            state(
                mapOf(
                    Commodity.GOLD to listOf(shortage, shortage),
                    Commodity.IVORY to listOf(shortage, surplus),
                )
            )
        )
    }

    @Test
    fun differentOwnAndRivalHoldingsRetainTheirDistinctAmounts() {
        val original = state(mapOf(Commodity.CLOTH to listOf(shortage)))
        val rival = players[1].id
        val before =
            original.copy(
                players =
                    original.players +
                        (rival to original.playerState(rival).copy(advances = PlayerAdvances()))
            )
        val quote = AdjustmentCancellationProjection.quotes(context(before)).last()
        assertNotEquals(
            quote.commodities.single().owners[0].payoutAfter,
            quote.commodities.single().owners[1].payoutAfter,
        )
    }

    @Test
    fun chaosOwnersRemainAbsentFromPublicQuotes() {
        val before = state(mapOf(Commodity.CLOTH to listOf(shortage)))
        val rival = players[1].id
        val chaos =
            before.copy(
                players =
                    before.players +
                        (rival to before.playerState(rival).copy(misery = MiseryStep.CHAOS))
            )
        val quote = AdjustmentCancellationProjection.quotes(context(chaos)).last()
        assertEquals(listOf(actor), quote.commodities.single().owners.map { it.player })
    }

    @Test
    fun hiddenHandsAndDeckOrderCannotChangePublicContextOrQuotes() {
        val before = state(mapOf(Commodity.CLOTH to listOf(shortage)))
        val rival = players[1].id
        val changed =
            before.copy(
                deck = before.deck.reversed(),
                players =
                    before.players +
                        (rival to
                            before
                                .playerState(rival)
                                .copy(
                                    hand =
                                        listOf(HandCard("private", CardData.definition("stone-1")))
                                )),
            )
        assertEquals(context(before), context(changed))
    }

    @Test
    fun unofferedCommandCannotProduceAHypotheticalQuote() {
        val input = context(state(mapOf(Commodity.CLOTH to listOf(shortage))))
        assertFailsWith<IllegalArgumentException> {
            AdjustmentCancellationProjection.quote(
                input,
                TurnCommand.CancelAdjustment(Commodity.CLOTH, surplus),
            )
        }
    }

    @Test
    fun anotherPlayersViewDoesNotAuthorizeTheFirstPlayersWindow() {
        val before = state(mapOf(Commodity.CLOTH to listOf(shortage)))
        assertFailsWith<IllegalArgumentException> {
            AdjustmentCancellationContext.from(before.viewForPlayer(players[1].id), players[1].id)
        }
    }
}
