package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotReachableRetentionTest {
    private val p1 = PlayerId("p1")
    private val ids = listOf(p1, PlayerId("p2"), PlayerId("p3"))

    @Test
    fun anAutomatonCanKeepAZeroPayoutCardBeforeUnopposedShippingExpansion() {
        val state = fixture(pool = 14)
        val observation = BotObservations.from(state, p1)
        val play = observation.candidates.single { it.command.startsWith("play held") }
        val model =
            BotModels.default.copy(
                weights =
                    BotModels.default.weights + (BotEconomicFeature.REACHABLE_RETENTION.key to 1.0)
            )

        val decision = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(play.features[BotEconomicFeature.RETAINED_COMMODITY.key]).isNull()
        assertThat(play.features[BotEconomicFeature.REACHABLE_RETENTION.key]).isLessThan(-1.0)
        assertThat(BotPolicy.choose(observation, BotDifficulty.HARD, 17)).startsWith("play held")
        assertThat(decision).isEqualTo("pass")
    }

    @Test
    fun aFutureRouteDoesNotInventTokensForImmediateExpansion() {
        val observation = BotObservations.from(fixture(pool = 0), p1)

        val play = observation.candidates.single { it.command.startsWith("play held") }

        assertThat(play.features[BotEconomicFeature.REACHABLE_RETENTION.key]).isNull()
        assertThat(play.features[BotEconomicFeature.REACHABLE_COMMODITY_OPTION.key]).isEqualTo(-1.0)
    }

    @Test
    fun unreachableMatchingMarketsDoNotCreateARouteOption() {
        val state = fixture(pool = 14, ships = ShippingCapability.NONE)

        val play =
            BotObservations.from(state, p1).candidates.single { it.command.startsWith("play held") }

        assertThat(play.features[BotEconomicFeature.REACHABLE_RETENTION.key]).isNull()
        assertThat(play.features[BotEconomicFeature.REACHABLE_COMMODITY_OPTION.key]).isNull()
    }

    @Test
    fun multipleOverseasQuotesShareTheFleetPresenceLimit() {
        val state =
            fixture(
                pool = 36,
                ships = ShippingCapability.OCEAN_1,
                advances = AdvanceId.entries.toSet() - AdvanceId.INDUSTRY,
            )
        val original = state.viewForDecision(p1)
        val forecast =
            checkNotNull(original.expansionForecast) {
                "Commodity card play requires its current-route forecast."
            }
        val overseas = forecast.peacefulMarkets.filter { it.usesOverseasSlot }
        val view = original.copy(expansionForecast = forecast.copy(peacefulMarkets = overseas))
        val base =
            checkNotNull(view.catalog) { "The fixture requires a public catalog." }
                .commodities
                .single { it.id == Commodity.SPICE }
                .baseValue

        val features = BotEconomicProjection(view, p1).card("held")

        assertThat(overseas.map { it.destination.value }).containsAtLeast("east-indies", "china")
        assertThat(forecast.overseasSlots).isEqualTo(1)
        assertThat(features[BotEconomicFeature.REACHABLE_RETENTION.key]).isEqualTo(-base.toDouble())
    }

    private fun fixture(
        pool: Int,
        ships: ShippingCapability = ShippingCapability.SEAWORTHY_10,
        advances: Set<AdvanceId> = emptySet(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.PLAY_CARDS,
            currentEpoch = Epoch.THREE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board =
                ids.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                    board.placeMarker(ProvinceId(capital), player).board
                },
            players =
                ids.mapIndexed { index, player ->
                        player to
                            PlayerState(
                                player = Player(player, player.value),
                                capital = capitals[index],
                                cash = 100,
                                poolTokens = pool,
                                stockTokens = 36 - pool,
                                ships = if (player == p1) ships else ShippingCapability.NONE,
                                advances =
                                    PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) }),
                                hand =
                                    if (player == p1)
                                        listOf(HandCard("held", CardData.definition("spice-2")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
