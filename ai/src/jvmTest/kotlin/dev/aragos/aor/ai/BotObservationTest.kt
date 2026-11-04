package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardDecision
import dev.aragos.aor.BoardState
import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardData
import dev.aragos.aor.CardDecision
import dev.aragos.aor.CardGame
import dev.aragos.aor.CardKind
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.Entry
import dev.aragos.aor.Epoch
import dev.aragos.aor.EventId
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.MarkerPlacementAftermath
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnDecision
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.WarResult
import dev.aragos.aor.WarRound
import dev.aragos.aor.WarState
import kotlin.test.Test

class BotObservationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val ids = listOf(p1, p2, PlayerId("p3"))

    @Test
    fun unseenHandContentsAndDeckOrderCannotChangeAnObservationOrMove() {
        val state =
            fixture()
                .copy(
                    stage = Stage.PLAY_CARDS,
                    deck = listOf("armor", "stirrups"),
                )
                .withHand(p1, "timber")
                .withHand(p2, "armor")
        val other = requireNotNull(state.players[p2]) { "The opponent fixture requires its seat." }
        val hiddenChanged =
            state.copy(
                deck = state.deck.reversed(),
                players =
                    state.players +
                        (p2 to
                            other.copy(
                                hand =
                                    listOf(
                                        HandCard(
                                            other.hand.single().reference,
                                            CardData.definition("stirrups"),
                                        )
                                    )
                            )),
            )

        val first = BotObservations.from(state, p1)
        val second = BotObservations.from(hiddenChanged, p1)

        assertThat(second).isEqualTo(first)
        assertThat(BotPolicy.choose(second, BotDifficulty.HARD, 42))
            .isEqualTo(BotPolicy.choose(first, BotDifficulty.HARD, 42))
    }

    @Test
    fun anUnrevealedOpponentBidCannotInfluenceThePolicy() {
        val state = fixture().copy(stage = Stage.BIDDING)
        val opponent = requireNotNull(state.players[p2]) { "The fixture requires an opponent." }
        val submitted = state.copy(players = state.players + (p2 to opponent.copy(capitalBid = 37)))

        val first = BotObservations.from(state, p1)
        val second = BotObservations.from(submitted, p1)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun richTokenPurchasesKeepNumericSuggestionsBounded() {
        val original = fixture().copy(stage = Stage.TOKEN_PURCHASE)
        val owner = requireNotNull(original.players[p1]) { "The purchase requires its owner." }
        val state = original.copy(players = original.players + (p1 to owner.copy(cash = 10000)))

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.size).isAtMost(75)
        assertThat(observation.candidates.map { it.command })
            .containsAtLeast(
                "buy-tokens -10000",
                "buy-tokens 0",
                "buy-tokens 10000",
            )
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun capitalFeaturesConditionOnTheActualPrivateInitialCard() {
        val original = fixture()
        val state =
            original
                .copy(
                    stage = Stage.CAPITAL_CHOICE,
                    turnNumber = null,
                    board = BoardState(),
                    capitalSelection = CapitalSelection.fromBids(ids.zip(listOf(3, 2, 1)).toMap()),
                    turnOrderSelection = TurnOrderSelection(),
                    flow = TurnFlow(),
                    players =
                        original.players.mapValues { (_, owner) ->
                            owner.copy(
                                capital = null,
                                capitalBid = 3 - ids.indexOf(owner.player.id),
                            )
                        },
                )
                .withHand(p1, "cloth-wine")

        val observation = BotObservations.from(state, p1)

        val venice = observation.candidates.single { it.command == "choose-capital venice" }
        val barcelona = observation.candidates.single { it.command == "choose-capital barcelona" }
        assertThat(venice.features["initialCommodity"]).isGreaterThan(0.0)
        assertThat(barcelona.features["initialCommodity"]).isGreaterThan(0.0)
        val differentHand = BotObservations.from(state.withHand(p1, "metal"), p1)
        val differentVenice =
            differentHand.candidates.single {
                it.command == "choose-capital venice"
            }
        assertThat(differentVenice.features["initialCommodity"] ?: 0.0).isEqualTo(0.0)
        assertThat(venice.features["capitalHand.venice.cloth-wine"]).isEqualTo(1.0)
        val learned =
            BotModel(
                weights = BotModels.default.weights + ("capitalHand.barcelona.cloth-wine" to 1000.0)
            )
        assertThat(BotPolicy.choose(observation, BotDifficulty.NORMAL, 0, learned))
            .isEqualTo("choose-capital barcelona")
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun expansionIncludesWholeMarketsAndAllCandidatesAreLegal() {
        val state = fixture()

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.any { it.command.startsWith("place 3 ") }).isTrue()
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun everyCardUsesLegalProjectedTargetsIncludingBoundedPiratesAlternatives() {
        val initial = fixture().copy(stage = Stage.PLAY_CARDS, currentEpoch = Epoch.THREE)
        val state = initial.withHand(p1, *CardData.all.map { it.id }.toTypedArray())

        val observation = BotObservations.from(state, p1)

        assertThat(
                observation.candidates.count {
                    it.command.startsWith("play held-pirates-vikings provinces")
                }
            )
            .isGreaterThan(1)
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun anUnresolvedWarExcludesAnotherWarFromBotCandidates() {
        val state =
            fixture()
                .copy(
                    stage = Stage.PLAY_CARDS,
                    activeWar =
                        WarState(
                            p2,
                            ids.last(),
                            startedTurn = 1,
                            ties = 1,
                            lastRound = WarRound(2, finalCardPlay = false, actor = p1),
                        ),
                )
                .withHand(p1, "war", "timber")

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.map { it.command })
            .containsExactly("pass", "play held-timber commodity timber")
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun aFinalHandWithOnlyABlockedWarStillHasALegalBotCandidate() {
        val state =
            fixture()
                .copy(
                    stage = Stage.FINAL_CARD_PLAY,
                    activeWar =
                        WarState(
                            p2,
                            ids.last(),
                            startedTurn = 1,
                            ties = 1,
                            lastRound = WarRound(2, finalCardPlay = true, actor = p1),
                        ),
                )
                .withHand(p1, "war")

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.map { it.command }).containsExactly("pass")
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun replacementIncludesEveryAffordableProvinceRatherThanOnlyDeclining() {
        val decision =
            CardDecision.ReplacementToken(
                p1,
                listOf(ProvinceId("rome"), ProvinceId("florence")),
                EventId.BLACK_DEATH,
            )
        val original = fixture()
        val state =
            original
                .copy(
                    board =
                        original.board
                            .remove(ProvinceId("rome"))
                            .board
                            .remove(ProvinceId("florence"))
                            .board
                )
                .withDecision(Stage.PLAY_CARDS, decision)

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.map { it.command })
            .contains("replace-event-tokens florence rome")
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun warSurrenderProvidesAlternativesBeyondTheDefaultSubset() {
        val state =
            fixture()
                .withDecision(
                    Stage.PLAY_CARDS,
                    CardDecision.WarSurrender(
                        p2,
                        WarResult.Victory(p1, p2, 1, mapOf(p1 to 6, p2 to 5)),
                    ),
                )

        val observation = BotObservations.from(state, p2)

        assertThat(observation.candidates.count { it.command.startsWith("cede-war") })
            .isGreaterThan(1)
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun civilWarAndTradeNestedResponsesUseTheDecidingSeat() {
        val loss = fixture().withDecision(Stage.PLAY_CARDS, CardDecision.CivilWarLoss(p2))
        val withCards = fixture().withHand(p2, "armor", "stirrups")
        val owner = requireNotNull(withCards.players[p2]) { "A trade requires its card owner." }
        val trade =
            withCards
                .copy(
                    players =
                        withCards.players +
                            (p2 to
                                owner.copy(
                                    hand =
                                        owner.hand.mapIndexed { index, card ->
                                            card.copy(reference = "hand-${index + 1}")
                                        }
                                ))
                )
                .withDecision(Stage.EXPANSION, BoardDecision.TradeOffer(p2, p1))

        val lossObservation = BotObservations.from(loss, p2)
        val tradeObservation = BotObservations.from(trade, p2)

        assertThat(lossObservation.candidates).hasSize(2)
        assertThat(tradeObservation.candidates).hasSize(2)
        assertCandidatesLegal(loss, lossObservation)
        assertCandidatesLegal(trade, tradeObservation)
    }

    @Test
    fun immediateArtDiscardIsHandledThroughTheLivePurchaseInterruption() {
        val state =
            fixture()
                .copy(
                    rules = RuleOptions(immediateMasterArtDiscard = true),
                    stage = Stage.PURCHASE,
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                            purchaseStep = PurchaseStep.ADVANCES,
                        ),
                )
                .withHand(p1, "armor")
                .withAdvances(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD)
        val purchased = state.step(TextCodec.parse("p1: buy-advance P"))

        val observation = BotObservations.from(purchased, p1)

        assertThat(observation.decision).isEqualTo("immediate-master-art-discard")
        assertThat(observation.candidates.map { it.command }).contains("discard-card held-armor")
        assertCandidatesLegal(purchased, observation)
    }

    @Test
    fun preparationFiltersNonMilitaryCardsEvenThoughTheHandProjectionContainsThem() {
        val state = fixture().copy(stage = Stage.PLAY_CARDS).withHand(p1, "armor", "mongol-armies")
        val war =
            CardGame.play(
                state.withHand(p1, "war", "armor", "mongol-armies"),
                p1,
                "held-war",
                CardChoice.PlayerChoice(p2),
            )
        val preparation =
            war.copy(flow = war.flow.copy(decisions = listOf(CardDecision.WarPreparation(p1))))

        val observation = BotObservations.from(preparation, p1)

        assertThat(observation.candidates.map { it.command })
            .doesNotContain("play held-mongol-armies")
        assertCandidatesLegal(preparation, observation)
    }

    @Test
    fun cathedralUsesTheLiveCompetitionWindow() {
        val state = fixture().withAdvances(AdvanceId.CATHEDRAL)
        val attack = state.legalChoices(p1).commands.first { it.startsWith("attack ") }
        val pending = state.step(Entry.Action(p1, TextCodec.parseCommand(attack)))

        val observation = BotObservations.from(pending, p1)

        assertThat(observation.decision).isEqualTo("cathedral")
        assertThat(observation.candidates.map { it.command }).containsExactly("cathedral", "pass")
        assertCandidatesLegal(pending, observation)
    }

    @Test
    fun windmillPricesItsPendingTokenPayment() {
        val state =
            fixture()
                .withAdvances(AdvanceId.WIND_WATERMILL)
                .withHand(p1, "armor")
                .withHand(p2, "stirrups")
                .withDecision(
                    Stage.EXPANSION,
                    BoardDecision.WindmillOpportunity(p1, p2, ProvinceId("rome")),
                )

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.single { it.command == "windmill" }.tokenCost)
            .isEqualTo(state.catalog.provinces.single { it.id == ProvinceId("rome") }.marketNumber)
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun renaissanceIsHandledWhenTheRealPhaseOpeningCreatesItsWindow() {
        val state =
            fixture()
                .withAdvances(AdvanceId.RENAISSANCE)
                .copy(
                    stage = Stage.PLAY_CARDS,
                    flow =
                        TurnFlow(
                            actor = ids.last(),
                            cursor = 2,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                        ),
                )
        val pending = state.step(TextCodec.parse("${ids.last()}: pass"))

        val observation = BotObservations.from(pending, p1)

        assertThat(observation.decision).isEqualTo("renaissance")
        assertThat(observation.candidates.map { it.command }).contains("swap-order p2")
        assertCandidatesLegal(pending, observation)
    }

    @Test
    fun patronageUsesTheActualExposedLeaderPrice() {
        val state =
            fixture()
                .withAdvances(AdvanceId.PATRONAGE)
                .copy(
                    stage = Stage.PURCHASE,
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                            purchaseStep = PurchaseStep.ADVANCES,
                        ),
                    effects = leaders(),
                )

        val observation = BotObservations.from(state, p1)

        val candidate = observation.candidates.single { it.command == "patronize marco-polo" }
        assertThat(candidate.cashCost).isEqualTo(5)
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun exposedLeaderIncludesAffordableProtectionAlternatives() {
        val card =
            CardData.all.first {
                it.kind == CardKind.LEADER && it.id !in setOf("charlemagne", "marco-polo")
            }
        val state =
            fixture().copy(stage = Stage.PLAY_CARDS, effects = leaders()).withHand(p1, card.id)

        val observation = BotObservations.from(state, p1)

        assertThat(observation.candidates.map { it.command })
            .contains("play held-${card.id} protect 10")
        assertCandidatesLegal(state, observation)
    }

    @Test
    fun eventMarkerReuseUsesEveryProjectedColouredSource() {
        val state =
            fixture()
                .withDecision(
                    Stage.PLAY_CARDS,
                    CardDecision.MarkerPlacement(
                        listOf(ProvinceId("acre")),
                        MarkerPlacementAftermath.Crusades(p2),
                    ),
                )

        val observation = BotObservations.from(state, p2)

        assertThat(observation.candidates.map { it.command })
            .containsAtLeast(
                "forgo-marker",
                "move-marker rome",
                "move-marker florence",
            )
        assertCandidatesLegal(state, observation)
    }

    private fun leaders(): CardTurnEffects =
        CardTurnEffects(
            leaders =
                listOf(
                    CardLeaderInPlay(LeaderId.CHARLEMAGNE, p2, CardLeaderRole.DISPLAY),
                    CardLeaderInPlay(
                        LeaderId.MARCO_POLO,
                        p2,
                        CardLeaderRole.EXPOSED,
                        protectionCash = 5,
                    ),
                )
        )

    private fun assertCandidatesLegal(state: GameState, observation: BotObservation) {
        val seat = PlayerId(observation.playerId)
        val ordinary =
            BotObservations.from(
                state.viewForPlayer(seat),
                state.legalChoices(seat),
                seat,
                observation.decision,
            )
        assertThat(observation).isEqualTo(ordinary)
        for (candidate in observation.candidates) {
            try {
                state.step(
                    Entry.Action(
                        PlayerId(observation.playerId),
                        TextCodec.parseCommand(candidate.command),
                    )
                )
            } catch (failure: Exception) {
                throw AssertionError(
                    "Rejected ${candidate.command} at ${observation.decision}",
                    failure,
                )
            }
        }
    }

    private fun fixture(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals)
                .fold(BoardState()) { current, (id, capital) ->
                    current.placeMarker(ProvinceId(capital), id).board
                }
                .placeMarker(ProvinceId("rome"), p2)
                .board
                .placeMarker(ProvinceId("florence"), p2)
                .board
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 1000,
                                poolTokens = 15,
                                stockTokens = 21,
                            )
                    }
                    .toMap(),
            board = board,
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }

    private fun GameState.withDecision(stage: Stage, decision: TurnDecision): GameState =
        copy(stage = stage, flow = flow.copy(decisions = listOf(decision)))

    private fun GameState.withHand(seat: PlayerId, vararg cards: String): GameState {
        val owner = requireNotNull(players[seat]) { "A hand fixture requires its owner." }
        return copy(
            players =
                players +
                    (seat to
                        owner.copy(
                            hand =
                                cards.map { card ->
                                    HandCard("held-$card", CardData.definition(card))
                                }
                        ))
        )
    }

    private fun GameState.withAdvances(vararg advances: AdvanceId): GameState {
        val owner = requireNotNull(players[p1]) { "An advance fixture requires its owner." }
        return copy(
            players =
                players +
                    (p1 to
                        owner.copy(
                            advances = PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) })
                        ))
        )
    }
}
