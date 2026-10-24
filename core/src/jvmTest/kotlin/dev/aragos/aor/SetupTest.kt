package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SetupTest {
    @Test
    fun creatorRoleDoesNotDependOnRegistrationOrder() {
        val state = GameState(header().copy(creatorId = PlayerId("p2"))).play("p2: start")

        assertThat(state.stage).isEqualTo(Stage.SHUFFLE)
    }

    @Test
    fun configurationPromptsAddressTheExplicitCreator() {
        val state = GameState(header().copy(creatorId = PlayerId("p2")))

        assertThat(state.prompts())
            .containsExactly(Prompt.SinglePlayer(player = PlayerId("p2"), choice = "configure"))
    }

    @Test
    fun publicCashComesFromThePlayerState() {
        val state =
            GameState(header()).updatePlayer(PlayerId("p1")) { player ->
                player.copy(cash = 17)
            }

        assertThat(state.viewForPlayer(PlayerId("p1")).players.first().cash).isEqualTo(17)
    }

    @Test
    fun stockMarkersAreDerivedAfterCopyingAPlayerView() {
        val view = dealt().viewForPlayer(PlayerId("p1")).players.first().copy(boardMarkers = 4)

        assertThat(view.stockMarkers).isEqualTo(21)
    }

    @Test
    fun newGamesHaveEmptyHandsForEveryPlayer() {
        for (count in 3..6) {
            val state = GameState(header(count))

            assertThat(state.players.keys).containsExactlyElementsIn(state.playerIds).inOrder()
            state.players.values.forEach { player -> assertThat(player.hand).isEmpty() }
        }
    }

    @Test
    fun dealingGivesEveryPlayerOneUniqueCard() {
        for (count in 3..6) {
            val state = dealt(count)
            val cards = state.players.values.flatMap(PlayerState::hand)

            assertThat(state.stage).isEqualTo(Stage.BIDDING)
            assertThat(state.deck).hasSize(29 - count)
            assertThat(cards.map { card -> card.definition.id }).containsNoDuplicates()
            assertThat(cards.map(HandCard::reference)).containsNoDuplicates()
            state.players.values.forEach { player -> assertThat(player.hand).hasSize(1) }
        }
    }

    @Test
    fun playerCountDeterminesAvailableCapitals() {
        for (count in 3..6) {
            assertThat(dealt(count).availableCapitals)
                .containsExactlyElementsIn(CapitalData.availableFor(count))
                .inOrder()
        }
    }

    @Test
    fun choosingEveryCapitalBeginsTheFirstTurn() {
        for (count in 3..6) {
            val bidding = dealt(count)
            val choosing =
                bidding.playerIds.foldIndexed(bidding) { index, state, player ->
                    state.play("$player: bid-capital ${count - index}")
                }
            val complete =
                choosing.capitalOrder.fold(choosing) { state, player ->
                    state.play("$player: choose-capital ${state.availableCapitals.first()}")
                }

            assertThat(complete.stage).isEqualTo(Stage.TOKEN_PURCHASE)
            assertThat(complete.turnNumber).isEqualTo(1)
            assertThat(complete.prompts())
                .containsExactly(
                    Prompt.MultiPlayer(players = complete.playerIds, choice = "buy-tokens")
                )
            assertThat(complete.players.values.map(PlayerState::capital)).hasSize(count)
            assertThat(complete.players.values.map(PlayerState::capital)).containsNoDuplicates()
            assertThat(complete.availableCapitals).isEmpty()
        }
    }

    @Test
    fun selectingCapitalsInitializesStartingResources() {
        val state =
            dealt()
                .play(
                    "p1: bid-capital 12",
                    "p2: bid-capital 5",
                    "p3: bid-capital 0",
                    "p1: choose-capital venice",
                    "p2: choose-capital genoa",
                    "p3: choose-capital barcelona",
                )

        state.viewForPlayer(PlayerId("p1")).players.forEach { player ->
            assertThat(player.stockTokens).isEqualTo(36)
            assertThat(player.poolTokens).isEqualTo(0)
            assertThat(player.stockMarkers).isEqualTo(24)
            assertThat(player.boardMarkers).isEqualTo(1)
            assertThat(player.misery).isEqualTo(MiseryStep.ZERO)
            assertThat(player.ships).isEqualTo(ShippingCapability.NONE)
        }
    }

    @Test
    fun everyPlayerPaysTheirCapitalBid() {
        val state =
            dealt()
                .play(
                    "p1: bid-capital 40",
                    "p2: bid-capital 12",
                    "p3: bid-capital 0",
                )

        assertThat(state.viewForPlayer(PlayerId("p1")).players.map(PlayerView::cash))
            .containsExactly(0, 28, 40)
            .inOrder()
    }

    @Test
    fun capitalBidsDetermineDescendingChoiceOrder() {
        val state =
            dealt()
                .play(
                    "p1: bid-capital 2",
                    "p2: bid-capital 40",
                    "p3: bid-capital 12",
                )

        assertThat(state.capitalOrder.map(PlayerId::value))
            .containsExactly("p2", "p3", "p1")
            .inOrder()
    }

    @Test
    fun incompleteBidBatchHasNoVisibleEffects() {
        val initial = dealt()
        val bidding = initial.play("p1: bid-capital 12", "p2: bid-capital 5")

        assertThat(bidding.viewForPlayer(PlayerId("p3")))
            .isEqualTo(initial.viewForPlayer(PlayerId("p3")))
    }

    @Test
    fun completeBidBatchRevealsEveryBidTogether() {
        val state =
            dealt()
                .play(
                    "p1: bid-capital 12",
                    "p2: bid-capital 5",
                    "p3: bid-capital 0",
                )

        assertThat(state.viewForPlayer(PlayerId("p1")).revealedBids)
            .containsExactly(PlayerId("p1"), 12, PlayerId("p2"), 5, PlayerId("p3"), 0)
    }

    @Test
    fun tieRollRequestsOnlyRemainingTiedPlayers() {
        val state =
            dealt(4)
                .play(
                    "p1: bid-capital 12",
                    "p2: bid-capital 12",
                    "p3: bid-capital 12",
                    "p4: bid-capital 0",
                    "!roll capital-tie p1=6 p2=2 p3=2",
                )

        assertThat(state.capitalSelection.ties.map(CapitalTie::players))
            .containsExactly(listOf(PlayerId("p2"), PlayerId("p3")))
        assertThat(state.prompts())
            .containsExactly(
                Prompt.System(
                    SystemNeed.GameRoll(
                        GameRollKind.CAPITAL_TIE,
                        2,
                        listOf(PlayerId("p2"), PlayerId("p3")),
                    )
                )
            )
    }

    @Test
    fun resolvingRepeatedTiesPreservesAlreadyResolvedPositions() {
        val state =
            dealt(4)
                .play(
                    "p1: bid-capital 12",
                    "p2: bid-capital 12",
                    "p3: bid-capital 12",
                    "p4: bid-capital 0",
                    "!roll capital-tie p1=6 p2=2 p3=2",
                    "!roll capital-tie p2=4 p3=4",
                    "!roll capital-tie p2=1 p3=4",
                )

        assertThat(state.capitalOrder.map(PlayerId::value))
            .containsExactly("p1", "p3", "p2", "p4")
            .inOrder()
        assertThat(state.stage).isEqualTo(Stage.CAPITAL_CHOICE)
    }

    @Test
    fun privateViewContainsOnlyTheRequestingPlayersCard() {
        val view = dealt().viewForPlayer(PlayerId("p3"))

        assertThat(view.hand.map { card -> card.definition.id }).containsExactly("wool-1")
        assertThat(view.hand.map(HandCard::reference)).containsExactly("hand-3")
        assertThat(view.players.map(PlayerView::handCount)).containsExactly(1, 1, 1)
    }

    @Test
    fun onlyTheCreatorCanStart() {
        violation(ViolationCode.WRONG_PLAYER) { GameState(header()).play("p2: start") }
    }

    @Test
    fun removedEpochOptionIsUnsupportedForEveryValue() {
        for (epochs in listOf(0, 1, 2, 3, 4)) {
            violation(ViolationCode.UNSUPPORTED_OPTION) {
                GameState(header()).play("p1: set-option epochs $epochs")
            }
        }
    }

    @Test
    fun gamesAlwaysBeginInTheFirstOfThreeEpochs() {
        val state = GameState(header()).play("p1: start")

        assertThat(state.currentEpoch).isEqualTo(Epoch.ONE)
        assertThat(Epoch.entries.map(Epoch::number)).containsExactly(1, 2, 3).inOrder()
        assertThat(state.legalChoices(PlayerId("p1")).commands)
            .doesNotContain("set-option epochs 1")
    }

    @Test
    fun bidsMustBeBetweenZeroAndForty() {
        val initial = dealt()

        for (bid in listOf(-1, 41)) {
            violation(ViolationCode.INVALID_BID) { initial.play("p1: bid-capital $bid") }
        }
        assertThat(initial.players.values.map(PlayerState::capitalBid))
            .containsExactly(null, null, null)
    }

    @Test
    fun configurationIsFrozenAfterStarting() {
        violation(ViolationCode.WRONG_PHASE) {
            dealt().play("p1: set-option rebuilt-marker-bonus include")
        }
    }

    @Test
    fun unknownPlayersCannotBid() {
        violation(ViolationCode.UNKNOWN_PLAYER) { dealt().play("p6: bid-capital 1") }
    }

    @Test
    fun capitalChoicesMustFollowTheResolvedOrder() {
        val choosing =
            dealt()
                .play(
                    "p1: bid-capital 40",
                    "p2: bid-capital 1",
                    "p3: bid-capital 0",
                )

        violation(ViolationCode.WRONG_PLAYER) { choosing.play("p2: choose-capital venice") }
    }

    @Test
    fun playersCannotChooseACapitalUnavailableAtTheirPlayerCount() {
        val choosing =
            dealt()
                .play(
                    "p1: bid-capital 40",
                    "p2: bid-capital 1",
                    "p3: bid-capital 0",
                )

        violation(ViolationCode.UNAVAILABLE_CAPITAL) { choosing.play("p1: choose-capital london") }
        assertThat(choosing.players.values.map(PlayerState::capital))
            .containsExactly(null, null, null)
    }

    @Test
    fun playersCannotChooseAnAlreadySelectedCapital() {
        val choosing =
            dealt()
                .play(
                    "p1: bid-capital 40",
                    "p2: bid-capital 1",
                    "p3: bid-capital 0",
                    "p1: choose-capital venice",
                )

        violation(ViolationCode.UNAVAILABLE_CAPITAL) { choosing.play("p2: choose-capital venice") }
    }

    @Test
    fun shuffleMustContainEveryCardExactlyOnce() {
        val started = GameState(header()).play("p1: start")

        violation(ViolationCode.INVALID_OUTCOME) {
            started.play("!shuffle ${List(29) { "stone-1" }.joinToString(" ")}")
        }
    }

    @Test
    fun dealingMustFollowRegistrationOrder() {
        val dealing = GameState(header()).play("p1: start", shuffleText())

        violation(ViolationCode.INVALID_OUTCOME) { dealing.play("!draw p2 hand-2 stone-1") }
    }

    @Test
    fun dealingMustTakeTheTopCard() {
        val dealing = GameState(header()).play("p1: start", shuffleText())

        violation(ViolationCode.INVALID_OUTCOME) { dealing.play("!draw p1 hand-1 wool-1") }
    }

    @Test
    fun setupDrawMustUseTheRequestedHandReference() {
        val dealing = GameState(header()).play("p1: start", shuffleText())

        violation(ViolationCode.INVALID_OUTCOME) { dealing.play("!draw p1 hand-2 stone-1") }
    }

    @Test
    fun capitalRollAttributionDoesNotDependOnRecordedPlayerOrder() {
        val state =
            dealt()
                .play(
                    "p1: bid-capital 0",
                    "p2: bid-capital 0",
                    "p3: bid-capital 0",
                    "!roll capital-tie p2=1 p3=4 p1=6",
                )

        assertThat(state.capitalOrder.map(PlayerId::value))
            .containsExactly("p1", "p3", "p2")
            .inOrder()
    }

    @Test
    fun capitalRollRequiresPlayerAttribution() {
        val tied =
            dealt()
                .play(
                    "p1: bid-capital 0",
                    "p2: bid-capital 0",
                    "p3: bid-capital 0",
                )

        violation(ViolationCode.INVALID_OUTCOME) { tied.play("!roll capital-tie 6 4 1") }
    }

    @Test
    fun tieRollMustIncludeEveryTiedPlayer() {
        val tied =
            dealt()
                .play(
                    "p1: bid-capital 0",
                    "p2: bid-capital 0",
                    "p3: bid-capital 0",
                )

        violation(ViolationCode.INVALID_OUTCOME) { tied.play("!roll capital-tie p1=1 p2=2") }
    }

    @Test
    fun tieRollsMustUseSixSidedDice() {
        val tied =
            dealt()
                .play(
                    "p1: bid-capital 0",
                    "p2: bid-capital 0",
                    "p3: bid-capital 0",
                )

        violation(ViolationCode.INVALID_OUTCOME) {
            tied.play("!roll capital-tie p1=0 p2=2 p3=7")
        }
    }

    private fun header(count: Int = 3) =
        GameHeader(
            creatorId = PlayerId("p1"),
            players =
                (1..count).map { number ->
                    Player(id = PlayerId("p$number"), name = "Player $number")
                },
        )

    private fun dealt(count: Int = 3): GameState =
        (1..count).fold(GameState(header(count)).play("p1: start", shuffleText())) { state, number
            ->
            state.play("!draw p$number hand-$number ${state.deck.first()}")
        }

    private fun shuffleText() =
        "!shuffle ${CardData.epochOne.joinToString(" ", transform = CardDefinition::id)}"

    private fun GameState.play(vararg entries: String): GameState =
        entries.fold(this) { state, text ->
            state.step(TextCodec.parse(text))
        }

    private fun violation(code: ViolationCode, action: () -> Unit) {
        assertThat(assertFailsWith<RuleViolation>(block = action).code).isEqualTo(code)
    }
}
