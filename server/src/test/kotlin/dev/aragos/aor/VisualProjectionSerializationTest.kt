package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class VisualProjectionSerializationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun publicWrittenCashSerializesSeparatelyFromCurrentCash() {
        val initial = state()
        val player = initial.playerState(p1).copy(cash = 17, writtenCash = 31)
        val changed = initial.copy(players = initial.players + (p1 to player))

        val view = json.readTree(json.writeValueAsString(changed.viewForPlayer(p2)))
        val row = view["players"].single { it["id"].asText() == "p1" }

        assertThat(row["writtenCash"].isInt).isTrue()
        assertThat(row["writtenCash"].asInt()).isEqualTo(31)
        assertThat(row["cash"].asInt()).isEqualTo(17)
    }

    @Test
    fun referenceTracksRetainPrintedValuesAndFleetIdentity() {
        val view = json.readTree(json.writeValueAsString(state().viewForPlayer(p1)))
        val catalog = view.path("catalog")
        val misery = catalog.path("miseryTrack").toList()
        val shipping = catalog.path("shippingTrack").toList()

        assertThat(misery.first().asInt()).isEqualTo(0)
        assertThat(misery[misery.lastIndex - 1].asInt()).isEqualTo(1000)
        assertThat(misery.last().isNull).isTrue()
        assertThat(
                shipping
                    .filter { space -> space.path("fleet").asText() == "GALLEY" }
                    .map { space -> space.path("level").asInt() }
            )
            .containsExactly(2, 4, 6, 8)
            .inOrder()
        assertThat(
                shipping
                    .filter { space -> space.path("fleet").asText() == "OCEAN" }
                    .map { space -> space.path("level").asInt() }
            )
            .containsExactly(1, 2, 3, 4)
            .inOrder()
    }

    @Test
    fun expansionProjectionSerializesTypedIdentifiersAndBlockers() {
        val view = json.readTree(json.writeValueAsString(state().viewForPlayer(p1)))
        val offers = view.path("expansionOffers").toList()
        val florence = offers.single { offer -> offer.path("destination").asText() == "florence" }
        val china = offers.single { offer -> offer.path("destination").asText() == "china" }

        assertThat(florence.path("primaryAction").asText()).isEqualTo("PLACE")
        assertThat(florence.path("requiredTokens").asInt()).isEqualTo(4)
        assertThat(florence.path("establishesMarker").asBoolean()).isTrue()
        assertThat(florence.path("incomeAfter").isInt).isTrue()
        assertThat(china.path("blockers").first().asText()).isEqualTo("PROVINCE_ACCESS_DENIED")
    }

    @Test
    fun livePayoutIncludesZeroPayingColouredAndWhiteOwners() {
        val commodity = ProvinceData.definition(ProvinceId("florence")).commodities.single()
        val whiteProvince =
            CatalogConfiguration(3)
                .provinces
                .first { province ->
                    commodity in province.commodities && province.id != ProvinceId("florence")
                }
                .id
        val board =
            BoardState()
                .placeMarker(ProvinceId("florence"), p1)
                .board
                .placeMarker(whiteProvince, p2, PieceSide.WHITE)
                .board
        val state =
            state()
                .copy(
                    board = board,
                    adjustments = mapOf(commodity to listOf(CommodityAdjustment.SURPLUS)),
                )
        val view = json.readTree(json.writeValueAsString(state.viewForPlayer(p1)))
        val payout =
            view.path("commodityPayouts").single { it.path("commodity").asText() == commodity.name }
        val owners = payout.path("owners").toList()

        assertThat(owners.map { it.path("player").asText() }).containsExactly("p1", "p2")
        assertThat(owners.map { it.path("payout").asInt() }).containsExactly(0, 0)
        assertThat(
                owners
                    .single { it.path("player").asText() == "p1" }
                    .path("provinces")
                    .single()
                    .asText()
            )
            .isEqualTo("florence")
    }

    @Test
    fun privateCardChoicesAreAbsentFromAnotherView() {
        val state = state().copy(stage = Stage.PLAY_CARDS)
        val viewer =
            state
                .playerState(p1)
                .copy(hand = listOf(HandCard("private-card", CardData.definition("charlemagne"))))
        val held = state.copy(players = state.players + (p1 to viewer))
        val own = json.readTree(json.writeValueAsString(held.viewForPlayer(p1)))
        val otherView = held.viewForPlayer(p2)
        val other = json.writeValueAsString(otherView)

        assertThat(own.path("cardOffers").single().path("reference").asText())
            .isEqualTo("private-card")
        assertThat(
                own.path("cardOffers")
                    .single()
                    .path("leaderDiscounts")
                    .single()
                    .path("advance")
                    .asText()
            )
            .isEqualTo("NATIONALISM")
        assertThat(other).doesNotContain("private-card")
        assertThat(otherView.hand).isEmpty()
        assertThat(otherView.cardOffers).isEmpty()
        assertThat(
                json.writeValueAsString(
                    otherView.copy(cardInspections = emptyList(), unplayedCards = emptyList())
                )
            )
            .doesNotContain("charlemagne")
        assertThat(otherView.cardInspections).isEqualTo(state.viewForPlayer(p2).cardInspections)
        assertThat(otherView.unplayedCards).isEqualTo(state.viewForPlayer(p2).unplayedCards)
    }

    @Test
    fun setupSerializesUnavailableIncomeAndNoEconomicActions() {
        val view =
            json.readTree(json.writeValueAsString(GameState(state().header).viewForPlayer(p1)))

        assertThat(
                view.path("players").all { player ->
                    player.has("income") && player.path("income").isNull
                }
            )
            .isTrue()
        assertThat(view.path("expansionOffers").toList()).isEmpty()
        assertThat(view.path("cardOffers").toList()).isEmpty()
        assertThat(view.path("shippingOffer").isNull).isTrue()
    }

    @Test
    fun incomeSerializesConditionalProfitSeparately() {
        val view = json.readTree(json.writeValueAsString(state().viewForPlayer(p1)))
        val income = view.path("players").first().path("income")

        assertThat(income.path("ordinary").isInt).isTrue()
        assertThat(income.path("interestProfit").asInt()).isEqualTo(0)
        assertThat(income.path("reliefSpaces").asInt()).isEqualTo(0)
    }

    private fun state(): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                poolTokens = 20,
                                stockTokens = 16,
                            )
                    }
                    .toMap(),
        )
    }
}
