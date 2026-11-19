package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Synthetic public views only: no neural inference, game-chance stream or complete game. */
class RevealedTokenCounterTest {
    @Test
    fun theCounterTargetsTheLearnedOpponentBidInsteadOfItsOwnBaseline() {
        val counter = learned()

        val result = bid(counter)

        assertThat(result.command).isEqualTo("buy-tokens 20")
    }

    @Test
    fun insufficientPublicObservationsPreserveTheOriginalPolicy() {
        val counter = counter()
        counter.observe(PublicTokenReveal(1, 21))

        assertThat(bid(counter).command).isEqualTo("buy-tokens 8")
    }

    @Test
    fun anExplicitOpeningPriorCanCounterBeforeAnyInGameReveal() {
        val counter = counter(RevealedTokenCounterConfig(2, 8, 1, openingBidPrior = 21))

        assertThat(bid(counter).command).isEqualTo("buy-tokens 20")
    }

    @Test
    fun theLowerQuantileAnticipatesARepeatedLowBidEvenWhenTheModeIsHigher() {
        val counter = counter(RevealedTokenCounterConfig(2, 8, 1, forecastQuantile = 0.25))
        counter.observe(PublicTokenReveal(1, 19))
        counter.observe(PublicTokenReveal(2, 21))
        counter.observe(PublicTokenReveal(3, 21))

        assertThat(bid(counter, turn = 4).command).isEqualTo("buy-tokens 18")
    }

    @Test
    fun repeatedViewsOfOneRevealDoNotSatisfyTheObservationMinimum() {
        val counter = counter()
        repeat(10) { counter.observe(PublicTokenReveal(1, 21)) }

        assertThat(bid(counter).reason).isEqualTo(CounterBidReason.WARMUP)
    }

    @Test
    fun theCommitmentWindowCannotSupplyACurrentBidToHistory() {
        val view = view(Stage.TOKEN_PURCHASE, 3).copy(revealedTokenPurchases = mapOf(target to 99))

        assertThat(PublicTokenReveal.from(view, target)).isNull()
    }

    @Test
    fun aCurrentPublicRevealIsNeverUsedToPredictThatSameTurn() {
        val counter = counter()
        counter.observe(PublicTokenReveal(1, 21))
        counter.observe(PublicTokenReveal(3, 99))

        assertThat(bid(counter).reason).isEqualTo(CounterBidReason.WARMUP)
    }

    @Test
    fun modalTiesPreferTheMostRecentRevealedAmount() {
        val counter = counter()
        counter.observe(PublicTokenReveal(1, 21))
        counter.observe(PublicTokenReveal(2, 19))

        assertThat(bid(counter).command).isEqualTo("buy-tokens 18")
    }

    @Test
    fun oldObservationsLeaveTheBoundedPredictionWindow() {
        val counter = counter(RevealedTokenCounterConfig(2, 2, 1))
        counter.observe(PublicTokenReveal(1, 21))
        counter.observe(PublicTokenReveal(2, 19))
        counter.observe(PublicTokenReveal(3, 19))

        assertThat(bid(counter, turn = 4).command).isEqualTo("buy-tokens 18")
    }

    @Test
    fun aNewGameHasNoLearnedHistory() {
        learned()

        assertThat(bid(counter()).reason).isEqualTo(CounterBidReason.WARMUP)
    }

    @Test
    fun anUnofferedTargetDoesNotJumpToAnExpensiveFullCashBid() {
        val counter = learned()

        val result = bid(counter, offered = listOf("buy-tokens 8", "buy-tokens 100"))

        assertThat(result.command).isEqualTo("buy-tokens 8")
        assertThat(result.reason).isEqualTo(CounterBidReason.TARGET_NOT_OFFERED)
    }

    @Test
    fun aStrictOverbidTargetsOneAboveTheOpponentsForecast() {
        assertThat(bid(learned(), style = RobustOpponentStyle.REVEALED_OVERBID).command)
            .isEqualTo("buy-tokens 22")
    }

    @Test
    fun laterCapitalPriorityCanBuyTheExactTieInTheTieAwareVariant() {
        val result = bid(learned(), style = RobustOpponentStyle.REVEALED_TIE_UNDERCUT)

        assertThat(result.command).isEqualTo("buy-tokens 21")
        assertThat(result.reason).isEqualTo(CounterBidReason.CAPITAL_PRIORITY_TIE)
    }

