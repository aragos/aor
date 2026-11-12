package dev.aragos.aor.ai

import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.Epoch
import dev.aragos.aor.ExpansionBonusProjection
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpansionPairedEncoderTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val own = players[0].id

    @Test
    fun legacyStateActionAndBaselineValuesRemainExact() {
        val state = fixture()
        val view = state.viewForPlayer(own)
        val ordinary = BotObservations.from(state, own)
        val original =
            ExpansionObservationEncoder.from(
                view,
                own,
                ordinary,
                "pass",
                TerritoryEncoder.EXPANSION_2,
            )

        val current = encode(state)

        assertEquals(
            original.stateFeatures,
            current.stateFeatures.filterKeys { it in original.stateFeatures },
        )
        assertEquals(original.actions, current.actions)
        assertEquals(original.baselineIndex, current.baselineIndex)
    }

    @Test
    fun inventoryUsesActualPublicCountsAndOwnPhysicalHand() {
        val state = fixture()
        val quote =
            state.viewForPlayer(own).commodityCards.single { it.commodity == Commodity.STONE }

        val features = encode(state).stateFeatures

        assertEquals(quote.unplayed!! / 10.0, features["commodity.STONE.cards.unplayed"] ?: 0.0)
        assertEquals(.1, features["commodity.STONE.cards.ownHand"])
        assertEquals(quote.epochs[2] / 10.0, features["commodity.STONE.cards.epoch3"] ?: 0.0)
    }

    @Test
    fun currentPayoutColumnsCopyAuthoritativeQuotesWithoutRepricing() {
        val state = fixture()
        val view = state.viewForPlayer(own)
        val features = encode(state).stateFeatures

        for (quote in view.commodityPayouts) {
            for (owner in quote.owners) {
                val prefix =
                    "commodity.${quote.commodity}.${RawActorState.role(view, own, owner.player)}"
                assertEquals(owner.marketCount / 25.0, features["$prefix.markets"] ?: 0.0)
                assertEquals(owner.payout / 200.0, features["$prefix.payout"] ?: 0.0)
            }
        }
    }

    @Test
    fun dictionaryContainsEveryFeatureAndLeavesOldActionVocabularyExact() {
        val state = fixture()
        val catalog = requireNotNull(state.publicView().catalog)
        val dictionary = ExpansionPairedEncoder.dictionary(catalog)
        val old = ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2)
        val observation = encode(state)

        assertTrue(dictionary.stateFeatureNames.containsAll(observation.stateFeatures.keys))
        assertTrue(dictionary.stateFeatureNames.containsAll(old.stateFeatureNames))
        assertEquals(old.actionFeatureNames, dictionary.actionFeatureNames)
        assertTrue(observation.stateFeatures.values.all { it.isFinite() && it in -16.0..16.0 })
    }

    @Test
    fun changingOnlyHiddenCardLocationsCannotChangeTheFeatures() {
        val state = fixture()
        val other = players[1].id
        fun changed(card: String) =
            state.copy(
                deck = listOf(card),
                players =
                    state.players +
                        (other to
                            state
                                .playerState(other)
                                .copy(
                                    hand = listOf(HandCard("private", CardData.definition(card)))
                                )),
            )

        assertEquals(encode(changed("stone-1")), encode(changed("gold-3")))
    }

    @Test
    fun oldPolicyContractsRejectTheNewResearchEncoder() {
        val current = encode(fixture())

        assertFailsWith<IllegalArgumentException> {
            TerritoryObservation(
                current.encoderVersion,
                current.stateFeatures,
                current.actions,
                current.baselineIndex,
            )
        }
    }

    private fun encode(state: GameState) =
        ExpansionPairedEncoder.from(
            state.viewForPlayer(own),
            own,
            BotObservations.from(state, own),
            "pass",
            ExpansionBonusProjection.from(state),
        )

    private fun fixture(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        return GameState(
            header = GameHeader(players, own),
            stage = Stage.EXPANSION,
            currentEpoch = Epoch.THREE,
            turnNumber = 5,
            board =
                players.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                    board.placeMarker(ProvinceId(capital), player.id).board
                },
            turnOrderSelection = TurnOrderSelection(players.map { it.id }),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = 100,
                                stockTokens = if (index == 0) 0 else 36,
                                poolTokens = if (index == 0) 36 else 0,
                                ships =
                                    if (index == 0) ShippingCapability.SEAWORTHY_10
                                    else ShippingCapability.NONE,
                                hand =
                                    if (index == 0)
                                        listOf(HandCard("own", CardData.definition("stone-1")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = own, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
