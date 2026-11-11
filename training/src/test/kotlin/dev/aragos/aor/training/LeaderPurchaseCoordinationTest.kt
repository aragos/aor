package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.AdvanceOfferView
import dev.aragos.aor.AdvanceQuote
import dev.aragos.aor.AppliedLeaderDiscount
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ShippingOfferView
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test

class LeaderPurchaseCoordinationTest {
    @Test
    fun shippingReservationPreservesTheCurrentlyQuotedTargetBudget() {
        val controller = controller("upgrade-ships")

        val command = controller.choose(decision(cash = 85))

        assertThat(command).isEqualTo("pass")
    }

    @Test
    fun shippingRemainsUnchangedWhenBothPurchasesFit() {
        val controller = controller("upgrade-ships")

        val command = controller.choose(decision(cash = 95))

        assertThat(command).isEqualTo("upgrade-ships")
    }

    @Test
    fun offeredTargetIsBoughtAndTheCommitmentEnds() {
        val controller = controller("buy-advance A")

        val command = controller.choose(decision(phase = "purchase-advances"))

        assertThat(command).isEqualTo("buy-advance X")
        assertThat(controller.receipt()["ended"]).isEqualTo("target-purchased")
    }

    @Test
    fun completedCommitmentLeavesLaterPurchaseChoicesUnchanged() {
        val controller = controller("buy-advance A")
        controller.choose(decision(phase = "purchase-advances"))

        val command = controller.choose(decision(phase = "purchase-advances"))

        assertThat(command).isEqualTo("buy-advance A")
    }

    @Test
    fun lostAffordabilityAbortsInsteadOfForcingAnIllegalTarget() {
        val controller = controller("upgrade-ships")
        val input =
            decision().let {
                it.copy(
                    view =
                        it.view.copy(
                            advanceOffers =
                                listOf(offer().copy(unavailableReason = "INSUFFICIENT_CASH"))
                        )
                )
            }

        val command = controller.choose(input)

        assertThat(command).isEqualTo("upgrade-ships")
        assertThat(controller.receipt()["ended"]).isEqualTo("INSUFFICIENT_CASH")
    }

    @Test
    fun missingLeaderCreditAbortsEvenWhenTheAdvanceRemainsAffordable() {
        val controller = controller("buy-advance A")
        val input =
            decision(phase = "purchase-advances").let {
                it.copy(view = it.view.copy(advanceOffers = listOf(offer(credit = 0))))
            }

        val command = controller.choose(input)

        assertThat(command).isEqualTo("buy-advance A")
        assertThat(controller.receipt()["ended"]).isEqualTo("leader-credit-expired")
    }

    @Test
    fun nextTurnCannotReviveTheCommitment() {
        val controller = controller("upgrade-ships")
        val input = decision().let { it.copy(view = it.view.copy(turnNumber = 5)) }

        val command = controller.choose(input)

        assertThat(command).isEqualTo("upgrade-ships")
        assertThat(controller.receipt()["ended"]).isEqualTo("turn-expired")
    }

    @Test
    fun otherCardChoicesAreDelegatedWithoutObservationChanges() {
        val input = decision(phase = "play-cards")
        var received: SimulationDecision? = null
        val controller =
            LeaderPurchaseCoordination(
                actor,
                4,
                target,
                SimulationController {
                    received = it
                    "play hand-9"
                },
            )

        val command = controller.choose(input)

        assertThat(command).isEqualTo("play hand-9")
        assertThat(received).isSameInstanceAs(input)
    }

    @Test
    fun targetRankingUsesActualSavingAfterTheAuthoritativePriceFloor() {
        val view =
            view()
                .copy(
                    advanceOffers =
                        listOf(
                            offer(advance = AdvanceId.THE_HEAVENS, base = 5, credit = 30),
                            offer(),
                        )
                )

        val chosen = LeaderPurchaseQuotes.target(view, "nicolaus-copernicus")

        assertThat(chosen?.advance).isEqualTo(AdvanceId.INSTITUTIONAL_RESEARCH)
        assertThat(chosen?.savingAtRoot).isEqualTo(20)
    }

    @Test
    fun equalSavingTargetsUseCheaperQuoteThenPrintedLetter() {
        val view =
            view()
                .copy(
                    advanceOffers =
                        listOf(
                            offer(),
                            offer(advance = AdvanceId.HUMAN_BODY, base = 60),
                            offer(advance = AdvanceId.THE_HEAVENS, base = 60),
                        )
                )

        val chosen = LeaderPurchaseQuotes.target(view, "nicolaus-copernicus")

        assertThat(chosen?.advance).isEqualTo(AdvanceId.THE_HEAVENS)
    }

    @Test
    fun blockedTargetCannotQualifyAnOrigin() {
        val view =
            view()
                .copy(
                    advanceOffers =
                        listOf(offer().copy(unavailableReason = "MISSING_ADVANCE_PREREQUISITE"))
                )

        val chosen = LeaderPurchaseQuotes.target(view, "nicolaus-copernicus")

        assertThat(chosen).isNull()
    }

    @Test
    fun retainedRootsFollowWorldIndexAndCapRegardlessOfCompletionOrder() {
        val state = GameState(header())
        val worlds =
            (0..39).reversed().map { world ->
                val root =
                    LeaderCoordinationRoot(world, state, actor, "play hand-1", target, emptyMap())
                LeaderCoordinationWorld(world, root, emptyMap())
            }

        val roots = LeaderCoordinationMain.retained(worlds)

        assertThat(roots.map { it.world }).containsExactlyElementsIn(0..31).inOrder()
    }

    private val actor = PlayerId("p2")
    private val target =
        LeaderPurchaseTarget(AdvanceId.INSTITUTIONAL_RESEARCH, "nicolaus-copernicus", 80, 20)

    private fun controller(command: String): LeaderPurchaseCoordination =
        LeaderPurchaseCoordination(actor, 4, target, SimulationController { command })

    private fun header(): GameHeader {
        val players = (1..4).map { Player(PlayerId("p$it"), "Fixture $it") }
        return GameHeader(players, players.first().id)
    }

    private fun view(cash: Int = 85): GameView =
        GameState(header())
            .publicView()
            .copy(
                turnNumber = 4,
                stage = Stage.PURCHASE,
                advanceOffers = listOf(offer()),
                shippingOffer = ShippingOfferView(10, null, cash - 10, emptyList()),
            )

    private fun offer(
        advance: AdvanceId = AdvanceId.INSTITUTIONAL_RESEARCH,
        base: Int = 100,
        credit: Int = 20,
    ): AdvanceOfferView {
        val quote =
            AdvanceQuote(
                advance,
                base,
                emptyList(),
                0,
                listOf(AppliedLeaderDiscount("nicolaus-copernicus", credit, 0)),
                MiseryStep.ZERO,
                MiseryStep.ZERO,
            )
        return AdvanceOfferView(advance, quote.price, null, quote.leaders, quote)
    }

    private fun decision(cash: Int = 85, phase: String = "purchase-shipping"): SimulationDecision =
        SimulationDecision(
            0,
            1,
            11,
            view(cash),
            BotObservation(
                playerId = actor.value,
                decision = phase,
                context = emptyMap(),
                availableCash = cash,
                availableTokens = 0,
                candidates =
                    listOf("pass", "upgrade-ships", "buy-advance X", "buy-advance A").map {
                        BotCandidate(it, emptyMap())
                    },
                rulesVersion = RULES_VERSION,
                dataVersion = DATA_VERSION,
            ),
        )
}
