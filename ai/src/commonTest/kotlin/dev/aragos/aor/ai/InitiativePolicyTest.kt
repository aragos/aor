package dev.aragos.aor.ai

import dev.aragos.aor.ContestedExpansionProspectView
import dev.aragos.aor.ExpansionForecastView
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.test.Test
import kotlin.test.assertEquals

class InitiativePolicyTest {
    private val players = (1..4).map { PlayerId("p$it") }
    private val seat = players.first()
    private val state =
        GameState(
                GameHeader(players.map { Player(it, it.value) }, creatorId = seat),
                turnNumber = 3,
                stage = Stage.TOKEN_PURCHASE,
            )
            .let { initial ->
                val capitals = players.zip(initial.catalog.capitals).toMap()
                initial.copy(
                    players =
                        initial.players.mapValues { (player, current) ->
                            current.copy(
                                capital =
                                    requireNotNull(capitals[player]) {
                                        "Every seat has a test capital."
                                    }
                            )
                        }
                )
            }
    private val view =
        state
            .viewForDecision(seat)
            .copy(
                capitalOrder = players,
                tokenPurchaseOffers =
                    (18..21).map { amount ->
                        TokenPurchaseOfferView(
                            amount,
                            amount,
                            amount,
                            40 - amount,
                            36 - amount,
                            amount,
                        )
                    },
                pastTokenPurchases = listOf(players.associateWith { 21 }),
            )
    private val config = InitiativePolicyConfig(2, .5, 1.0, false, 0.0)

    @Test
    fun usesTheCheapestUsefulUndercut() {
        assertEquals("buy-tokens 20", InitiativePolicy.bid(view, seat, "buy-tokens 21", 7, config))
    }

    @Test
    fun respectsTheContestedPoolRequirement() {
        val forecast =
            ExpansionForecastView(
                peacefulMarkets = emptyList(),
                overseasSlots = 0,
                contestedMarkets =
                    listOf(
                        ContestedExpansionProspectView(
                            ProvinceId(state.catalog.capitals.first()),
                            21,
                            false,
                            false,
                        )
                    ),
            )
        val constrained = view.copy(expansionForecast = forecast)
        assertEquals(
            "buy-tokens 21",
            InitiativePolicy.bid(
                constrained,
                seat,
                "buy-tokens 21",
                7,
                config.copy(requireAffordableContest = true),
            ),
        )
    }

    @Test
    fun ignoresSealedChoicesWhenHistoryIsEmpty() {
        val empty =
            view.copy(
                pastTokenPurchases = emptyList(),
                revealedTokenPurchases = players.associateWith { 21 },
            )

        assertEquals("buy-tokens 21", InitiativePolicy.bid(empty, seat, "buy-tokens 21", 7, config))
    }

    @Test
    fun earlyCardPurchaseBonusFallsAsThePriceRises() {
        val options =
            TerritoryObservation(
                TerritoryEncoder.EXPANSION_2.version,
                emptyMap(),
                listOf(
                    TerritoryAction("buy-expansion-card", mapOf("kind.buyCard" to 1.0)),
                    TerritoryAction("pass", mapOf("kind.pass" to 1.0)),
                ),
                baselineIndex = 1,
            )
        val normal = IncumbentResidualChoice(listOf(0.0, 0.0), listOf(0.0, 1.0), 1, "pass")
        val expansion =
            view.copy(
                stage = Stage.EXPANSION,
                orderBoxes = mapOf(seat to 1),
                expansionCardCost = 3,
            )
        val buying = config.copy(cardBonus = 2.0)

        assertEquals(
            "buy-expansion-card",
            InitiativePolicy.expansion(expansion, seat, options, normal, buying).command,
        )
        assertEquals(
            "pass",
            InitiativePolicy.expansion(
                    expansion.copy(expansionCardCost = 9),
                    seat,
                    options,
                    normal,
                    buying,
                )
                .command,
        )
    }
}
