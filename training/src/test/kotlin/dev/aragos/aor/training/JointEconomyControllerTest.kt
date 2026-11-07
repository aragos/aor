package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointEconomyControllerTest {
    @Test
    fun tokenInvestmentScalingCanChangeALegalBudgetWithoutChangingTheMargin() {
        CardAdvantageFixture().use { fixture ->
            val environment = environment(fixture)
            val observation =
                observation(
                    "buy-tokens",
                    listOf(0, 16, 36).map { amount ->
                        BotCandidate(
                            "buy-tokens $amount",
                            BotTokenValue.featureNames.associateWith { 0.0 } +
                                mapOf(
                                    "points" to if (amount == 16) 1.0 else 0.0,
                                    "planning.tokenValue.v2.plan.heldCommodity" to
                                        if (amount == 36) .016 else 0.0,
                                ),
                            cashCost = amount,
                        )
                    },
                )
            val candidate =
                JointEconomyCandidates.generate(environment.baseline.model).single {
                    it.id == "investment-high"
                }
            val decision = decision(observation)

            assertThat(
                    BotPolicy.choose(observation, BotDifficulty.HARD, 0, environment.baseline.model)
                )
                .isEqualTo("buy-tokens 16")
            assertThat(JointEconomyController(environment, candidate.policy).choose(decision))
                .isEqualTo("buy-tokens 36")
            val probe =
                requireNotNull(JointEconomyPreflight(environment).choices(decision)) {
                    "Token decisions belong to the fixed-input feasibility probe."
                }
            assertThat(probe.incumbent).isEqualTo("buy-tokens 16")
            assertThat(probe.candidates).hasSize(15)
            assertThat(probe.candidates["investment-high"]).isEqualTo("buy-tokens 36")
        }
    }

    @Test
    fun expansionScalingCanChangeAResourceExclusiveMarketChoice() {
        CardAdvantageFixture().use { fixture ->
            val environment = environment(fixture)
            val observation =
                observation(
                    "expand",
                    listOf(
                        BotCandidate(
                            "place 5 genoa",
                            mapOf("points" to 1.25),
                            tokenCost = 5,
                            group = "genoa",
                            family = "expansion",
                        ),
                        BotCandidate(
                            "place 5 venice",
                            mapOf("planning.heldCommodity" to 1.0),
                            tokenCost = 5,
                            group = "venice",
                            family = "expansion",
                        ),
                        BotCandidate("pass", emptyMap()),
                    ),
                )
            val candidate =
                JointEconomyCandidates.generate(environment.baseline.model).single {
                    it.id == "expansion-high"
                }

            assertThat(
                    BotPolicy.choose(observation, BotDifficulty.HARD, 0, environment.baseline.model)
                )
                .isEqualTo("place 5 genoa")
            assertThat(
                    JointEconomyController(environment, candidate.policy)
                        .choose(decision(observation))
                )
                .isEqualTo("place 5 venice")
        }
    }

    @Test
    fun purchaseSamplingKeepsFAsPriorEvenIfAnAxisFeatureWouldReverseTheRecommendation() {
        CardAdvantageFixture().use { fixture ->
            val environment = environment(fixture)
            val state = purchaseState()
            val actor = PlayerId("p1")
            val ordinary = BotObservations.from(state, actor)
            val advance = ordinary.candidates.first { it.family == "advance" }
            val observation =
                ordinary.copy(
                    candidates =
                        listOf(
                            advance.copy(
                                features = mapOf("points" to -1.25, "planning.heldCommodity" to 1.0)
                            ),
                            ordinary.candidates
                                .single { it.command == "pass" }
                                .copy(features = emptyMap()),
                        ),
                    advancePlanning = null,
                    reserveShortfallBasis = null,
                )
            val candidate =
                JointEconomyCandidates.generate(environment.baseline.model).single {
                    it.id == "expansion-high"
                }
            val controller = JointEconomyController(environment, candidate.policy)
            val purchase =
                PurchaseActorController(environment.baseline.model, environment.purchase.model)

            assertThat(
                    BotPolicy.choose(observation, BotDifficulty.HARD, 0, environment.baseline.model)
                )
                .isEqualTo("pass")
            assertThat(BotPolicy.choose(observation, BotDifficulty.HARD, 0, candidate.policy.model))
                .isEqualTo(advance.command)
            listOf(0, 1, -1, 71, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
                val decision =
                    SimulationDecision(10, 0, seed, state.viewForPlayer(actor), observation)
                assertThat(controller.choose(decision)).isEqualTo(purchase.choose(decision))
            }
        }
    }

    @Test
    fun nonEconomicDecisionsFallThroughEvenWithAnAxisFeaturePresent() {
        CardAdvantageFixture().use { fixture ->
            val environment = environment(fixture)
            val policy = JointEconomyCandidates.generate(environment.baseline.model).last().policy
            val controller = JointEconomyController(environment, policy)
            val observation =
                observation(
                    "play-cards",
                    listOf(BotCandidate("pass", mapOf("planning.heldCommodity" to 10.0))),
                )

            assertThat(controller.controls("buy-tokens")).isTrue()
            assertThat(controller.controls("expand")).isTrue()
            assertThat(controller.controls("purchase-shipping")).isTrue()
            assertThat(controller.controls("purchase-advances")).isTrue()
            assertThat(controller.controls("play-cards")).isFalse()
            assertThat(controller.controls("stabilization")).isFalse()
            assertThat(controller.choose(decision(observation))).isNull()
            assertThat(JointEconomyPreflight(environment).choices(decision(observation))).isNull()
        }
    }

    @Test
    fun aPolicyCannotBeReboundToADifferentPurchaseBaseline() {
        CardAdvantageFixture().use { fixture ->
            val environment = environment(fixture)
            val policy = JointEconomyCandidates.generate(environment.baseline.model).last().policy
            val changed =
                environment.copy(baseline = environment.baseline.copy(model = policy.model))

            assertFailsWith<IllegalArgumentException> { JointEconomyController(changed, policy) }
        }
    }

    private fun environment(fixture: CardAdvantageFixture): CardAdvantageEnvironment {
        fixture.artifacts.model(JointEconomyPolicyTest.baseline, "baseline.json")
        return fixture.input.environment
    }

    private fun observation(decision: String, candidates: List<BotCandidate>) =
        BotObservation(
            playerId = "p1",
            decision = decision,
            context = mapOf("stock" to 36.0),
            availableCash = 100,
            availableTokens = 5,
            candidates = candidates,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun decision(observation: BotObservation) =
        SimulationDecision(
            10,
            0,
            0,
            purchaseState().viewForPlayer(PlayerId("p1")),
            observation,
        )

    private fun purchaseState(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, players.first().id),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to PlayerState(player, capital = capitals[index], cash = 100)
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = players.first().id,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }
}
