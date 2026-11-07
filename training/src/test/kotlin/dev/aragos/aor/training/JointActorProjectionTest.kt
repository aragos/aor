package dev.aragos.aor.training

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
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test

class JointActorProjectionTest {
    @Test
    fun rebateFundedEscrowRetainsTheActualCashInBothTypedViews() {
        JointCollectionFixture().use { fixture ->
            val state = state(printedWord = true, cash = 10)
            val card = observe(fixture, state).first.single()
            val quoted = card.observation.actions.single { it.command == "play held protect 20" }
            val after = state.step(TextCodec.parse("p1: play held protect 20"))
            val purchase = observe(fixture, purchaseWindow(after)).second.single()

            assertThat(after.playerState(actor).cash).isEqualTo(10)
            assertThat(quoted.features["cashAfter"]).isEqualTo(.05)
            assertThat(purchase.observation.stateFeatures["role.0.cash"]).isEqualTo(.05)
            assertThat(purchase.observation.stateFeatures["leader.leonardo-da-vinci.present"])
                .isEqualTo(1.0)
        }
    }

    @Test
    fun plainAndZeroProtectionExposeTheSameSinglePrintedWordReceipt() {
        JointCollectionFixture().use { fixture ->
            val state = state(printedWord = true, cash = 10)
            val actions = observe(fixture, state).first.single().observation.actions
            val plain = actions.single { it.command == "play held" }
            val zero = actions.single { it.command == "play held protect 0" }
            val after = state.step(TextCodec.parse("p1: play held protect 0"))

            assertThat(plain.features["cashAfter"]).isEqualTo(zero.features["cashAfter"])
            assertThat(zero.features["cashAfter"]).isEqualTo(after.playerState(actor).cash / 200.0)
            assertThat(plain.features["effect.role.0.cashDelta"])
                .isEqualTo(zero.features["effect.role.0.cashDelta"])
        }
    }

    @Test
    fun finalCardReceiptDoesNotInventAPurchaseRecordOrWindow() {
        JointCollectionFixture().use { fixture ->
            val final =
                state(printedWord = true, cash = 10)
                    .copy(stage = Stage.FINAL_CARD_PLAY, endingTriggered = true)
            val recorded = observe(fixture, final)
            val after = final.step(TextCodec.parse("p1: play held protect 20"))

            assertThat(recorded.first.single().observation.stateFeatures["cardWindow.final"])
                .isEqualTo(1.0)
            assertThat(recorded.second).isEmpty()
            assertThat(after.playerState(actor).cash).isEqualTo(10)
            assertThat(after.stage).isNotEqualTo(Stage.PURCHASE)
        }
    }

    @Test
    fun actualPrerequisitePurchaseRegeneratesTheNextTypedActionSet() {
        JointCollectionFixture().use { fixture ->
            val state = purchaseWindow(state(cash = 1000))
            val before = observe(fixture, state).second.single()
            val after =
                observe(fixture, state.step(TextCodec.parse("p1: buy-advance N"))).second.single()

            assertThat(before.observation.actions.map { it.command })
                .doesNotContain("buy-advance O")
            assertThat(after.observation.actions.map { it.command }).contains("buy-advance O")
        }
    }

    @Test
    fun actualResearchPurchaseRepricesTheNextTypedQuote() {
        JointCollectionFixture().use { fixture ->
            val state = purchaseWindow(state(cash = 1000))
            val before =
                observe(fixture, state).second.single().observation.actions.single {
                    it.command == "buy-advance A"
                }
            val after =
                observe(fixture, state.step(TextCodec.parse("p1: buy-advance X")))
                    .second
                    .single()
                    .observation
                    .actions
                    .single { it.command == "buy-advance A" }

            assertThat(before.features["cost"]).isEqualTo(.15)
            assertThat(after.features["cost"]).isEqualTo(.1)
            assertThat(after.features["researchCredit"]).isEqualTo(.1)
        }
    }

    @Test
    fun neitherTypedViewRevealsAnOpponentsPrivateHandOrDeckOrder() {
        JointCollectionFixture().use { fixture ->
            val original = state(printedWord = true, cash = 100)
            val rival = PlayerId("p2")
            val changed =
                original.copy(
                    deck = original.deck.reversed(),
                    players =
                        original.players +
                            (rival to
                                original
                                    .playerState(rival)
                                    .copy(
                                        hand =
                                            listOf(
                                                HandCard(
                                                    "private",
                                                    CardData.definition("marco-polo"),
                                                )
                                            )
                                    )),
                )

            assertThat(observe(fixture, original)).isEqualTo(observe(fixture, changed))
            assertThat(observe(fixture, purchaseWindow(original)))
                .isEqualTo(observe(fixture, purchaseWindow(changed)))
        }
    }

    private fun observe(
        fixture: JointCollectionFixture,
        state: GameState,
    ): Pair<List<CardDecisionRecord>, List<PurchaseDecisionRecord>> {
        val cards = mutableListOf<CardDecisionRecord>()
        val purchases = mutableListOf<PurchaseDecisionRecord>()
        val input = fixture.input
        val controller = input.bundle.controller(input.baseline, cards::add, purchases::add)
        controller.choose(
            SimulationDecision(
                0,
                0,
                71,
                state.viewForPlayer(actor),
                BotObservations.from(state, actor),
            )
        )
        return cards.toList() to purchases.toList()
    }

    /** Keep the engine-applied resource/effect endpoint while isolating its next purchase quote. */
    private fun purchaseWindow(state: GameState): GameState =
        state.copy(
            stage = Stage.PURCHASE,
            flow =
                TurnFlow(
                    actor = actor,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )

    private fun state(printedWord: Boolean = false, cash: Int = 100): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        val owned =
            if (printedWord)
                setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.HUMAN_BODY, AdvanceId.PRINTED_WORD)
            else emptySet()
        return GameState(
            header = GameHeader(players, actor),
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            deck = listOf("stone-1", "wool-1"),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = cash,
                                advances =
                                    if (index == 0)
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                                hand =
                                    when (index) {
                                        0 ->
                                            listOf(
                                                HandCard(
                                                    "held",
                                                    CardData.definition("leonardo-da-vinci"),
                                                )
                                            )
                                        1 ->
                                            listOf(
                                                HandCard(
                                                    "private",
                                                    CardData.definition("charlemagne"),
                                                )
                                            )
                                        else -> emptyList()
                                    },
                            )
                    }
                    .toMap(),
            // The display already protects its first leader; only subsequent leaders offer escrow.
            effects =
                CardTurnEffects(
                    leaders =
                        listOf(
                            CardLeaderInPlay(
                                LeaderId.ST_BENEDICT,
                                PlayerId("p2"),
                                CardLeaderRole.DISPLAY,
                            )
                        )
                ),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }

    companion object {
        private val actor = PlayerId("p1")
    }
}
