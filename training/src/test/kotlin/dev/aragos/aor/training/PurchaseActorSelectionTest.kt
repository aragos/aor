package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseActorSelectionTest {
    @Test
    fun defaultAndExplicitSampleKeepTheOriginalSeededInference() {
        ArenaPurchaseFixture().use { fixture ->
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val original = PurchaseActorController(BotModels.default, policy)
            val explicit =
                PurchaseActorController(
                    BotModels.default,
                    policy,
                    selection = PurchaseActorSelection.SAMPLE,
                )
            val decisions = (0..63).map { purchaseSelectionDecision(seed = it) }

            val actual = decisions.map { original.choose(it) to explicit.choose(it) }

            val expected = decisions.map { decision ->
                val chosen =
                    PurchasePolicy.choose(
                            purchaseSelectionObservation(decision),
                            policy,
                            PurchasePolicy.actorSeed(decision.seed),
                        )
                        .command
                chosen to chosen
            }
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun sampledRecordingRetainsExactOriginalLikelihoodValueAndSeed() {
        ArenaPurchaseFixture().use { fixture ->
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val decision = purchaseSelectionDecision(seed = 71)
            val observation = purchaseSelectionObservation(decision)
            val actorSeed = PurchasePolicy.actorSeed(decision.seed)
            val expected = PurchasePolicy.choose(observation, policy, actorSeed)
            val records = mutableListOf<PurchaseDecisionRecord>()
            val controller = PurchaseActorController(BotModels.default, policy) { records.add(it) }

            val command = controller.choose(decision)

            assertThat(command).isEqualTo(expected.command)
            assertThat(records)
                .containsExactly(
                    PurchaseDecisionRecord(
                        decision.number,
                        actorSeed,
                        decision.observation.decision,
                        2,
                        observation,
                        expected.index,
                        expected.logProbability,
                        expected.value,
                    )
                )
        }
    }

    @Test
    fun maximumProbabilityUsesTheSameDistributionIndependentlyOfSeed() {
        ArenaPurchaseFixture().use { fixture ->
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val decision = purchaseSelectionDecision()
            val observation = purchaseSelectionObservation(decision)
            val probabilities = PurchasePolicy.evaluate(observation, policy).probabilities
            val expected =
                observation.actions[probabilities.indices.maxBy { probabilities[it] }].command
            val controller =
                PurchaseActorController(
                    BotModels.default,
                    policy,
                    selection = PurchaseActorSelection.MAXIMUM_PROBABILITY,
                )

            val commands =
                listOf(Int.MIN_VALUE, -1, 0, 1, 71, Int.MAX_VALUE).map {
                    controller.choose(decision.copy(seed = it))
                }

            assertThat(commands.toSet()).containsExactly(expected)
        }
    }

    @Test
    fun exactMaximumTiesChooseTheFirstCanonicalCommand() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val advanceIndex = initial.actionFeatureNames.indexOf("kind.advance")
            val policy =
                initial.copy(
                    actionWeights =
                        initial.actionWeights.mapIndexed { index, value ->
                            if (index == advanceIndex) 8.0 else value
                        },
                    actionBias =
                        initial.actionBias.mapIndexed { index, value ->
                            if (index == 0) -4.0 else value
                        },
                    actorWeights =
                        initial.actorWeights.mapIndexed { index, value ->
                            if (index == 0) 100.0 else value
                        },
                )
            val prior = BotModel(weights = mapOf("action.pass" to 1.0))
            val decision = purchaseSelectionDecision()
            val observation = purchaseSelectionObservation(decision, prior)
            val probabilities = PurchasePolicy.evaluate(observation, policy).probabilities
            val maximum = probabilities.max()
            val maxima = observation.actions.indices.filter { probabilities[it] == maximum }
            val controller =
                PurchaseActorController(
                    prior,
                    policy,
                    selection = PurchaseActorSelection.MAXIMUM_PROBABILITY,
                )

            val command = controller.choose(decision)

            assertThat(observation.actions[observation.baselineIndex].command).isEqualTo("pass")
            assertThat(maxima.size).isGreaterThan(1)
            assertThat(command).isEqualTo(observation.actions[maxima.first()].command)
        }
    }

    @Test
    fun deterministicSelectionCannotCreateAnOnPolicyRecord() {
        ArenaPurchaseFixture().use { fixture ->
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PurchaseActorController(
                        BotModels.default,
                        policy,
                        record = {},
                        selection = PurchaseActorSelection.MAXIMUM_PROBABILITY,
                    )
                }

            assertThat(failure).hasMessageThat().contains("cannot record sampled behavior")
        }
    }

    @Test
    fun deterministicSelectionRequiresAnActor() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PurchaseActorController(
                    BotModels.default,
                    null,
                    selection = PurchaseActorSelection.MAXIMUM_PROBABILITY,
                )
            }

        assertThat(failure).hasMessageThat().contains("requires an actor")
    }

    @Test
    fun selectionLeavesAllNonpurchaseDecisionsUncontrolled() {
        ArenaPurchaseFixture().use { fixture ->
            val controller =
                PurchaseActorController(
                    BotModels.default,
                    PurchasePolicyInput.read(fixture.directory.resolve("policy.json")),
                    selection = PurchaseActorSelection.MAXIMUM_PROBABILITY,
                )

            val controlled =
                listOf(
                        "purchase-shipping",
                        "purchase-advances",
                        "play-cards",
                        "expand",
                        "buy-tokens",
                        "purchase-stabilization",
                    )
                    .filter(controller::controls)

            assertThat(controlled).containsExactly("purchase-shipping", "purchase-advances")
        }
    }
}

/** Synthetic authoritative purchase root; no game is simulated for the decoding tests. */
internal fun purchaseSelectionDecision(seat: Int = 0, seed: Int = 71): SimulationDecision {
    val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    val capitals = listOf("venice", "genoa", "barcelona", "paris")
    val actor = players[seat].id
    val board =
        players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
            current.placeMarker(ProvinceId(capital), player.id).board
        }
    val state =
        GameState(
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
                    actor = actor,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    return SimulationDecision(
        10,
        seat,
        seed,
        state.viewForPlayer(actor),
        BotObservations.from(state, actor),
    )
}

internal fun purchaseSelectionObservation(
    decision: SimulationDecision,
    prior: BotModel = BotModels.default,
): PurchaseObservation =
    PurchaseObservationEncoder.from(
        decision.view,
        PlayerId(decision.observation.playerId),
        decision.observation,
        BotPolicy.choose(decision.observation, BotDifficulty.HARD, decision.seed, prior),
    )
