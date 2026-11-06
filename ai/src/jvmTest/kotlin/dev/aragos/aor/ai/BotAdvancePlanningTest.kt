package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotAdvancePlanningTest {
    private val buyer = PlayerId("p1")
    private val research = setOf("buy-advance X")
    private val gate = BotAdvanceFeature.RESEARCH_SAVING.key

    @Test
    fun researchFirstPreservesAllFourPurchasesAndLeavesTenMoreDollars() {
        val state = fixture(recordedAdvances)

        val researchFirst = purchase(state, listOf("X", "T", "W", "O"))
        val navigationFirst = purchase(state, listOf("T", "X", "W", "O"))

        assertThat(researchFirst.playerState(buyer).advances.owned)
            .isEqualTo(navigationFirst.playerState(buyer).advances.owned)
        assertThat(researchFirst.playerState(buyer).advances.owned)
            .containsAtLeast(
                AdvanceId.INSTITUTIONAL_RESEARCH,
                AdvanceId.OCEAN_NAVIGATION,
                AdvanceId.NATIONALISM,
                AdvanceId.PRINTED_WORD,
            )
        assertThat(researchFirst.playerState(buyer).cash).isEqualTo(35)
        assertThat(navigationFirst.playerState(buyer).cash).isEqualTo(25)
    }

    @Test
    fun enabledPlannerChoosesResearchBeforeTheHigherImmediateNavigationScore() {
        val observation = sequenceObservation()
        val model = sequenceModel() + (gate to 1.0)

        val command =
            BotPolicy.choose(observation, BotDifficulty.HARD, 0, BotModel(weights = model))

        assertThat(command).isEqualTo("buy-advance X")
    }

    @Test
    fun zeroWeightKeepsTheLegacyFirstMoveAndArithmetic() {
        val observation = sequenceObservation()
        val model = BotModel(weights = sequenceModel() + (gate to 0.0))

        val current = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)
        val legacy =
            BotPolicy.choose(observation.copy(advancePlanning = null), BotDifficulty.HARD, 0, model)

        assertThat(current).isEqualTo("buy-advance T")
        assertThat(current).isEqualTo(legacy)
        assertThat(BotAdvanceSequencing.forObservation(observation, model)).isNull()
    }

    @Test
    fun oneWeightCorrectsEveryConditionalCashContributionExactlyOnce() {
        val observation = sequenceObservation()
        val navigation = observation.candidates.single { it.command == "buy-advance T" }
        val model =
            BotModel(
                weights =
                    mapOf(
                        gate to 1.0,
                        "cash" to 1.25,
                        "count.4.cash" to 0.5,
                        "endgame.cash" to 0.3,
                        "deckModified.cash" to -0.1,
                        "points" to 20.0,
                        "reserveShortfall" to -100.0,
                        "planning.foregoneInterest" to 100.0,
                    )
            )
        val sequence =
            checkNotNull(BotAdvanceSequencing.forObservation(observation, model)) {
                "A positive research weight requires sequence evaluation."
            }

        val value = sequence.value(navigation, research)

        assertThat(sequence.price(navigation, research)).isEqualTo(70)
        assertThat(value).isWithin(1.0e-9).of(19.5)
    }

    @Test
    fun disabledTokenHeadCannotAlterResearchCashRepricing() {
        val reserved = "planning.tokenValue.v1.cost.cash"
        val observation =
            BotObservation(
                playerId = "p1",
                decision = "purchase-advances",
                context = mapOf("stabilization" to 0.0),
                availableCash = 120,
                availableTokens = 0,
                candidates =
                    listOf(
                        BotCandidate(
                            "buy-advance T",
                            mapOf("cash" to -80.0, "points" to 81.0, reserved to 1.0),
                            cashCost = 80,
                            group = "t",
                            family = "advance",
                        ),
                        BotCandidate(
                            "buy-advance X",
                            mapOf("cash" to -40.0, "points" to 40.0),
                            cashCost = 40,
                            group = "x",
                            family = "advance",
                        ),
                        BotCandidate("pass", emptyMap()),
                    ),
                rulesVersion = RULES_VERSION,
                dataVersion = DATA_VERSION,
                advancePlanning =
                    BotAdvancePlanning(
                        steps =
                            mapOf(
                                "buy-advance T" to BotAdvanceStep(AdvanceId.OCEAN_NAVIGATION, 10),
                                "buy-advance X" to
                                    BotAdvanceStep(AdvanceId.INSTITUTIONAL_RESEARCH, 0),
                            )
                    ),
            )
        val model =
            BotModel(
                weights =
                    mapOf(
                        "cash" to 1.0,
                        "points" to 1.0,
                        gate to 1.0,
                        BotTokenValue.GATE to 0.0,
                        reserved to 2000.0,
                    )
            )

        val command = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        // X then T scores 0 + 0.92 * (1 + 10), whereas T then X scores only 1.
        assertThat(command).isEqualTo("buy-advance X")
    }

    @Test
    fun aZeroFinalScoreReceivesItsOwnResearchPriceDerivative() {
        val original = sequenceObservation()
        val observation =
            original.copy(
                candidates =
                    original.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features +
                                    (BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 0.0)
                        )
                    }
            )
        val navigation = observation.candidates.single { it.command == "buy-advance T" }
        val model =
            BotModel(
                weights =
                    mapOf(
                        gate to 0.5,
                        "cash" to 1.25,
                        "count.4.cash" to 0.75,
                        BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 1.5,
                    )
            )
        val sequence =
            checkNotNull(BotAdvanceSequencing.forObservation(observation, model)) {
                "A positive research weight requires sequence evaluation."
            }

        val value = sequence.value(navigation, research)

        assertThat(sequence.price(navigation, research)).isEqualTo(70)
        assertThat(value).isEqualTo(25.0)
    }

    @Test
    fun missingFinalScoreDoesNotInventATerminalResearchBonus() {
        val observation = sequenceObservation()
        val navigation = observation.candidates.single { it.command == "buy-advance T" }
        val model =
            BotModel(
                weights =
                    mapOf(
                        gate to 0.5,
                        "cash" to 2.0,
                        BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 1.5,
                    )
            )
        val sequence =
            checkNotNull(BotAdvanceSequencing.forObservation(observation, model)) {
                "A positive research weight requires sequence evaluation."
            }

        val value = sequence.value(navigation, research)

        assertThat(value).isEqualTo(10.0)
    }

    @Test
    fun aTerminalWeightDoesNotEnableOtherwiseDisabledResearchRepricing() {
        val model =
            BotModel(
                weights = mapOf(gate to 0.0, BotEconomicFeature.FINAL_ADVANCE_SCORE.key to 1.0)
            )

        val sequence = BotAdvanceSequencing.forObservation(sequenceObservation(), model)

        assertThat(sequence).isNull()
    }

    @Test
    fun ordinarySameTurnPurchasesNeverAddCategoryCreditsToTheSequence() {
        val observation = BotObservations.from(fixture(emptyList(), cash = 1000), buyer)
        val humanBody = observation.candidates.single { it.command == "buy-advance B" }
        val sequence =
            checkNotNull(
                BotAdvanceSequencing.forObservation(
                    observation,
                    BotModel(weights = mapOf("cash" to 1.0, gate to 1.0)),
                )
            ) {
                "A positive research weight requires sequence evaluation."
            }

        val afterHeavens = sequence.price(humanBody, setOf("buy-advance A"))
        val afterResearchAndHeavens = sequence.price(humanBody, research + "buy-advance A")

        assertThat(afterHeavens).isEqualTo(60)
        assertThat(afterResearchAndHeavens).isEqualTo(50)
    }

    @Test
    fun researchDoesNotDiscountCivicsOrReligion() {
        val observation = BotObservations.from(fixture(emptyList(), cash = 1000), buyer)
        val planning =
            checkNotNull(observation.advancePlanning) {
                "A purchase observation requires planning quotes."
            }

        assertThat(planning.steps["buy-advance W"]?.institutionalResearchDiscount).isEqualTo(0)
        assertThat(planning.steps["buy-advance E"]?.institutionalResearchDiscount).isEqualTo(0)
        assertThat(planning.steps["buy-advance X"]?.institutionalResearchDiscount).isEqualTo(0)
    }

    @Test
    fun anAlreadyFreeAdvanceReceivesNoFictitiousCashSaving() {
        val observation =
            BotObservations.from(
                fixture(
                    listOf(
                        AdvanceId.HUMAN_BODY,
                        AdvanceId.LAWS_OF_MATTER,
                    )
                ),
                buyer,
            )
        val heavens = observation.candidates.single { it.command == "buy-advance A" }
        val sequence =
            checkNotNull(
                BotAdvanceSequencing.forObservation(
                    observation,
                    BotModel(weights = mapOf("cash" to 1.0, gate to 1.0)),
                )
            ) {
                "A positive research weight requires sequence evaluation."
            }

        assertThat(heavens.cashCost).isEqualTo(0)
        assertThat(sequence.price(heavens, research)).isEqualTo(0)
        assertThat(sequence.value(heavens, research)).isEqualTo(0.0)
    }

    @Test
    fun existingResearchOwnershipCannotEarnTheDiscountTwice() {
        val observation =
            BotObservations.from(
                fixture(recordedAdvances + AdvanceId.INSTITUTIONAL_RESEARCH),
                buyer,
            )

        val planning =
            checkNotNull(observation.advancePlanning) {
                "A purchase observation requires planning quotes."
            }

        assertThat(planning.steps.values.all { it.institutionalResearchDiscount == 0 }).isTrue()
    }

    @Test
    fun incomePlanningUsesTheOwnersCurrentPublicQuoteIncludingOwnedMiddleClass() {
        val observation = BotObservations.from(fixture(recordedAdvances), buyer)

        val planning =
            checkNotNull(observation.advancePlanning) {
                "A purchase observation requires planning quotes."
            }

        assertThat(planning.ownsInterestProfit).isTrue()
        assertThat(planning.ordinaryIncome).isEqualTo(29)
    }

    private fun sequenceObservation(): BotObservation {
        val state = fixture(recordedAdvances)
        val observation = BotObservations.from(state, buyer)
        val commands =
            setOf("buy-advance T", "buy-advance X", "buy-advance W", "buy-advance O", "pass")
        val candidates = observation.candidates.filter { it.command in commands }
        return observation.copy(
            candidates = candidates,
            advancePlanning =
                BotAdvancePlanningProjection.from(state.viewForDecision(buyer), buyer, candidates),
        )
    }

    private fun sequenceModel(): Map<String, Double> =
        mapOf(
            "cash" to 1.0,
            "advance.T" to 106.9,
            "advance.X" to 50.4,
            "advance.W" to 9.7,
            "advance.O" to 47.6,
        )

    private fun purchase(state: GameState, letters: List<String>): GameState =
        letters.fold(state) { current, letter ->
            current.step(TextCodec.parseAction("buy-advance $letter", current, buyer))
        }

    private fun fixture(advances: List<AdvanceId>, cash: Int = 185): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, buyer),
            rules =
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                ),
            currentEpoch = Epoch.THREE,
            turnNumber = 14,
            stage = Stage.PURCHASE,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow =
                TurnFlow(
                    actor = buyer,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = cash,
                                misery = MiseryStep.HUNDRED_FIFTY,
                                advances =
                                    if (player.id == buyer) {
                                        PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) })
                                    } else {
                                        PlayerAdvances()
                                    },
                            )
                    }
                    .toMap(),
        )
    }

    private val recordedAdvances =
        listOf(
            AdvanceId.CARAVAN,
            AdvanceId.ENLIGHTENMENT,
            AdvanceId.HUMAN_BODY,
            AdvanceId.IMPROVED_AGRICULTURE,
            AdvanceId.INTEREST_PROFIT,
            AdvanceId.LAWS_OF_MATTER,
            AdvanceId.MIDDLE_CLASS,
            AdvanceId.OVERLAND_EAST,
            AdvanceId.SEAWORTHY_VESSELS,
            AdvanceId.THE_HEAVENS,
            AdvanceId.URBAN_ASCENDANCY,
            AdvanceId.WIND_WATERMILL,
            AdvanceId.WRITTEN_RECORD,
        )
}
