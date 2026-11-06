package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test

class BotPlanningSerializationTest {
    @Test
    fun livePlayerCardTargetsUseTheOptionalBrowserStringField() {
        val initial = fixture()
        val actor = initial.header.players.first().id
        val owner =
            initial
                .playerState(actor)
                .copy(hand = listOf(HandCard("held-war", CardData.definition("civil-war"))))
        val state =
            initial.copy(
                stage = Stage.PLAY_CARDS,
                players = initial.players + (actor to owner),
            )

        val observation = json.readTree(json.writeValueAsString(BotObservations.from(state, actor)))

        val candidate =
            observation["candidates"].single {
                it["command"].asText() == "play held-war player p2"
            }
        assertThat(candidate["playerTarget"].isTextual).isTrue()
        assertThat(candidate["playerTarget"].asText()).isEqualTo("p2")
        val pass = observation["candidates"].single { it["command"].asText() == "pass" }
        assertThat(pass["playerTarget"].isNull).isTrue()
    }

    @Test
    fun liveExpansionPlanningUsesBrowserCompatibleNumbersEnumsAndCommandKeys() {
        val state = fixture()
        val actor = state.header.players.first().id

        val observation = json.readTree(json.writeValueAsString(BotObservations.from(state, actor)))

        val planning = observation["expansionPlanning"]
        val wool = planning["commodities"].single { it["commodity"].asText() == "WOOL" }
        assertThat(planning["markerStock"].isIntegralNumber).isTrue()
        assertThat(planning["markerStock"].intValue()).isEqualTo(23)
        assertThat(planning["industry"].isBoolean).isTrue()
        assertThat(planning["industry"].booleanValue()).isTrue()
        assertThat(wool)
            .isEqualTo(
                json.readTree(
                    """{"commodity":"WOOL","markets":0,"baseValue":2,"adjustmentOffset":1,"outsideCards":0}"""
                )
            )
        assertThat(planning["heldCards"].map { card -> card.map { it.asText() }.toSet() })
            .containsExactly(setOf("WOOL"), setOf("WOOL"), setOf("CLOTH", "WINE"))
            .inOrder()
        assertThat(planning["steps"]["place 2 smyrna"])
            .isEqualTo(
                json.readTree("""{"markerCost":1,"commodities":["WOOL"],"newOverseasCost":0}""")
            )
    }

    @Test
    fun liveFinalAdvancePreservesZeroScoreAndTypedInterestFacts() {
        val initial = fixture()
        val actor = initial.header.players.first().id
        val owner =
            initial
                .playerState(actor)
                .copy(
                    cash = 100,
                    advances =
                        PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.INTEREST_PROFIT, 1))),
                )
        val state =
            initial.copy(
                stage = Stage.PURCHASE,
                endingTriggered = true,
                players = initial.players + (actor to owner),
                flow = initial.flow.copy(purchaseStep = PurchaseStep.ADVANCES),
            )
        val publicOwner = state.viewForDecision(actor).players.single { it.id == actor }
        val expectedIncome =
            checkNotNull(publicOwner.income) { "The purchase fixture requires public income." }

        val observation = json.readTree(json.writeValueAsString(BotObservations.from(state, actor)))

        val heavens = observation["candidates"].single { it["command"].asText() == "buy-advance A" }
        val features = heavens["features"]
        val key = "planning.finalAdvanceScore"
        assertThat(heavens["cashCost"].intValue()).isEqualTo(30)
        assertThat(features.has(key)).isTrue()
        assertThat(features[key].isNumber).isTrue()
        assertThat(features[key].doubleValue()).isEqualTo(0.0)
        val planning = observation["advancePlanning"]
        assertThat(planning["ordinaryIncome"].isIntegralNumber).isTrue()
        assertThat(planning["ordinaryIncome"].intValue()).isEqualTo(expectedIncome.ordinary)
        assertThat(planning["ownsInterestProfit"].isBoolean).isTrue()
        assertThat(planning["ownsInterestProfit"].booleanValue())
            .isEqualTo(AdvanceId.INTEREST_PROFIT in publicOwner.advances)
    }

    private fun fixture(): GameState {
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        val actor = players.first().id
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            players
                .zip(capitals)
                .fold(BoardState()) { current, (player, capital) ->
                    current.placeMarker(ProvinceId(capital), player.id).board
                }
                .placeMarker(ProvinceId("constantinople"), actor)
                .board
        return GameState(
            header = GameHeader(players, actor),
            currentEpoch = Epoch.THREE,
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            board = board,
            adjustments = mapOf(Commodity.WOOL to listOf(CommodityAdjustment.SHORTAGE)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = 6,
                                stockTokens = 30,
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.INDUSTRY, 1))
                                    ),
                                hand =
                                    if (player.id == actor) {
                                        listOf("wool-1", "wool-2", "cloth-wine").mapIndexed {
                                            card,
                                            id ->
                                            HandCard(
                                                "held-${card + 1}",
                                                CardData.definition(id),
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
