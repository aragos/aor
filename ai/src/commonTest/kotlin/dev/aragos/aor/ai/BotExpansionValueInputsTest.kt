package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.Entry
import dev.aragos.aor.ExpansionTransition
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BotExpansionValueInputsTest {
    private val actor = PlayerId("p1")
    private val smyrna = ProvinceId("smyrna")

    @Test
    fun boardReplacementMatchesAuthoritativeWhiteMarkerPayoutsAndIncome() {
        val state = fixture()
        val view = state.viewForDecision(actor)
        val inputs = BotExpansionValueInputs.from(view, actor)
        val position =
            requireNotNull(view.expansionPosition) { "The fixture requires expansion inputs." }
                .toPosition()
        val transition = assertIs<ExpansionTransition.Resolved>(position.reserve(smyrna, 2))
        val afterInputs = inputs.withBoard(transition.position.context.board)
        val actual = state.step(Entry.Action(actor, TextCodec.parseCommand("place 2 smyrna")))
        val after = actual.viewForDecision(actor)
        val owner = after.players.single { it.id == actor }
        val payout =
            after.commodityPayouts
                .single { it.commodity == Commodity.WOOL }
                .owners
                .single { it.player == actor }

        assertEquals(payout.payout.toDouble(), afterInputs.payout(actor, Commodity.WOOL))
        assertEquals(owner.income?.ordinary, afterInputs.income(actual.board)?.income)
        assertEquals(owner.income?.interestProfit, afterInputs.income(actual.board)?.interestProfit)
        assertEquals(
            BotExpansionValueInputs.from(after, actor).commodityForecasts,
            afterInputs.commodityForecasts,
        )
    }

    @Test
    fun nominalPartialPlacementRetainsLegacyRawMarginalDespiteIndustryAndShortage() {
        val view = fixture().viewForDecision(actor)
        val offer = view.expansionOffers.single { it.destination == smyrna }

        val features =
            BotExpansionValueInputs.from(view, actor)
                .nominalFeatures(
                    smyrna,
                    BotExpansionActionInputs.from(offer),
                    1,
                )

        assertEquals(
            linkedMapOf(
                "tokens" to -1.0,
                "progress" to 0.5,
                "reach" to 0.5,
                "commodity" to 0.25,
                "income" to 0.0,
                "risk" to 0.0,
            ),
            features,
        )
    }

    @Test
    fun sharedPhysicalCardsKeepTheExistingPortfolioValuation() {
        val view = fixture().viewForDecision(actor)
        val inputs = BotExpansionValueInputs.from(view, actor)

        val gain = inputs.portfolio().gains(listOf(setOf(Commodity.WOOL)))

        assertEquals(18.0, gain.held)
        assertEquals(
            BotEconomicProjection(view, actor).portfolio().gains(listOf(setOf(Commodity.WOOL))),
            gain,
        )
    }

    @Test
    fun unrelatedPrivateOpponentCardsDoNotChangeEconomicInputs() {
        val state = fixture()
        val before = state.viewForDecision(actor)
        val changed =
            state
                .copy(
                    players =
                        state.players.mapValues { (id, player) ->
                            if (id == actor) player
                            else
                                player.copy(
                                    hand =
                                        listOf(HandCard("different", CardData.definition("gold-3")))
                                )
                        }
                )
                .viewForDecision(actor)

        assertEquals(
            BotExpansionValueInputs.from(before, actor),
            BotExpansionValueInputs.from(changed, actor),
        )
    }

    private fun fixture(): GameState {
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
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
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = actor, phaseStarted = true),
            board = board,
            adjustments = mapOf(Commodity.WOOL to listOf(CommodityAdjustment.SHORTAGE)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 23,
                                poolTokens = 15,
                                stockTokens = 21,
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceId.INDUSTRY, AdvanceId.INTEREST_PROFIT).map {
                                            AdvanceAcquisition(it, 1)
                                        }
                                    ),
                                hand =
                                    if (player.id == actor)
                                        listOf(
                                            HandCard("held", CardData.definition("wool-2")),
                                            HandCard("shared", CardData.definition("cloth-wine")),
                                        )
                                    else emptyList(),
                            )
                    }
                    .toMap(),
        )
    }
}
