package dev.aragos.aor.ai

import dev.aragos.aor.CardChoiceKind
import dev.aragos.aor.CardChoiceView
import dev.aragos.aor.CardData
import dev.aragos.aor.CardOfferView
import dev.aragos.aor.CashChangeView
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommodityTimingPolicyTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val actor = players.first().id
    private val header = GameHeader(players, actor)
    private val gold = "play gold"
    private val ordinary =
        BotObservation(
            playerId = actor.value,
            decision = "play-cards",
            context = emptyMap(),
            availableCash = 50,
            availableTokens = 10,
            candidates =
                listOf(
                    BotCandidate("pass", emptyMap()),
                    BotCandidate(gold, mapOf(BotEconomicFeature.RETAINED_COMMODITY.key to -24.0)),
                ),
            rulesVersion = header.rulesVersion,
            dataVersion = header.dataVersion,
        )
    private val encoded =
        CardObservation(
            stateFeatures = emptyMap(),
            actions = ordinary.candidates.map { CardAction(it.command, emptyMap()) },
            baselineIndex = 1,
        )
    private val original = CardPolicyEvaluation(listOf(0.0, 0.0), listOf(0.5, 0.5), 0.5)
    private val config = CommodityTimingConfig(0.1, 10, 0)

    private fun view(payout: Int = 0) =
        GameState(header)
            .viewForPlayer(actor)
            .copy(
                stage = Stage.PLAY_CARDS,
                hand = listOf(HandCard("gold", CardData.definition("gold-3"))),
                cardOffers =
                    listOf(
                        CardOfferView(
                            reference = "gold",
                            playable = true,
                            blockers = emptyList(),
                            choices =
                                listOf(
                                    CardChoiceView(
                                        CardChoiceKind.NONE,
                                        null,
                                        gold,
                                        cashChanges =
                                            listOf(CashChangeView(actor, 50, 50 + payout)),
                                    )
                                ),
                            commodities = setOf(Commodity.GOLD),
                            leaderDiscounts = emptyList(),
                            provinceTargets = emptySet(),
                            requiredTargetCount = null,
                            protectionMax = null,
                        )
                    ),
            )

    @Test
    fun suppressesAZeroPayoutWhenAffordablePlacementsImproveIt() {
        val adjusted =
            CommodityTimingPolicy.adjust(view(), actor, ordinary, encoded, original, config)

        assertTrue(adjusted.probabilities[1] < 0.1)
    }

    @Test
    fun preservesTheCurrentReceiptOutsideTheConfiguredLimit() {
        val adjusted =
            CommodityTimingPolicy.adjust(view(15), actor, ordinary, encoded, original, config)

        assertEquals(original, adjusted)
    }

    @Test
    fun preservesThePolicyWhenTheGameIsEnding() {
        val adjusted =
            CommodityTimingPolicy.adjust(
                view().copy(endingTriggered = true),
                actor,
                ordinary,
                encoded,
                original,
                config,
            )

        assertEquals(original, adjusted)
    }
}
