package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotLeaderAlternative
import dev.aragos.aor.ai.BotLeaderPlanning
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test

class RecordedLeaderHoldTest {
    @Test
    fun controllerContinuesOtherCardsDuringTheHeldTurn() {
        val controller = controller()

        val chosen = controller.choose(decision(turn = 4))

        assertThat(chosen).isEqualTo("play hand-2")
    }

    @Test
    fun controllerRestoresTheLeaderAtTheNextTurn() {
        val controller = controller()

        val chosen = controller.choose(decision(turn = 5))

        assertThat(chosen).isEqualTo("play hand-1")
        assertThat(controller.receipt()["maskedDecisions"]).isEqualTo(0)
    }

    @Test
    fun controllerRecordsAnUnavoidableLegalRelease() {
        val controller = controller()
        val ordinary = observation()
        val mandatory =
            ordinary.copy(candidates = listOf(ordinary.candidates.first()), leaderPlanning = null)

        val chosen = controller.choose(decision(turn = 4).copy(observation = mandatory))

        assertThat(chosen).isEqualTo("play hand-1")
        assertThat(controller.receipt()["forcedReleases"] as List<*>).hasSize(1)
    }

    @Test
    fun withholdingRemovesAllParameterizationsOfOnlyTheOriginalPhysicalCard() {
        val original = observation()

        val filtered =
            requireNotNull(HeldLeaderObservation.filter(original, "hand-1")) {
                "Other legal commands remain."
            }

        assertThat(filtered.candidates.map(BotCandidate::command))
            .containsExactly("play hand-2", "pass")
            .inOrder()
        assertThat(filtered.context).isSameInstanceAs(original.context)
        assertThat(filtered.availableCash).isEqualTo(original.availableCash)
    }

    @Test
    fun remainingLeadersKeepTheirAuthoritativeForecasts() {
        val original = observation()

        val filtered =
            requireNotNull(HeldLeaderObservation.filter(original, "hand-1")) {
                "Other legal commands remain."
            }

        assertThat(filtered.leaderPlanning?.leaders?.keys).containsExactly("play hand-2")
        assertThat(filtered.leaderPlanning?.forecasts)
            .isSameInstanceAs(original.leaderPlanning?.forecasts)
    }

    @Test
    fun anAbsentHeldCardLeavesTheExactObservationUnchanged() {
        val original = observation()

        val filtered = HeldLeaderObservation.filter(original, "missing")

        assertThat(filtered).isSameInstanceAs(original)
    }

    @Test
    fun removingTheOnlyLeaderForecastRetainsOtherCardsAndPass() {
        val original =
            observation()
                .copy(
                    leaderPlanning =
                        observation()
                            .leaderPlanning
                            ?.copy(
                                leaders =
                                    mapOf("play hand-1" to BotLeaderAlternative(null, null, 0.0))
                            )
                )

        val filtered =
            requireNotNull(HeldLeaderObservation.filter(original, "hand-1")) {
                "Other legal commands remain."
            }

        assertThat(filtered.leaderPlanning).isNull()
        assertThat(filtered.candidates.map(BotCandidate::command))
            .containsExactly("play hand-2", "pass")
    }

    @Test
    fun mandatorySingleCardPlayRequestsLegalReleaseInsteadOfAnEmptyPolicy() {
        val original =
            observation()
                .copy(candidates = listOf(observation().candidates.first()), leaderPlanning = null)

        val filtered = HeldLeaderObservation.filter(original, "hand-1")

        assertThat(filtered).isNull()
    }

    @Test
    fun discardsAreUnaffectedByThePlayOnlyIntervention() {
        val discard =
            BotCandidate(
                "discard-card hand-1",
                emptyMap(),
                group = "card.hand-1",
                family = "discard",
            )
        val original = observation().copy(candidates = listOf(discard), leaderPlanning = null)

        val filtered = HeldLeaderObservation.filter(original, "hand-1")

        assertThat(filtered).isSameInstanceAs(original)
    }

    private fun controller(): HeldLeaderController =
        HeldLeaderController(
            PlayerId("p2"),
            4,
            "hand-1",
            BotModels.default,
            SimulationController { it.observation.candidates.first().command },
        )

    private fun decision(turn: Int): SimulationDecision {
        val players = (1..3).map { Player(PlayerId("p$it"), "Fixture $it") }
        val view =
            GameState(GameHeader(players, players.first().id))
                .publicView()
                .copy(turnNumber = turn, stage = Stage.PLAY_CARDS)
        return SimulationDecision(0, 1, 11, view, observation())
    }

    private fun observation(): BotObservation =
        BotObservation(
            playerId = "p2",
            decision = "play-cards",
            context = mapOf("handCount" to 2.0),
            availableCash = 12,
            availableTokens = 0,
            candidates =
                listOf(
                    BotCandidate("play hand-1", emptyMap(), group = "card.hand-1", family = "play"),
                    BotCandidate(
                        "play hand-1 protection 2",
                        emptyMap(),
                        group = "card.hand-1",
                        family = "play",
                    ),
                    BotCandidate("play hand-2", emptyMap(), group = "card.hand-2", family = "play"),
                    BotCandidate("pass", emptyMap(), family = "pass"),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            leaderPlanning =
                BotLeaderPlanning(
                    emptyList(),
                    emptyList(),
                    mapOf(
                        "play hand-1" to BotLeaderAlternative(null, null, 0.0),
                        "play hand-1 protection 2" to BotLeaderAlternative(null, null, 0.0),
                        "play hand-2" to BotLeaderAlternative(null, null, 0.0),
                    ),
                    emptySet(),
                ),
        )
}
