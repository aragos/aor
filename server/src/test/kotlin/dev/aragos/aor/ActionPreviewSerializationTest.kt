package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ActionPreviewSerializationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun selectedCardTargetSerializesPrintedMiseryEndpointsAndChaos() {
        val initial = state()
        val before =
            initial.copy(
                stage = Stage.PLAY_CARDS,
                players =
                    initial.players +
                        (p1 to
                            initial
                                .playerState(p1)
                                .copy(
                                    hand =
                                        listOf(
                                            HandCard(
                                                "private-civil-war",
                                                CardData.definition("civil-war"),
                                            )
                                        )
                                )) +
                        (p2 to initial.playerState(p2).copy(misery = MiseryStep.THOUSAND)),
            )

        val view = json.readTree(json.writeValueAsString(before.viewForPlayer(p1)))
        val choice =
            view.path("cardOffers").single().path("choices").single {
                it.path("value").asText() == "p2"
            }
        val change = choice.path("miseryChanges").single()

        assertThat(change.path("player").asText()).isEqualTo("p2")
        assertThat(change.path("fromValue").asInt()).isEqualTo(1000)
        assertThat(change.path("toValue").isNull).isTrue()
        assertThat(before.viewForPlayer(p2).cardOffers).isEmpty()
        assertThat(json.writeValueAsString(before.viewForPlayer(p2)))
            .doesNotContain("private-civil-war")
    }

    @Test
    fun advancePriceBreakdownAndMiseryUseTheAuthoritativeQuote() {
        val initial = state()
        val buyer =
            initial
                .playerState(p1)
                .copy(
                    advances =
                        PlayerAdvances(
                            listOf(
                                    AdvanceId.THE_HEAVENS,
                                    AdvanceId.WRITTEN_RECORD,
                                    AdvanceId.INSTITUTIONAL_RESEARCH,
                                )
                                .map { AdvanceAcquisition(it, 1) }
                        )
                )
        val before =
            initial.copy(
                players = initial.players + (p1 to buyer),
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.ANDREAS_VESALIUS,
                                    p1,
                                    CardLeaderRole.DISPLAY,
                                )
                            )
                    ),
            )

        val view = json.readTree(json.writeValueAsString(before.viewForPlayer(p1)))
        val offer =
            view.path("advanceOffers").single { it.path("advance").asText() == "HUMAN_BODY" }
        val breakdown = offer.path("priceBreakdown")

        assertThat(breakdown.path("baseCost").asInt()).isEqualTo(60)
        assertThat(breakdown.path("categoryCredit").asInt()).isEqualTo(20)
        assertThat(breakdown.path("advanceCredits").single().path("advance").asText())
            .isEqualTo("THE_HEAVENS")
        assertThat(breakdown.path("advanceCredits").single().path("amount").asInt()).isEqualTo(20)
        assertThat(breakdown.path("effectiveDiscount").asInt()).isEqualTo(60)
        assertThat(breakdown.path("institutionalResearchCredit").asInt()).isEqualTo(10)
        assertThat(breakdown.path("leaders").single().path("amount").asInt()).isEqualTo(30)
        assertThat(breakdown.path("price").asInt()).isEqualTo(offer.path("price").asInt())
        assertThat(offer.path("miseryChanges").single().path("fromValue").asInt()).isEqualTo(100)
        assertThat(offer.path("miseryChanges").single().path("toValue").asInt()).isEqualTo(90)
    }

    @Test
    fun blockedAdvanceStillSerializesItsNamedCreditsAndPrice() {
        val initial = state()
        val player =
            initial
                .playerState(p1)
                .copy(
                    advances =
                        PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.OVERLAND_EAST, 1)))
                )
        val before = initial.copy(players = initial.players + (p1 to player))

        val view = json.readTree(json.writeValueAsString(before.viewForPlayer(p1)))
        val offer =
            view.path("advanceOffers").single {
                it.path("advance").asText() == "OCEAN_NAVIGATION"
            }

        assertThat(offer.path("unavailableReason").asText())
            .isEqualTo("MISSING_ADVANCE_PREREQUISITE")
        assertThat(offer.path("price").asInt()).isEqualTo(100)
        assertThat(offer.path("priceBreakdown").path("price").asInt()).isEqualTo(100)
        assertThat(
                offer
                    .path("priceBreakdown")
                    .path("advanceCredits")
                    .single()
                    .path("advance")
                    .asText()
            )
            .isEqualTo("OVERLAND_EAST")
    }

    @Test
    fun expansionCardTokenCostIsAvailableToItsActingPlayer() {
        val before =
            state()
                .copy(stage = Stage.EXPANSION, flow = TurnFlow(actor = p1, expansionCardCount = 1))

        val view = json.readTree(json.writeValueAsString(before.viewForPlayer(p1)))
        val waiting = json.readTree(json.writeValueAsString(before.viewForPlayer(p2)))

        assertThat(view.path("expansionCardCost").asInt()).isEqualTo(6)
        assertThat(waiting.path("expansionCardCost").isNull).isTrue()
    }

    private fun state(): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            flow = TurnFlow(actor = p1, purchaseStep = PurchaseStep.ADVANCES, phaseStarted = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = 1000,
                                misery = MiseryStep.HUNDRED,
                            )
                    }
                    .toMap(),
        )
    }
}
