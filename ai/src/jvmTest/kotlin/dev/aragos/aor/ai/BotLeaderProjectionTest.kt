package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.RuleViolation
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BotLeaderProjectionTest {
    private val buyer = PlayerId("p1")
    private val ids = listOf(buyer, PlayerId("p2"), PlayerId("p3"))

    @Test
    fun currentAdvanceAndShippingForecastsDoNotAuthorizeEarlyPurchases() {
        val state = fixture("charlemagne", cash = 100)

        val view = state.viewForDecision(buyer)
        val observation = BotObservations.from(state, buyer)

        assertThat(view.advanceOffers).isEqualTo(state.viewForPlayer(buyer).advanceOffers)
        assertThat(view.purchaseShippingForecast?.cost).isEqualTo(10)
        assertThat(view.shippingOffer).isNull()
        assertThat(observation.candidates.none { it.command.startsWith("buy-advance") }).isTrue()
        assertThat(observation.candidates.none { it.command == "upgrade-ships" }).isTrue()
        assertFailsWith<RuleViolation> {
            state.step(TextCodec.parse("p1: buy-advance W"))
        }
    }

    @Test
    fun anOlderCardViewWithoutAShippingForecastKeepsTheLegacyPolicy() {
        val state = fixture("charlemagne", cash = 100)
        val view = state.viewForDecision(buyer).copy(purchaseShippingForecast = null)

        val observation = BotObservations.from(view, state.legalChoices(buyer), buyer, "play-cards")

        assertThat(observation.leaderPlanning).isNull()
    }

    @Test
    fun theHumanGamesCharlemagneCannotUseItsCreditAtTwentyTwoCash() {
        val observation = BotObservations.from(fixture("charlemagne", cash = 22), buyer)

        val with = advances(observation, withLeader = true)

        assertThat(with.none { it.command == "buy-advance W" }).isTrue()
        assertThat(choose(observation)).isEqualTo("pass")
    }

    @Test
    fun missingPrerequisitesExcludeAnOtherwiseAffordableLeaderTarget() {
        val observation = BotObservations.from(fixture("christopher-columbus", cash = 500), buyer)

        val with = advances(observation, withLeader = true)

        assertThat(with.none { it.command in setOf("buy-advance T", "buy-advance U") }).isTrue()
    }

    @Test
    fun existingAuthorizedCreditsArePreservedAndExtraDiscountStopsAtZero() {
        val initial = fixture("william-caxton", cash = 100, owned = setOf(AdvanceId.WRITTEN_RECORD))
        val state =
            initial.copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.JOHANN_GUTENBERG,
                                    buyer,
                                    CardLeaderRole.DISPLAY,
                                )
                            )
                    )
            )
        val observation = BotObservations.from(state, buyer)

        val before =
            advances(observation, withLeader = false).single { it.command == "buy-advance O" }
        val after =
            advances(observation, withLeader = true).single { it.command == "buy-advance O" }

        assertThat(before.cashCost).isEqualTo(10)
        assertThat(after.cashCost).isEqualTo(0)
        assertThat(before.cashCost - after.cashCost).isEqualTo(10)
    }

    @Test
    fun printedWordRebateAndProtectionSetTheSameCashInBothComparisonArms() {
        val initial =
            fixture(
                "leonardo-da-vinci",
                cash = 10,
                owned =
                    setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD, AdvanceId.HUMAN_BODY),
            )
        val state =
            initial.copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.ST_BENEDICT,
                                    ids[1],
                                    CardLeaderRole.DISPLAY,
                                )
                            )
                    )
            )
        val observation = BotObservations.from(state, buyer)

        val without = forecast(observation, withLeader = false, command = "play held protect 5")
        val with = forecast(observation, withLeader = true, command = "play held protect 5")

        assertThat(without.cash).isEqualTo(25)
        assertThat(with.cash).isEqualTo(25)
        assertThat(with.stabilization).isEqualTo(without.stabilization)
        assertThat(with.stabilization).isEqualTo(0.0)
    }

    @Test
    fun buyingShippingConsumesCashBeforeTheDiscountedPurchaseQuote() {
        val observation = BotObservations.from(fixture("dionysus-exiguus", cash = 15), buyer)

        val skipped = advances(observation, withLeader = true)
        val upgraded = advances(observation, withLeader = true, shipping = "upgrade-ships")

        assertThat(skipped.single { it.command == "buy-advance N" }.cashCost).isEqualTo(10)
        assertThat(upgraded.none { it.command == "buy-advance N" }).isTrue()
        assertThat(forecast(observation, withLeader = true, shipping = "upgrade-ships").cash)
            .isEqualTo(5)
    }

    @Test
    fun aNewlyAffordableNavigationQuoteRetainsTheSharedShippingReplacementSignal() {
        val initial =
            fixture(
                "prince-henry",
                cash = 70,
                owned =
                    setOf(
                        AdvanceId.THE_HEAVENS,
                        AdvanceId.OVERLAND_EAST,
                        AdvanceId.SEAWORTHY_VESSELS,
                    ),
            )
        val state =
            initial.copy(
                players =
                    initial.players +
                        (buyer to
                            initial
                                .playerState(buyer)
                                .copy(ships = ShippingCapability.SEAWORTHY_10))
            )
        val planning = planning(BotObservations.from(state, buyer))
        val leader =
            checkNotNull(planning.leaders["play held"]) { "The fixture requires its leader." }
        val continuation =
            checkNotNull(leader.withLeader) { "The fixture requires a purchase continuation." }

        val upgrade =
            planning.forecasts[continuation.shipping]
                .candidates
                .map { planning.candidates[it] }
                .single { it.command == "upgrade-ships" }

        assertThat(upgrade.features[BotPurchaseFeature.NAVIGATION_REPLACEMENT.key]).isEqualTo(-10.0)
    }

    @Test
    fun finalCardPlayHasNoShippingOrPurchaseContinuation() {
        val state =
            fixture("charlemagne", cash = 100)
                .copy(
                    stage = Stage.FINAL_CARD_PLAY,
                    endingTriggered = true,
                )

        val view = state.viewForDecision(buyer)
        val planning = planning(BotObservations.from(state, buyer))

        assertThat(view.purchaseShippingForecast).isNull()
        assertThat(planning.forecasts).isEmpty()
        assertThat(
                planning.leaders.values.all {
                    it.withLeader == null && it.withoutLeader == null && it.retainedDiscount == 0.0
                }
            )
            .isTrue()
    }

    @Test
    fun zeroProtectionAndThePlainPlayShareEveryForecast() {
        val initial = fixture("dionysus-exiguus", cash = 50)
        val state =
            initial.copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.ST_BENEDICT,
                                    ids[1],
                                    CardLeaderRole.DISPLAY,
                                )
                            )
                    )
            )

        val planning = planning(BotObservations.from(state, buyer))

        assertThat(planning.leaders["play held"]).isEqualTo(planning.leaders["play held protect 0"])
    }

    @Test
    fun anotherPlayersPrivateHandCannotChangeTheForecast() {
        val initial = fixture("dionysus-exiguus", cash = 50)
        val other = initial.playerState(ids[1])
        val first =
            initial.copy(
                players =
                    initial.players +
                        (ids[1] to
                            other.copy(
                                hand = listOf(HandCard("private", CardData.definition("wool-1")))
                            ))
            )
        val second =
            initial.copy(
                players =
                    initial.players +
                        (ids[1] to
                            other.copy(
                                hand =
                                    listOf(HandCard("private", CardData.definition("charlemagne")))
                            ))
            )

        val firstPlanning = planning(BotObservations.from(first, buyer))
        val secondPlanning = planning(BotObservations.from(second, buyer))

        assertThat(firstPlanning).isEqualTo(secondPlanning)
    }

    private fun choose(observation: BotObservation): String =
        BotPolicy.choose(
            observation,
            BotDifficulty.HARD,
            0,
            BotModel(
                weights =
                    mapOf(
                        "cash" to 1.0,
                        "discount" to 1.0,
                        BotLeaderTiming.GATE to 1.0,
                        BotLeaderTiming.RETENTION to 0.5,
                    )
            ),
        )

    private fun planning(observation: BotObservation): BotLeaderPlanning =
        checkNotNull(observation.leaderPlanning) { "The fixture requires a leader forecast." }

    private fun forecast(
        observation: BotObservation,
        withLeader: Boolean,
        command: String = "play held",
        shipping: String = "pass",
    ): BotPurchaseForecast {
        val planning = planning(observation)
        val leader =
            checkNotNull(planning.leaders[command]) { "The fixture requires its leader choice." }
        val continuation =
            checkNotNull(if (withLeader) leader.withLeader else leader.withoutLeader) {
                "The fixture requires a pending purchase phase."
            }
        return planning.forecasts[
                checkNotNull(continuation.advances[shipping]) {
                    "The fixture requires the requested shipping continuation."
                }]
    }

    private fun advances(
        observation: BotObservation,
        withLeader: Boolean,
        shipping: String = "pass",
    ): List<BotCandidate> {
        val planning = planning(observation)
        return forecast(observation, withLeader, shipping = shipping).candidates.map {
            planning.candidates[it]
        }
    }

    private fun fixture(card: String, cash: Int, owned: Set<AdvanceId> = emptySet()): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, buyer),
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = cash,
                                ships = ShippingCapability.NONE,
                                advances =
                                    if (id == buyer)
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                                hand =
                                    if (id == buyer)
                                        listOf(HandCard("held", CardData.definition(card)))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = buyer, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
