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
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotLeaderCashTest {
    private val buyer = PlayerId("p1")
    private val ids = listOf(buyer, PlayerId("p2"), PlayerId("p3"))
    private val printedWord =
        setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD, AdvanceId.HUMAN_BODY)
    private val interest = BotEconomicFeature.FOREGONE_INTEREST.key

    @Test
    fun zeroProtectionMatchesTheOrdinaryPlayIncludingItsRebateAndScore() {
        val observation =
            BotObservations.from(
                fixture(owned = printedWord + AdvanceId.INTEREST_PROFIT),
                buyer,
            )
        val plain = observation.candidates.single { it.command == "play held" }
        val protected = observation.candidates.single { it.command == "play held protect 0" }

        assertThat(protected.features).isEqualTo(plain.features)
        assertThat(BotPolicy.score(protected)).isEqualTo(BotPolicy.score(plain))
        assertThat(protected.cashCost).isEqualTo(0)
    }

    @Test
    fun aRebateFundedEscrowDoesNotInventReserveOrInterestLosses() {
        val state = fixture(owned = printedWord + AdvanceId.INTEREST_PROFIT)
        val observation = BotObservations.from(state, buyer)
        val protected = observation.candidates.single { it.command == "play held protect 20" }

        val played = state.step(TextCodec.parse("p1: ${protected.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(10)
        assertThat(protected.cashCost).isEqualTo(20)
        assertThat(protected.features["cash"]).isEqualTo(1.0)
        assertThat(protected.features).doesNotContainKey("reserveShortfall")
        assertThat(protected.features).doesNotContainKey(interest)
        assertThat(forecastCash(observation, protected.command)).containsExactly(10, 10)
    }

    @Test
    fun spendingTheEntireRebateAndBalanceChargesOnlyTheNetLoss() {
        val state = fixture(owned = printedWord + AdvanceId.INTEREST_PROFIT)
        val protected = candidate(state, "play held protect 30")

        val played = state.step(TextCodec.parse("p1: ${protected.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(0)
        assertThat(protected.features["cash"]).isEqualTo(-9.0)
        assertThat(protected.features["reserveShortfall"]).isEqualTo(1.0)
        assertThat(protected.features[interest]).isEqualTo(-10.0)
    }

    @Test
    fun aRebateCanIncreasePendingInterestOnlyUpToTheOrdinaryIncomeCap() {
        val state = fixture(owned = printedWord + AdvanceId.INTEREST_PROFIT)
        val plain = candidate(state, "play held")

        val played = state.step(TextCodec.parse("p1: ${plain.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(30)
        assertThat(plain.features["cash"]).isEqualTo(21.0)
        assertThat(plain.features[interest]).isEqualTo(8.0)
    }

    @Test
    fun aRebateRelievesTheExistingReserveShortfallWithoutDuplicatingHandSavings() {
        val state =
            fixture(
                owned = printedWord,
                otherCards = listOf("wool-1", "stone-1", "timber", "fur"),
            )

        val plain = candidate(state, "play held")

        assertThat(plain.features["cash"]).isEqualTo(25.0)
        assertThat(plain.features["reserveShortfall"]).isEqualTo(-5.0)
    }

    @Test
    fun allOwnedTargetsRebateTheirPrintedAmountsWithoutWrittenRecordBonuses() {
        val state = fixture(owned = printedWord + AdvanceId.MASTER_ART)
        val plain = candidate(state, "play held")

        val played = state.step(TextCodec.parse("p1: ${plain.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(50)
        assertThat(plain.features["cash"]).isEqualTo(41.0)
    }

    @Test
    fun finalCardProtectionKeepsTheRebateWithoutInventingAnotherIncomeReceipt() {
        val state =
            fixture(owned = printedWord + AdvanceId.INTEREST_PROFIT)
                .copy(stage = Stage.FINAL_CARD_PLAY, endingTriggered = true)
        val protected = candidate(state, "play held protect 5")

        val played = state.step(TextCodec.parse("p1: ${protected.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(25)
        assertThat(protected.features["cash"]).isEqualTo(16.0)
        assertThat(protected.features).doesNotContainKey(interest)
    }

    @Test
    fun aPlainLeaderWithoutPrintedWordKeepsItsOriginalCashFeatures() {
        val state = fixture(owned = setOf(AdvanceId.HUMAN_BODY, AdvanceId.INTEREST_PROFIT))
        val plain = candidate(state, "play held")

        val played = state.step(TextCodec.parse("p1: ${plain.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(10)
        assertThat(plain.cashCost).isEqualTo(0)
        assertThat(plain.features["cash"]).isEqualTo(1.0)
        assertThat(plain.features).doesNotContainKey("reserveShortfall")
        assertThat(plain.features).doesNotContainKey(interest)
        assertThat(plain.features).doesNotContainKey("protection")
    }

    @Test
    fun protectionWithoutPrintedWordStillDebitsItsFullEscrow() {
        val state = fixture(owned = setOf(AdvanceId.HUMAN_BODY, AdvanceId.INTEREST_PROFIT))
        val protected = candidate(state, "play held protect 5")

        val played = state.step(TextCodec.parse("p1: ${protected.command}"))

        assertThat(played.playerState(buyer).cash).isEqualTo(5)
        assertThat(protected.features["cash"]).isEqualTo(-4.0)
        assertThat(protected.features[interest]).isEqualTo(-5.0)
        assertThat(protected.features["protection"]).isEqualTo(5.0)
    }

    private fun candidate(state: GameState, command: String): BotCandidate =
        BotObservations.from(state, buyer).candidates.single { it.command == command }

    private fun forecastCash(observation: BotObservation, command: String): List<Int> {
        val planning =
            checkNotNull(observation.leaderPlanning) {
                "The fixture requires leader purchase forecasts."
            }
        val leader =
            checkNotNull(planning.leaders[command]) {
                "The fixture requires the protected leader's forecast."
            }
        return listOf(leader.withoutLeader, leader.withLeader).map { continuation ->
            val branch =
                checkNotNull(continuation) {
                    "The fixture requires an open purchase window."
                }
            planning.forecasts[branch.shipping].cash
        }
    }

    private fun fixture(
        owned: Set<AdvanceId>,
        otherCards: List<String> = emptyList(),
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        val hand =
            listOf(HandCard("held", CardData.definition("leonardo-da-vinci"))) +
                otherCards.mapIndexed { index, card ->
                    HandCard("other-$index", CardData.definition(card))
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
                                cash = 10,
                                advances =
                                    if (id == buyer) {
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    } else PlayerAdvances(),
                                hand = if (id == buyer) hand else emptyList(),
                            )
                    }
                    .toMap(),
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
                ),
            flow = TurnFlow(actor = buyer, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