    @Test
    fun earlierCapitalPriorityStillRequiresAStrictUndercut() {
        val result =
            learned()
                .bid(
                    "buy-tokens 8",
                    offers,
                    3,
                    rival,
                    listOf(rival, target, players[2], players[3]),
                    RobustOpponentStyle.REVEALED_TIE_UNDERCUT,
                )

        assertThat(result.command).isEqualTo("buy-tokens 20")
    }

    @Test
    fun anUnofferedPriorityTieFallsBackToTheExactOfferedNeighbor() {
        val result =
            bid(
                learned(),
                offered = listOf("buy-tokens 8", "buy-tokens 20"),
                style = RobustOpponentStyle.REVEALED_TIE_UNDERCUT,
            )

        assertThat(result.command).isEqualTo("buy-tokens 20")
        assertThat(result.reason).isEqualTo(CounterBidReason.STRICT_NEIGHBOR)
    }

    @Test
    fun aTieAwareCounterInsertsBeforeThePubliclyPlacedTarget() {
        assertThat(
                learned()
                    .tie(
                        "choose-turn-position 2",
                        tieOffers,
                        3,
                        rival,
                        listOf(target),
                        RobustOpponentStyle.REVEALED_TIE_UNDERCUT,
                    )
            )
            .isEqualTo("choose-turn-position 1")
    }

    @Test
    fun aTieAwareOverbidInsertsAfterThePubliclyPlacedTarget() {
        assertThat(
                learned()
                    .tie(
                        "choose-turn-position 1",
                        tieOffers,
                        3,
                        rival,
                        listOf(target),
                        RobustOpponentStyle.REVEALED_TIE_OVERBID,
                    )
            )
            .isEqualTo("choose-turn-position 2")
    }

    @Test
    fun theSamePublicRevealCannotChangeAfterItWasObserved() {
        val counter = counter()
        counter.observe(PublicTokenReveal(1, 21))

        assertFailsWith<IllegalArgumentException> { counter.observe(PublicTokenReveal(1, 22)) }
    }

    @Test
    fun oneTargetedRivalLeavesTheOtherTwoQualified() {
        val league = league()

        assertThat(players.filter(league::isTargeted)).containsExactly(rival)
    }

    @Test
    fun theExplicitStressEcologyCanTargetAllThreeRivals() {
        val league = league(RevealedTokenCounterConfig(2, 4, 3))

        assertThat(players.filter(league::isTargeted)).containsExactlyElementsIn(players.drop(1))
    }

    @Test
    fun targetedRivalSelectionFollowsRegistrationCyclicallyFromTheFixtureTarget() {
        val league =
            RevealedTokenCounterLeague(players[2], players, RevealedTokenCounterConfig(2, 4, 1))

        assertThat(players.filter(league::isTargeted)).containsExactly(players[3])
    }

    @Test
    fun aLearnedTieCounterOwnsTiePlacementEvenWhenTheBaseDoesNotControlIt() {
        val league = league()
        league.observe(view(Stage.PLAY_CARDS, 1))
        league.observe(view(Stage.PLAY_CARDS, 2))
        val controller = league.wrap(rival, RobustOpponentStyle.REVEALED_TIE_UNDERCUT, tokenOnly())
        controller.choose(decision(rival, view(Stage.TOKEN_PURCHASE, 3)))

        val result =
            controller.choose(
                decision(
                    rival,
                    view(Stage.TURN_ORDER_TIE, 3).copy(provisionalTieOrder = listOf(target)),
                    "choose-turn-position",
                    tieOffers,
                )
            )

        assertThat(result).isEqualTo("choose-turn-position 1")
    }

    @Test
    fun warmupRetainsTheUnsupportedTieFallback() {
        val controller =
            league().wrap(rival, RobustOpponentStyle.REVEALED_TIE_UNDERCUT, tokenOnly())
        controller.choose(decision(rival, view(Stage.TOKEN_PURCHASE, 1)))

        val result =
            controller.choose(
                decision(
                    rival,
                    view(Stage.TURN_ORDER_TIE, 1).copy(provisionalTieOrder = listOf(target)),
                    "choose-turn-position",
                    tieOffers,
                )
            )

        assertThat(result).isNull()
    }

