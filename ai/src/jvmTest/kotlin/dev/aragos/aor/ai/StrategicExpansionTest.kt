package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.HandCard
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class StrategicExpansionTest {
    private val owner = PlayerId("p1")
    private val ids = (1..4).map { PlayerId("p$it") }

    @Test
    fun expansionPrioritiesRespondToTheOwnersCommodityCard() {
        val cloth = observation(fixture(card = "cloth-2"))
        val stone = observation(fixture(card = "stone-1"))
        val economicModel = BotModel(weights = mapOf("heldCommodity" to 1.0))
        val choices = setOf("place 4 florence", "place 4 rome")

        val clothMove =
            BotPolicy.choose(
                cloth.copy(candidates = cloth.candidates.filter { it.command in choices }),
                BotDifficulty.NORMAL,
                0,
                economicModel,
            )
        val stoneMove =
            BotPolicy.choose(
                stone.copy(candidates = stone.candidates.filter { it.command in choices }),
                BotDifficulty.NORMAL,
                0,
                economicModel,
            )

        assertThat(clothMove).isEqualTo("place 4 florence")
        assertThat(stoneMove).isEqualTo("place 4 rome")
    }

    @Test
    fun aDualCommodityCardCannotEarnBothPayouts() {
        val state = fixture(card = "cloth-wine")
        val strategy = StrategicExpansion(state.viewForPlayer(owner), owner)

        val wineGain = strategy.heldCommodityGain(ProvinceId("marseilles"))
        val clothGain = strategy.heldCommodityGain(ProvinceId("florence"))

        // Genoa already makes the card worth $5; a first Wine market also pays only $5.
        assertThat(wineGain).isEqualTo(0.0)
        assertThat(clothGain).isEqualTo(15.0)
    }

    @Test
    fun markerIncomeMakesExpansionUsefulWithoutAMatchingCommodity() {
        val observation = observation(fixture(card = "silk"))
        val choices = setOf("pass", "place 3 milan")
        val incomeModel = BotModel(weights = mapOf("income" to 3.2, "tokens" to 2.1))

        val selected =
            BotPolicy.choose(
                observation.copy(
                    candidates = observation.candidates.filter { it.command in choices }
                ),
                BotDifficulty.NORMAL,
                0,
                incomeModel,
            )

        assertThat(selected).isEqualTo("place 3 milan")
        assertThat(
                observation.candidates.single { it.command == selected }.features["heldCommodity"]
            )
            .isEqualTo(0.0)
    }

    @Test
    fun aGatewayHasReachValueWhereARedundantBaseDoesNot() {
        val state = fixture(card = "silk")
        val strategy = StrategicExpansion(state.viewForPlayer(owner), owner)

        val rome = strategy.futureReach(ProvinceId("rome"), tokens = 4, complete = true)
        val florence = strategy.futureReach(ProvinceId("florence"), tokens = 4, complete = true)

        assertThat(rome).isGreaterThan(0.0)
        assertThat(florence).isEqualTo(0.0)
    }

    @Test
    fun aSatelliteAttackForecastKeepsOnlyTheWinningOccupancy() {
        val initial = fixture(card = "silk")
        val player = requireNotNull(initial.players[owner]) { "The fixture requires its owner." }
        val state =
            initial.copy(
                players =
                    initial.players + (owner to player.copy(ships = ShippingCapability.GALLEY_2)),
                board = initial.board.replaceWithToken(ProvinceId("cagliari"), ids[1]).board,
            )

        val observation = observation(state)

        val attack = observation.candidates.single { it.command == "attack cagliari" }
        assertThat(attack.tokenCost).isGreaterThan(1)
        assertThat(attack.features["reach"]).isAtLeast(0.0)
    }

    @Test
    fun partialPlacementForecastPreservesOtherPlayersTokens() {
        val initial = fixture(card = "silk")
        val state =
            initial.copy(
                board = initial.board.replaceWithToken(ProvinceId("florence"), ids[1]).board
            )

        val observation = observation(state)

        val partial = observation.candidates.single { it.command == "place 1 florence" }
        assertThat(partial.features["heldCommodity"] ?: 0.0).isEqualTo(0.0)
        assertThat(partial.features["income"]).isEqualTo(0.0)
        assertThat(partial.features["reach"]).isAtLeast(0.0)
    }

    @Test
    fun aCommodityCardCanBeHeldForAffordableExpansion() {
        val observation = observation(fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 7))

        val selected = BotPolicy.choose(observation, BotDifficulty.NORMAL, 0)

        assertThat(selected).isEqualTo("pass")
    }

    @Test
    fun holdingCannotAssumeThatNewBasesUnlockMoreMarketsThisTurn() {
        val state = fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 12)
        val strategy = StrategicExpansion(state.viewForPlayer(owner), owner)

        val value = strategy.retentionValue("held-stone-1")

        // Milan and Rome are reachable now. Naples needs the new Rome base next round.
        assertThat(value).isWithin(0.0001).of(4.0 * 0.85)
    }

    @Test
    fun aCardIsPlayedWhenTheMatchingExpansionIsUnaffordable() {
        val observation = observation(fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 2))

        val selected = BotPolicy.choose(observation, BotDifficulty.NORMAL, 0)

        assertThat(selected).isEqualTo("play held-stone-1 commodity stone")
    }

    @Test
    fun aCardIsPlayedWhenCashIsNeededForStabilization() {
        val observation =
            observation(fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 7, cash = 0))

        val selected = BotPolicy.choose(observation, BotDifficulty.NORMAL, 0)

        assertThat(selected).isEqualTo("play held-stone-1 commodity stone")
    }

    @Test
    fun anEndingGameDoesNotHoldCardsForAnotherExpansion() {
        val observation =
            observation(
                fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 7)
                    .viewForPlayer(owner)
                    .copy(endingTriggered = true),
                fixture(card = "stone-1", stage = Stage.PLAY_CARDS, pool = 7),
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.NORMAL, 0)

        assertThat(selected).isEqualTo("play held-stone-1 commodity stone")
    }

    private fun observation(state: GameState): BotObservation = BotObservations.from(state, owner)

    private fun observation(view: GameView, state: GameState): BotObservation =
        BotObservations.from(view, state.legalChoices(owner), owner, "play-cards")

    private fun fixture(
        card: String,
        stage: Stage = Stage.EXPANSION,
        pool: Int = 12,
        cash: Int = 100,
    ): GameState {
        val capitals = listOf("genoa", "venice", "barcelona", "paris")
        val players =
            ids.mapIndexed { index, id ->
                    id to
                        PlayerState(
                            player = Player(id, id.value),
                            capital = capitals[index],
                            cash = cash,
                            poolTokens = if (id == owner) pool else 0,
                            stockTokens = if (id == owner) 36 - pool else 36,
                            hand =
                                if (id == owner)
                                    listOf(HandCard("held-$card", CardData.definition(card)))
                                else emptyList(),
                        )
                }
                .toMap()
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, owner),
            rules =
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                ),
            stage = stage,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            players = players,
            board = board,
            flow = TurnFlow(actor = owner, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
