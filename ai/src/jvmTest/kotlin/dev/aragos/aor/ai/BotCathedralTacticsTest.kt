package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.ExpansionPosition
import dev.aragos.aor.ExpansionTacticalFrontier
import dev.aragos.aor.ExpansionTransition
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvincePieces
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenHolding
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf

class BotCathedralTacticsTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val model =
        BotModel(
            weights = mapOf("progress" to 20.0, "income" to 10.0, "tokens" to 0.5, "risk" to -1.0)
        )

    @Test
    fun disabledSelectorReturnsTheCompleteIncumbentWithoutPlanning() {
        val state = fixture()
        val observation = observation(state)

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation,
                "attack florence",
                model,
            )

        assertThat(result).isEqualTo(BotCathedralChoice("attack florence"))
    }

    @Test
    fun cheapAttackCanRetainAFullMarketFollowupAfterABlock() {
        val state = fixture()
        val observation = observation(state)
        val incumbent = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation,
                incumbent,
                model,
                true,
            )

        assertThat(incumbent).isEqualTo("attack florence")
        assertThat(result.command).isEqualTo("attack bari")
        assertThat(result.rootValues["attack bari"])
            .isGreaterThan(result.rootValues["attack florence"])
        assertThat(result.resolvedLeaves).isAtMost(6400)
        assertThat(result.stopEvaluations).isAtMost(80)
        assertThat(result.firstActionVictoriesStopped).isGreaterThan(0)
    }

    @Test
    fun offeredPassAvoidsRobustlyNegativeAttacks() {
        val state = fixture()
        val costly = BotModel(weights = mapOf("tokens" to 1.0, "risk" to -10.0))

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation(state),
                "attack florence",
                costly,
                true,
            )

        assertThat(result.command).isEqualTo("pass")
        assertThat(result.rootValues["pass"]).isEqualTo(0.0)
    }

    @Test
    fun absentPassIsNeverInvented() {
        val state = fixture()
        val costly = BotModel(weights = mapOf("tokens" to 1.0, "risk" to -10.0))
        val supplied = observation(state, setOf("attack florence"))

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                supplied,
                "attack florence",
                costly,
                true,
            )

        assertThat(result.command).isEqualTo("attack florence")
        assertThat(result.rootValues.keys).containsExactly("attack florence")
    }

    @Test
    fun exactUtilityTieKeepsTheIncumbent() {
        val state = fixture()

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation(state),
                "attack florence",
                BotModel(weights = emptyMap()),
                true,
            )

        assertThat(result.command).isEqualTo("attack florence")
    }

    @Test
    fun nonExposedIncumbentRetainsItsOriginalChoice() {
        val state = fixture(defenderCathedral = false)

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation(state),
                "attack florence",
                model,
                true,
            )

        assertThat(result).isEqualTo(BotCathedralChoice("attack florence"))
    }

    @Test
    fun blockedAttackEarnsOnlyRealResourceCosts() {
        val state = fixture()
        val root = position(state)
        val blocked = pending(root.reserve(id("florence"), 8)).useCathedral(p2)

        val features =
            BotExpansionOutcomeValue(state.viewForDecision(p1), root, model)
                .features(blocked.position, 8)

        assertThat(features["tokens"]).isEqualTo(-8.0)
        assertThat(features["risk"]).isEqualTo(8.0)
        for (name in listOf("progress", "reach", "commodity", "income", "planning.heldCommodity")) {
            assertThat(features[name]).isEqualTo(0.0)
        }
        assertThat(features).doesNotContainKey("action.expansion")
    }

    @Test
    fun lostExistingTokensReduceControlWithoutBecomingPoolOrExtraCommitment() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(p1 to TokenHolding(2, 0), p2 to TokenHolding(1, 0))
                        )
                )
            )
        val state = fixture(board = board)
        val root = position(state)
        val blocked = pending(root.reserve(id("florence"), 3)).useCathedral(p2)

        val features =
            BotExpansionOutcomeValue(state.viewForDecision(p1), root, model)
                .features(blocked.position, 3)

        assertThat(features["progress"]).isEqualTo(-0.5)
        assertThat(features["tokens"]).isEqualTo(-3.0)
        assertThat(features["risk"]).isEqualTo(3.0)
    }

    @Test
    fun defenderCanDeclineRatherThanAcceptTheBaitAssumption() {
        val frontier =
            ExpansionTacticalFrontier.responses(
                pending(position(fixture()).reserve(id("florence"), 8))
            )
        val values =
            frontier.branches.map {
                when {
                    it.declaredBy != null -> 20.0
                    it.result.competition?.attackerWon == true -> 10.0
                    else -> -5.0
                }
            }

        val selected = BotCathedralTactics.aggregate(frontier, values)

        assertThat(selected).isWithin(1e-12).of((174 * 10.0 - 42 * 5.0) / 216.0)
    }

    @Test
    fun actorMaxIncludesDecliningItsOwnCathedral() {
        val frontier =
            ExpansionTacticalFrontier.responses(
                pending(
                    position(fixture(defenderCathedral = false, actorCathedral = true))
                        .reserve(id("florence"), 8)
                )
            )
        val values =
            frontier.branches.map {
                when {
                    it.declaredBy != null -> -50.0
                    it.result.competition?.attackerWon == true -> 10.0
                    else -> -5.0
                }
            }

        val selected = BotCathedralTactics.aggregate(frontier, values)

        assertThat(selected).isWithin(1e-12).of((174 * 10.0 - 42 * 5.0) / 216.0)
    }

    @Test
    fun privateOpponentCardsCannotChangeTheSearch() {
        val state = fixture()
        val changed =
            state.copy(
                players =
                    state.players.mapValues { (id, player) ->
                        if (id == p1) player
                        else
                            player.copy(
                                hand = listOf(HandCard("hidden", CardData.definition("gold-3")))
                            )
                    }
            )

        val before =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation(state),
                "attack florence",
                model,
                true,
            )
        val after =
            BotCathedralTactics.choose(
                changed.viewForDecision(p1),
                observation(changed),
                "attack florence",
                model,
                true,
            )

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun peacefulFullMarketRootGetsTheSameFollowupOpportunity() {
        val state = fixture()
        val offered = BotObservations.from(state, p1)
        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                offered,
                "attack florence",
                model,
                true,
            )

        assertThat(result.rootValues.keys.any { it.startsWith("place ") }).isTrue()
        assertThat(result.stopEvaluations).isGreaterThan(0)
        assertThat(offered.candidates.map { it.command }).contains(result.command)
        assertThat(result.retainedNonpassRoots).isAtMost(16)
        assertThat(result.legalNonpassRoots).isAtLeast(result.retainedNonpassRoots)
    }

    private fun observation(
        state: GameState,
        commands: Set<String> = setOf("attack florence", "attack bari", "pass"),
    ): BotObservation {
        val original = BotObservations.from(state, p1)
        return original.copy(
            candidates = original.candidates.filter { it.command in commands },
            expansionPlanning = null,
        )
    }

    private fun position(state: GameState): ExpansionPosition =
        requireNotNull(state.viewForDecision(p1).expansionPosition) {
                "The fixture requires a ready expansion position."
            }
            .toPosition()

    private fun pending(value: ExpansionTransition): ExpansionTransition.PendingCompetition =
        value as ExpansionTransition.PendingCompetition

    @Test
    fun cheapProbeSurvivesMoreThanSixteenHighNominalAlternatives() {
        val expensive =
            (1..20).map {
                BotCandidate("attack market-$it", mapOf("progress" to (100.0 + it)), tokenCost = 8)
            }
        val cheap = BotCandidate("attack probe", mapOf("progress" to -100.0), tokenCost = 1)
        val incumbent = expensive.last().command

        val retained = BotCathedralTactics.retainRoots(expensive + cheap, incumbent, model)

        assertThat(retained).hasSize(16)
        assertThat(retained.map(BotCandidate::command)).containsAtLeast(incumbent, cheap.command)
        assertThat(retained.distinct()).hasSize(16)
    }

    @Test
    fun firstActionVictoryStopsEvenWhenAnotherAttackIsPubliclyLegal() {
        val state = fixture()
        val observation =
            observation(state).let { original ->
                original.copy(
                    candidates =
                        original.candidates.filter {
                            it.command == "attack bari" || it.command == "pass"
                        }
                )
            }
        val root = position(state)
        val cost = observation.candidates.single { it.command == "attack bari" }.tokenCost
        val frontier = ExpansionTacticalFrontier.responses(pending(root.reserve(id("bari"), cost)))
        val victories = frontier.branches.filter { it.result.competition?.attackerWon == true }

        val result =
            BotCathedralTactics.choose(
                state.viewForDecision(p1),
                observation,
                "attack bari",
                model,
                enabled = true,
            )

        assertThat(victories).isNotEmpty()
        assertThat(
                victories.all { branch ->
                    ExpansionTacticalFrontier.attacks(branch.result.position).options.any {
                        it.destination == id("florence")
                    }
                }
            )
            .isTrue()
        assertThat(result.firstActionVictoriesStopped).isEqualTo(victories.size)
        assertThat(result.stopEvaluations).isEqualTo(frontier.branches.size - victories.size)
    }

    private fun fixture(
        defenderCathedral: Boolean = true,
        actorCathedral: Boolean = false,
        board: BoardState =
            BoardState()
                .placeMarker(id("naples"), p1)
                .board
                .placeMarker(id("florence"), p2)
                .board
                .replaceWithToken(id("bari"), p2)
                .board,
    ): GameState {
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = 10,
                                stockTokens = 26 - board.tokenCount(player.id),
                                advances =
                                    PlayerAdvances(
                                        if (
                                            player.id == p2 && defenderCathedral ||
                                                player.id == p1 && actorCathedral
                                        ) {
                                            listOf(AdvanceAcquisition(AdvanceId.CATHEDRAL, 1))
                                        } else {
                                            emptyList()
                                        }
                                    ),
                            )
                    }
                    .toMap(),
        )
    }

    private fun id(value: String) = ProvinceId(value)
}