    @Test
    fun forkedTailsLearnIndependentlyFromTheSamePublicPrefix() {
        val prefix = league(RevealedTokenCounterConfig(2, 2, 1))
        prefix.observe(view(Stage.PLAY_CARDS, 1))
        prefix.observe(view(Stage.PLAY_CARDS, 2))
        val left = prefix.fork()
        val right = prefix.fork()
        left.observe(view(Stage.PLAY_CARDS, 3).copy(revealedTokenPurchases = mapOf(target to 10)))
        right.observe(view(Stage.PLAY_CARDS, 3).copy(revealedTokenPurchases = mapOf(target to 30)))
        fun command(league: RevealedTokenCounterLeague): String? =
            league
                .wrap(rival, RobustOpponentStyle.REVEALED_UNDERCUT, tokenOnly())
                .choose(decision(rival, view(Stage.TOKEN_PURCHASE, 4)))

        assertThat(command(left)).isEqualTo("buy-tokens 9")
        assertThat(command(right)).isEqualTo("buy-tokens 29")
        assertThat(command(prefix)).isEqualTo("buy-tokens 20")
    }

    @Test
    fun aForkAfterSealedRootCommandsRetainsTheRivalsTieIntent() {
        val prefix = league()
        prefix.observe(view(Stage.PLAY_CARDS, 1))
        prefix.observe(view(Stage.PLAY_CARDS, 2))
        prefix
            .wrap(rival, RobustOpponentStyle.REVEALED_TIE_UNDERCUT, tokenOnly())
            .choose(decision(rival, view(Stage.TOKEN_PURCHASE, 3)))
        val tail = prefix.fork().wrap(rival, RobustOpponentStyle.REVEALED_TIE_UNDERCUT, tokenOnly())

        val command =
            tail.choose(
                decision(
                    rival,
                    view(Stage.TURN_ORDER_TIE, 3).copy(provisionalTieOrder = listOf(target)),
                    "choose-turn-position",
                    tieOffers,
                )
            )

        assertThat(command).isEqualTo("choose-turn-position 1")
    }

    @Test
    fun controlledSeatCurrentCommandsCannotTeachTheCounterInsideTheSealedBatch() {
        assertThat(counterAfterCurrentTargetCommand("buy-tokens 17"))
            .isEqualTo(counterAfterCurrentTargetCommand("buy-tokens 35"))
        assertThat(counterAfterCurrentTargetCommand("buy-tokens 17")).isEqualTo("buy-tokens 20")
    }

    @Test
    fun diagnosticProofRetentionIsBoundedWhileAllDecisionsAreCounted() {
        val counter = learned()
        repeat(100) { bid(counter) }

        val value = counter.diagnostics()["publicTokenCounter"] as Map<*, *>
        assertThat(value["bidDecisions"]).isEqualTo(100)
        assertThat(value["decisionProofs"] as List<*>).hasSize(RevealedTokenCounter.PROOF_LIMIT)
    }

    @Test
    fun publicOrderOutcomesJoinForecastsToActualPrintedBoxesOnlyOnce() {
        val counter = learned()
        bid(counter)
        val revealed =
            view(Stage.DRAW_CARD, 3)
                .copy(
                    revealedTokenPurchases = mapOf(target to 21, rival to 20),
                    orderBoxes = mapOf(target to 3, rival to 1),
                )
        counter.order(revealed, rival)
        counter.order(revealed, rival)

        val value = counter.diagnostics()["publicTokenCounter"] as Map<*, *>
        assertThat(value["resolvedOrderOutcomes"]).isEqualTo(1)
        assertThat(value["rivalBeforeTargetCount"]).isEqualTo(1)
        assertThat(value["predictionHits"]).isEqualTo(1)
        val proofs = value["decisionProofs"] as List<*>
        assertThat(proofs.last() as Map<*, *>).containsEntry("rivalOrderBox", 1)
    }

