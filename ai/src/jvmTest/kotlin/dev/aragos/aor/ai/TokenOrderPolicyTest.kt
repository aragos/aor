package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import kotlin.test.Test

class TokenOrderPolicyTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val own = players.first().id

    @Test
    fun versionTwoSeparatesCapitalPriorityWithoutChangingLegacyEncoding() {
        val state = fixture()
        val changed =
            state.copy(
                capitalSelection =
                    CapitalSelection.fromBids(
                        players.mapIndexed { index, player -> player.id to index }.toMap()
                    )
            )

        val original = encoded(state)
        val reversed = encoded(changed)

        assertThat(original.stateFeatures["role.0.capitalChoicePosition"]).isEqualTo(1 / 6.0)
        assertThat(reversed.stateFeatures["role.0.capitalChoicePosition"]).isEqualTo(4 / 6.0)
        assertThat(encoded(state, encoder = TerritoryEncoder.TOKEN_1))
            .isEqualTo(encoded(changed, encoder = TerritoryEncoder.TOKEN_1))
        assertThat(original.actions).isEqualTo(reversed.actions)
    }

    @Test
    fun versionTwoRetainsEveryVersionOneValueAndAddsOnlyItsDeclaredColumns() {
        val state = fixture()
        val old = encoded(state, encoder = TerritoryEncoder.TOKEN_1)
        val current = encoded(state)

        assertThat(current.stateFeatures - TokenObservationEncoder.capitalChoiceNames.toSet())
            .isEqualTo(old.stateFeatures)
        assertThat(
                current.actions.map {
                    it.copy(features = it.features - TokenObservationEncoder.bidAmountNames.toSet())
                }
            )
            .isEqualTo(old.actions)
        assertThat(current.baselineIndex).isEqualTo(old.baselineIndex)
    }

    @Test
    fun categoricalBidsDistinguishNeighborsAndDistantEndpoints() {
        val actions = encoded(fixture()).actions.associateBy(TerritoryAction::command)

        assertThat(actions["buy-tokens 15"]?.features).containsEntry("bid.amount.15", 1.0)
        assertThat(actions["buy-tokens 16"]?.features).containsEntry("bid.amount.16", 1.0)
        assertThat(actions["buy-tokens -1000"]?.features).containsEntry("bid.amount.below", 1.0)
        assertThat(actions["buy-tokens 1000"]?.features).containsEntry("bid.amount.above", 1.0)
        assertThat(
                actions.values.all { action ->
                    action.features.keys.count { it.startsWith("bid.amount.") } == 1
                }
            )
            .isTrue()
    }

    @Test
    fun rivalSealedAmountsPrivateCardsAndDeckOrderCannotChangeVersionTwo() {
        val state = fixture()
        val other = players[1].id
        fun hidden(card: String, bid: Int) =
            state.copy(
                deck = listOf(card, "wine-3"),
                players =
                    state.players +
                        (other to
                            state
                                .playerState(other)
                                .copy(
                                    hand = listOf(HandCard("private", CardData.definition(card))),
                                    tokenPurchase = bid,
                                )),
            )

        assertThat(encoded(hidden("stone-1", -17))).isEqualTo(encoded(hidden("gold-3", 24)))
    }

    @Test
    fun proposalsStartWithActualBaselineThenItsNeighborsAndKeepLegacyChoices() {
        val state = fixture()
        val ordinary = BotObservations.from(state, own)
        val observation = encoded(state)

        val proposals =
            TokenOrderPolicy.proposedIndices(ordinary, observation).map {
                observation.actions[it].command
            }

        assertThat(proposals)
            .containsExactly(
                "buy-tokens 16",
                "buy-tokens 15",
                "buy-tokens 17",
                "buy-tokens 0",
                "buy-tokens -4",
                "buy-tokens 8",
                "buy-tokens 24",
                "buy-tokens 36",
            )
            .inOrder()
    }

    @Test
    fun proposalsDoNotInventAnUnavailableNeighborAtAFullCashEndpoint() {
        val state = fixture()
        val observation = encoded(state, baseline = "buy-tokens 1000")

        val proposals =
            TokenOrderPolicy.proposedIndices(BotObservations.from(state, own), observation).map {
                observation.actions[it].command
            }

        assertThat(proposals).doesNotContain("buy-tokens 999")
        assertThat(proposals).doesNotContain("buy-tokens 1001")
        assertThat(proposals.first()).isEqualTo("buy-tokens 1000")
    }

    @Test
    fun insufficientLearnedAdvantageRetainsTheExactBaseline() {
        val state = fixture()
        val observation = encoded(state)

        val command =
            TokenOrderPolicy.choose(
                BotObservations.from(state, own),
                observation,
                model(state, .019),
            )

        assertThat(command).isEqualTo("buy-tokens 16")
    }

    @Test
    fun learnedNeighborAboveTheFixedMarginCanChangeTheBid() {
        val state = fixture()
        val observation = encoded(state)

        val command =
            TokenOrderPolicy.choose(
                BotObservations.from(state, own),
                observation,
                model(state, .03),
            )

        assertThat(command).isEqualTo("buy-tokens 15")
    }

    @Test
    fun residualZeroHeadRetainsFixedReferenceWithoutRelearningIt() {
        val state = fixture()

        val command =
            TokenOrderPolicy.chooseResidual(
                BotObservations.from(state, own),
                encoded(state),
                model(state, 0.0),
            )

        assertThat(command).isEqualTo("buy-tokens 15")
    }

    @Test
    fun residualCanUndoFixedAdjustmentWhenOrdinaryBidBeatsItsReference() {
        val state = fixture()

        val command =
            TokenOrderPolicy.chooseResidual(
                BotObservations.from(state, own),
                encoded(state),
                model(state, -.03),
            )

        assertThat(command).isEqualTo("buy-tokens 16")
    }

    @Test
    fun residualSmallAdvantageStillRetainsFixedReference() {
        val state = fixture()

        val command =
            TokenOrderPolicy.chooseResidual(
                BotObservations.from(state, own),
                encoded(state),
                model(state, -.019),
            )

        assertThat(command).isEqualTo("buy-tokens 15")
    }

    @Test
    fun residualDoesNotRecenterProposalsAroundFifteen() {
        val state = fixture()
        val original = model(state, 1.0)
        val onlyFourteen =
            original.copy(
                actionWeights =
                    List(original.actionWeights.size) {
                        if (it == original.actionFeatureNames.indexOf("bid.amount.14")) 8.0 else 0.0
                    }
            )

        val command =
            TokenOrderPolicy.chooseResidual(
                BotObservations.from(state, own),
                encoded(state),
                onlyFourteen,
            )

        assertThat(command).isEqualTo("buy-tokens 15")
    }

    @Test
    fun residualRetainsSignedOrdinaryFallbackOutsideFixedTrigger() {
        val state = fixture()

        val command =
            TokenOrderPolicy.chooseResidual(
                BotObservations.from(state, own),
                encoded(state, baseline = "buy-tokens -4"),
                model(state, 0.0),
            )

        assertThat(command).isEqualTo("buy-tokens -4")
    }

    @Test
    fun referenceNeighborsIncludeFourteenAndKeepOrdinaryBaseline() {
        val state = fixture()
        val observation = encoded(state)

        val proposals =
            TokenOrderPolicy.referenceNeighborIndices(BotObservations.from(state, own), observation)

        assertThat(proposals.map { observation.actions[it].command })
            .containsExactly(
                "buy-tokens 16",
                "buy-tokens 15",
                "buy-tokens 14",
                "buy-tokens 0",
                "buy-tokens -4",
                "buy-tokens 8",
                "buy-tokens 24",
                "buy-tokens 36",
            )
            .inOrder()
        assertThat(observation.actions[observation.baselineIndex].command)
            .isEqualTo("buy-tokens 16")
    }

    @Test
    fun referenceNeighborsPreserveSignedOrdinaryProposalOrderOutsideTrigger() {
        val state = fixture()
        val ordinary = BotObservations.from(state, own)
        val observation = encoded(state, baseline = "buy-tokens -4")

        val proposals = TokenOrderPolicy.referenceNeighborIndices(ordinary, observation)

        assertThat(proposals).isEqualTo(TokenOrderPolicy.proposedIndices(ordinary, observation))
    }

    @Test
    fun referenceNeighborsPreserveFullCashEndpointWithoutInventingQuotes() {
        val state = fixture()
        val ordinary = BotObservations.from(state, own)
        val observation = encoded(state, baseline = "buy-tokens 1000")

        val proposals = TokenOrderPolicy.referenceNeighborIndices(ordinary, observation)

        assertThat(proposals).isEqualTo(TokenOrderPolicy.proposedIndices(ordinary, observation))
    }

    @Test
    fun unavailableReferenceNeighborIsOmittedRatherThanClipped() {
        val state = fixture()
        val full = encoded(state)
        val ordinary =
            BotObservations.from(state, own).let { observation ->
                observation.copy(
                    candidates = observation.candidates.filter { it.command != "buy-tokens 14" }
                )
            }
        val actions = full.actions.filter { it.command != "buy-tokens 14" }
        val observation =
            full.copy(
                actions = actions,
                baselineIndex = actions.indexOfFirst { it.command == "buy-tokens 16" },
            )

        val proposals = TokenOrderPolicy.referenceNeighborIndices(ordinary, observation)

        assertThat(proposals.map { observation.actions[it].command })
            .doesNotContain("buy-tokens 14")
        assertThat(proposals.map { observation.actions[it].command })
            .doesNotContain("buy-tokens 13")
    }

    @Test
    fun referenceNeighborZeroHeadRetainsFixedTExactly() {
        val state = fixture()

        val command =
            TokenOrderPolicy.chooseReferenceNeighbors(
                BotObservations.from(state, own),
                encoded(state),
                model(state, 0.0),
            )

        assertThat(command).isEqualTo("buy-tokens 15")
    }

    @Test
    fun referenceNeighborModelCanSelectOfferedFourteen() {
        val state = fixture()
        val original = model(state, 1.0)
        val onlyFourteen =
            original.copy(
                actionWeights =
                    List(original.actionWeights.size) {
                        if (it == original.actionFeatureNames.indexOf("bid.amount.14")) 8.0 else 0.0
                    }
            )

        val command =
            TokenOrderPolicy.chooseReferenceNeighbors(
                BotObservations.from(state, own),
                encoded(state),
                onlyFourteen,
            )

        assertThat(command).isEqualTo("buy-tokens 14")
    }

    private fun encoded(
        state: GameState,
        baseline: String = "buy-tokens 16",
        encoder: TerritoryEncoder = TerritoryEncoder.TOKEN_2,
    ) =
        TokenObservationEncoder.from(
            state.viewForDecision(own),
            own,
            BotObservations.from(state, own),
            baseline,
            encoder,
        )

    private fun model(state: GameState, weight: Double): TerritoryValueModel {
        val dictionary =
            TokenObservationEncoder.dictionary(
                requireNotNull(state.viewForDecision(own).catalog) {
                    "The token fixture has a catalog."
                },
                TerritoryEncoder.TOKEN_2,
            )
        return TerritoryValueModel(
            encoderVersion = TerritoryEncoder.TOKEN_2.version,
            stateFeatureNames = dictionary.stateFeatureNames,
            actionFeatureNames = dictionary.actionFeatureNames,
            stateWeights = List(64 * dictionary.stateFeatureNames.size) { 0.0 },
            stateBias = List(64) { 0.0 },
            actionWeights =
                List(64 * dictionary.actionFeatureNames.size) {
                    if (it == dictionary.actionFeatureNames.indexOf("bid.amount.15")) 8.0 else 0.0
                },
            actionBias = List(64) { 0.0 },
            outputWeights = List(64) { if (it == 0) weight else 0.0 },
            outputBias = .5,
        )
    }

    private fun fixture(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        return GameState(
            header = GameHeader(players, own),
            stage = Stage.TOKEN_PURCHASE,
            turnNumber = 1,
            capitalSelection =
                CapitalSelection.fromBids(
                    players.mapIndexed { index, player -> player.id to 4 - index }.toMap()
                ),
            board =
                players.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                    board.placeMarker(ProvinceId(capital), player.id).board
                },
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capitalBid = 0,
                                capital = capitals[index],
                                cash = 1000,
                            )
                    }
                    .toMap(),
        )
    }
}
