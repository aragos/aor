package dev.aragos.aor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LegalChoicesCacheTest {
    private val first = PlayerId("p1")
    private val second = PlayerId("p2")

    @Test
    fun repeatedQueriesPreserveTheIndependentCommandsAndLiveOffers() {
        val state = LegalChoicesCacheFixture.expansion()
        val expected = TurnEngine.choices(state, first)
        val offers = state.copy().viewForPlayer(first).expansionOffers

        assertEquals(expected, state.legalChoices(first))
        assertEquals(expected, state.legalChoices(first))
        assertEquals(offers, state.viewForDecision(first).expansionOffers)
        assertEquals(offers, state.viewForPlayer(first).expansionOffers)
    }

    @Test
    fun changedPlayerResourcesRecomputeWithoutChangingThePriorSnapshot() {
        val before = LegalChoicesCacheFixture.expansion()
        val original = before.legalChoices(first)
        val after = before.updatePlayer(first) { player -> player.copy(poolTokens = 0) }

        assertEquals(TurnEngine.choices(after, first), after.legalChoices(first))
        assertNotEquals(original, after.legalChoices(first))
        assertEquals(original, before.legalChoices(first))
    }

    @Test
    fun waitingSeatCannotReuseTheActivePlayersChoices() {
        val state = LegalChoicesCacheFixture.expansion()

        assertTrue(state.legalChoices(second).commands.isEmpty())
        assertEquals(TurnEngine.choices(state, first), state.legalChoices(first))
        assertTrue(state.legalChoices(first).commands.isNotEmpty())
    }

    @Test
    fun sealedChoicesRetainEachPlayersOwnCashBounds() {
        val state =
            LegalChoicesCacheFixture.expansion()
                .copy(stage = Stage.TOKEN_PURCHASE, flow = TurnFlow())
                .updatePlayer(second) { player -> player.copy(cash = 3) }

        assertEquals(-3, state.legalChoices(second).bidMin)
        assertEquals(3, state.legalChoices(second).bidMax)
        assertEquals(-40, state.legalChoices(first).bidMin)
        assertEquals(40, state.legalChoices(first).bidMax)
    }

    @Test
    fun unregisteredPlayerRetainsTheTypedRejection() {
        val state = LegalChoicesCacheFixture.expansion()

        val failure = assertFailsWith<RuleViolation> { state.legalChoices(PlayerId("p6")) }

        assertEquals(ViolationCode.UNKNOWN_PLAYER, failure.code)
    }

    @Test
    fun copiedInterruptionOwnsItsPrivateHandChoices() {
        val before = LegalChoicesCacheFixture.expansion()
        val original = before.legalChoices(first)
        val after = before.pushDecision(BoardDecision.TradeOffer(first, second))

        assertEquals(TurnEngine.choices(after, first), after.legalChoices(first))
        assertNotEquals(original, after.legalChoices(first))
        assertTrue(after.legalChoices(second).commands.isEmpty())
    }

    @Test
    fun queryCachesDoNotChangeStateEqualityOrRecordedCommandReplay() {
        val before = LegalChoicesCacheFixture.expansion()
        val fresh = before.copy()
        val originalHash = before.hashCode()
        val action = TextCodec.parse("p1: place 1 florence")
        val recorded = action.toText()
        before.legalChoices(first)
        before.viewForPlayer(first)

        assertEquals(fresh, before)
        assertEquals(originalHash, before.hashCode())
        assertEquals(recorded, TextCodec.parse(recorded).toText())
        assertEquals(fresh.step(action), before.step(TextCodec.parse(recorded)))
        assertEquals(
            fresh.step(action).legalChoices(first),
            before.step(action).legalChoices(first),
        )
    }
}

/** One explicit immutable snapshot, without authority draws, dice or policy decisions. */
internal object LegalChoicesCacheFixture {
    fun expansion(): GameState {
        val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
        val first = players.first().id
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, first),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = first, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            deck = CardData.epochOne.drop(3).map(CardDefinition::id),
            deckHistory = DeckHistory(nextDraw = 4),
            players =
                players
                    .mapIndexed { index, player ->
                        val pool = if (player.id == first) 20 else 10
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                hand =
                                    listOf(HandCard("hand-${index + 1}", CardData.epochOne[index])),
                                poolTokens = pool,
                                stockTokens = 36 - pool,
                                ships = ShippingCapability.GALLEY_4,
                            )
                    }
                    .toMap(),
        )
    }
}
