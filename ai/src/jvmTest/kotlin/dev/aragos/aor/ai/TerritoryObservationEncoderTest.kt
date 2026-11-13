package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class TerritoryObservationEncoderTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val own = players.first().id

    @Test
    fun highCashRetainsEverySignedQuoteIncludingDistantCashEndpoints() {
        val state = fixture(Stage.TOKEN_PURCHASE)
        val view = state.viewForPlayer(own)

        val encoded = token(state)

        assertThat(encoded.actions.map(TerritoryAction::command))
            .containsExactlyElementsIn(
                view.tokenPurchaseOffers.map { "buy-tokens ${it.amount}" }.sorted()
            )
            .inOrder()
        assertThat(encoded.actions.size).isEqualTo(75)
        assertThat(encoded.actions.map(TerritoryAction::command))
            .containsAtLeast("buy-tokens -1000", "buy-tokens 1000", "buy-tokens 0")
        val negative = encoded.actions.single { it.command == "buy-tokens -10" }.features
        assertThat(negative["amount"]).isEqualTo(-10 / 36.0)
        assertThat(negative["cost"]).isEqualTo(.05)
        assertThat(negative["received"] ?: 0.0).isEqualTo(0.0)
        assertThat(encoded.actions.all { it.features.values.all { value -> value in -16.0..16.0 } })
            .isTrue()
    }

    @Test
    fun rivalPrivateCardsDeckOrderAndSealedAmountsAreAbsent() {
        val state = fixture(Stage.TOKEN_PURCHASE)
        val other = players[1].id
        fun changed(card: String, amount: Int) =
            state.copy(
                deck = listOf(card, "wine-3"),
                players =
                    state.players +
                        (other to
                            state
                                .playerState(other)
                                .copy(
                                    hand = listOf(HandCard("private", CardData.definition(card))),
                                    tokenPurchase = amount,
                                )),
            )

        assertThat(token(changed("stone-1", -17))).isEqualTo(token(changed("gold-3", 24)))
    }

    @Test
    fun highConnectivityRetainsAllExpansionCommandsWithinDeclaredVocabularyAndBounds() {
        val state = fixture(Stage.EXPANSION, AdvanceId.entries.toSet(), ShippingCapability.OCEAN_4)
        val offered = BotObservations.from(state, own)
        val view = state.viewForPlayer(own)

        val encoded = expansion(state)
        val dictionary =
            ExpansionObservationEncoder.dictionary(
                requireNotNull(view.catalog) { "The ordinary fixture includes the public catalog." }
            )

        assertThat(encoded.actions.map(TerritoryAction::command))
            .containsExactlyElementsIn(offered.candidates.map(BotCandidate::command).sorted())
            .inOrder()
        assertThat(encoded.actions.size).isGreaterThan(20)
        // Each province contributes at most its partial amounts plus one primary or reduction.
        val structuralMaximum =
            requireNotNull(view.catalog) { "The ordinary fixture includes the public catalog." }
                .provinces
                .sumOf { maxOf(it.marketNumber, 2) } + 2
        assertThat(structuralMaximum).isAtMost(TerritoryPhase.EXPANSION.maxActions)
        assertThat(encoded.actions.size).isAtMost(structuralMaximum)
        assertThat(dictionary.stateFeatureNames.size).isAtMost(8192)
        assertThat(dictionary.actionFeatureNames.size).isAtMost(512)
        assertThat(dictionary.stateFeatureNames)
            .containsAtLeastElementsIn(encoded.stateFeatures.keys)
        assertThat(dictionary.actionFeatureNames)
            .containsAtLeastElementsIn(encoded.actions.flatMap { it.features.keys }.toSet())
        val tokenDictionary =
            TokenObservationEncoder.dictionary(
                requireNotNull(view.catalog) { "The ordinary fixture includes the public catalog." }
            )
        assertThat(tokenDictionary.stateFeatureNames.size).isAtMost(8192)
    }

    @Test
    fun partialPlacementKeepsItsOwnEndpointWithoutBorrowingFullMarketIncome() {
        val state = fixture(Stage.EXPANSION)
        val quote =
            state.viewForPlayer(own).expansionOffers.first {
                it.partialPlacements.isNotEmpty() && it.blockers.isEmpty()
            }
        val partial = quote.partialPlacements.first()

        val action =
            expansion(state).actions.single {
                it.command == "place ${partial.tokens} ${quote.destination}"
            }

        assertThat(action.features["poolAfter"] ?: 0.0).isEqualTo(partial.resultingPool / 36.0)
        assertThat(action.features["partial"]).isEqualTo(1.0)
        assertThat(action.features).doesNotContainKey("incomeAfterKnown")
        assertThat(action.features).doesNotContainKey("shippingRequired")
    }

    @Test
    fun occupiedOverseasSlotRegeneratesTheActuallyOfferedSet() {
        val state =
            fixture(Stage.EXPANSION, setOf(AdvanceId.OCEAN_NAVIGATION), ShippingCapability.OCEAN_1)
        val quote =
            state.viewForPlayer(own).expansionOffers.single { it.destination.value == "china" }
        val before = expansion(state)

        val next = state.step(TextCodec.parse("p1: place ${quote.requiredTokens} china"))
        val after = expansion(next)

        assertThat(before.actions.any { it.command.endsWith(" east-indies") }).isTrue()
        assertThat(after.actions.any { it.command.endsWith(" east-indies") }).isFalse()
        assertThat(after.stateFeatures["role.0.pool"])
            .isEqualTo(next.playerState(own).poolTokens / 36.0)
    }

    @Test
    fun attackRetainsPublicUncertaintyRatherThanInventingAResult() {
        val initial = fixture(Stage.EXPANSION)
        val state =
            initial.copy(
                board = initial.board.placeMarker(ProvinceId("alexandria"), players[1].id).board
            )
        val quote =
            state.viewForPlayer(own).expansionOffers.single { it.destination.value == "alexandria" }

        val attack = expansion(state).actions.single { it.command == "attack alexandria" }

        assertThat(attack.features["uncertain"]).isEqualTo(1.0)
        assertThat(attack.features["winProbability"] ?: 0.0)
            .isEqualTo(quote.competitionWinProbability ?: 0.0)
        assertThat(attack.features.keys.none { it.contains("actual") || it.contains("winner") })
            .isTrue()
    }

    @Test
    fun passDoesNotClaimUnusedTokensRemainCommitted() {
        val action = expansion(fixture(Stage.EXPANSION)).actions.single { it.command == "pass" }

        assertThat(action.features).containsEntry("kind.pass", 1.0)
        assertThat(action.features).doesNotContainKey("poolAfter")
    }

    @Test
    fun unboundExpansionFactsExcludeRivalCardIdentityAndFutureDeckOrder() {
        val state = fixture(Stage.EXPANSION)
        val other = players[1].id
        fun changed(card: String, deck: List<String>) =
            state.copy(
                deck = deck,
                players =
                    state.players +
                        (other to
                            state
                                .playerState(other)
                                .copy(
                                    hand = listOf(HandCard("private", CardData.definition(card)))
                                )),
            )
        val first = changed("stone-1", listOf("wine-3", "gold-3"))
        val second = changed("gold-3", listOf("gold-3", "wine-3"))

        assertThat(projectedExpansion(first)).isEqualTo(projectedExpansion(second))
    }

    @Test
    fun unboundExpansionFactsKeepEveryActuallyOfferedCommand() {
        val state = fixture(Stage.EXPANSION, AdvanceId.entries.toSet(), ShippingCapability.OCEAN_4)
        val offered = BotObservations.from(state, own)

        val projection = projectedExpansion(state)

        assertThat(projection.encoderVersion).isEqualTo(TerritoryEncoder.EXPANSION_2.version)
        assertThat(projection.actions.map(TerritoryAction::command))
            .containsExactlyElementsIn(offered.candidates.map(BotCandidate::command).sorted())
            .inOrder()
    }

    private fun projectedExpansion(state: GameState) =
        ExpansionObservationEncoder.project(
            state.viewForPlayer(own),
            own,
            BotObservations.from(state, own),
            TerritoryEncoder.EXPANSION_2,
        )

    private fun token(state: GameState) =
        TokenObservationEncoder.from(
            state.viewForPlayer(own),
            own,
            BotObservations.from(state, own),
            "buy-tokens 0",
        )

    private fun expansion(state: GameState) =
        ExpansionObservationEncoder.from(
            state.viewForPlayer(own),
            own,
            BotObservations.from(state, own),
            "pass",
        )

    private fun fixture(
        stage: Stage,
        advances: Set<AdvanceId> = emptySet(),
        ships: ShippingCapability = ShippingCapability.SEAWORTHY_10,
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        return GameState(
            header = GameHeader(players, own),
            stage = stage,
            currentEpoch = Epoch.THREE,
            turnNumber = 2,
            board =
                players.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                    board.placeMarker(ProvinceId(capital), player.id).board
                },
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = 1000,
                                stockTokens =
                                    if (stage == Stage.EXPANSION && player.id == own) 0 else 36,
                                poolTokens =
                                    if (stage == Stage.EXPANSION && player.id == own) 36 else 0,
                                ships = if (player.id == own) ships else ShippingCapability.NONE,
                                advances =
                                    if (player.id == own)
                                        PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = if (stage == Stage.EXPANSION) own else null,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }
}