    @Test
    fun incompleteOrderResolutionDoesNotInventAnAdvantage() {
        val counter = learned()
        bid(counter)

        counter.order(view(Stage.TURN_ORDER_TIE, 3), rival)

        val value = counter.diagnostics()["publicTokenCounter"] as Map<*, *>
        assertThat(value["resolvedOrderOutcomes"]).isEqualTo(0)
    }

    @Test
    fun expansionActivityCountsActualAttacksCardsAndUnspentTokens() {
        val counter = counter()
        counter.expansion(rival, "attack basel", 8)
        counter.expansion(rival, "buy-expansion-card", 6)
        counter.expansion(rival, "pass", 3)

        val value = counter.diagnostics()["publicTokenCounter"] as Map<*, *>
        val rivals = value["rivalExpansionActivity"] as Map<*, *>
        val actor = rivals[rival.value] as Map<*, *>
        assertThat(actor["attacks"]).isEqualTo(1)
        assertThat(actor["expansionCards"]).isEqualTo(1)
        assertThat(actor["discardedPoolAtPass"]).isEqualTo(3L)
    }

    private fun counterAfterCurrentTargetCommand(command: String): String? {
        val league = league()
        league.observe(view(Stage.PLAY_CARDS, 1))
        league.observe(view(Stage.PLAY_CARDS, 2))
        val targetController =
            league.wrap(
                target,
                RobustOpponentStyle.REVEALED_UNDERCUT,
                SimulationController { command },
            )
        targetController.choose(decision(target, view(Stage.TOKEN_PURCHASE, 3)))
        val rivalController =
            league.wrap(
                rival,
                RobustOpponentStyle.REVEALED_UNDERCUT,
                SimulationController { "buy-tokens 8" },
            )
        return rivalController.choose(decision(rival, view(Stage.TOKEN_PURCHASE, 3)))
    }

    private fun tokenOnly(): SimulationController =
        object : SimulationController {
            override fun controls(decision: String): Boolean = decision == "buy-tokens"

            override fun choose(decision: SimulationDecision): String {
                check(controls(decision.observation.decision)) {
                    "The synthetic baseline must never receive an unsupported tie decision."
                }
                return "buy-tokens 8"
            }
        }

    private fun decision(
        actor: PlayerId,
        view: GameView,
        choice: String = "buy-tokens",
        commands: List<String> = offers,
    ): SimulationDecision =
        SimulationDecision(
            number = 0,
            seat = players.indexOf(actor),
            seed = 9,
            view = view,
            observation =
                BotObservation(
                    playerId = actor.value,
                    decision = choice,
                    context = emptyMap(),
                    availableCash = 40,
                    availableTokens = 0,
                    candidates = commands.map { BotCandidate(it, emptyMap()) },
                    rulesVersion = RULES_VERSION,
                    dataVersion = DATA_VERSION,
                ),
        )

    private fun view(stage: Stage, turn: Int): GameView {
        val header = GameHeader(players.map { Player(it, it.value) }, target)
        return GameState(header)
            .viewForPlayer(rival)
            .copy(
                stage = stage,
                turnNumber = turn,
                capitalOrder = players,
                revealedTokenPurchases = mapOf(target to 21),
            )
    }

    private fun league(config: RevealedTokenCounterConfig = RevealedTokenCounterConfig(2, 4, 1)) =
        RevealedTokenCounterLeague(target, players, config)

    private fun counter(config: RevealedTokenCounterConfig = RevealedTokenCounterConfig(2, 4, 1)) =
        RevealedTokenCounter(target, config)

    private fun learned(): RevealedTokenCounter =
        counter().also {
            it.observe(PublicTokenReveal(1, 21))
            it.observe(PublicTokenReveal(2, 21))
        }

    private fun bid(
        counter: RevealedTokenCounter,
        turn: Int = 3,
        offered: List<String> = offers,
        style: RobustOpponentStyle = RobustOpponentStyle.REVEALED_UNDERCUT,
    ) = counter.bid("buy-tokens 8", offered, turn, rival, players, style)

    companion object {
        private val players = (1..4).map { PlayerId("p$it") }
        private val target = players[0]
        private val rival = players[1]
        private val offers = (0..40).map { "buy-tokens $it" }
        private val tieOffers = listOf("choose-turn-position 1", "choose-turn-position 2")
    }
}
