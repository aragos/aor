package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import dev.aragos.aor.ai.BotObservations
import kotlin.test.Test

class JointActorEvaluationTest {
    @Test
    fun registryPreservesBothComponentIdentitiesAndLegacyDefaults() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.evaluation(job)
            val policy =
                requireNotNull(input.policies["candidate"]) { "The joint role is registered." }

            assertThat(policy.jointBundle?.sha256).isEqualTo(fixture.input.bundle.sha256)
            assertThat(policy.metadata().keys).containsExactly("kind", "path", "sha256", "bundle")
            assertThat(input.manifest()["jointActorVersion"]).isEqualTo(JointActorBundle.VERSION)
            assertThat(input.policies["baseline"]?.metadata()?.keys)
                .containsExactly("kind", "path", "sha256")
            assertThat(policy.controller(input.environment).controls("expand")).isFalse()
            assertThat(policy.controller(input.environment).controls("buy-tokens")).isFalse()
            assertThat(policy.controller(input.environment).controls("prepare-war")).isFalse()
        }
    }

    @Test
    fun jointPurchaseActorOverridesOnlyItsExplicitRoleRatherThanEnvironmentPurchase() {
        JointCollectionFixture().use { fixture ->
            val raw =
                PurchaseJson.read(fixture.artifacts.directory.resolve("purchase-policy.json"))
                    as ObjectNode
            val names = raw.required("actionFeatureNames").map(JsonNode::asText)
            (raw.required("actionWeights") as ArrayNode).set(
                names.indexOf("kind.pass"),
                PurchaseJson.mapper.valueToTree<JsonNode>(2.0),
            )
            (raw.required("actorWeights") as ArrayNode).set(
                0,
                PurchaseJson.mapper.valueToTree<JsonNode>(20.0),
            )
            fixture.artifacts.write("learned-purchase.json", raw)
            fixture.writeBundle("bundle.json", purchaseName = "learned-purchase.json")
            val input = fixture.evaluation(job)
            val joint =
                requireNotNull(input.policies["candidate"]) { "The joint role is registered." }
            val controller = joint.controller(input.environment)
            val own =
                PurchaseActorController(
                    input.environment.baseline.model,
                    requireNotNull(joint.jointBundle) { "The role owns a bundle." }.purchase.model,
                )
            val legacy =
                requireNotNull(input.policies["baseline"]) { "The baseline role is registered." }
                    .controller(input.environment)
            val state = purchaseState()
            val observation = BotObservations.from(state, PlayerId("p1"))
            val decisions =
                (0..31).map {
                    SimulationDecision(20, 0, it, state.viewForPlayer(PlayerId("p1")), observation)
                }

            assertThat(decisions.map(controller::choose)).isEqualTo(decisions.map(own::choose))
            assertThat(decisions.any { controller.choose(it) != legacy.choose(it) }).isTrue()
            assertThat(decisions.map(legacy::choose))
                .isEqualTo(
                    decisions.map {
                        PurchaseActorController(
                                input.environment.baseline.model,
                                input.environment.purchase.model,
                            )
                            .choose(it)
                    }
                )
        }
    }

    @Test
    fun repeatedBundleEvaluationMatchesItsJointCollector() {
        JointCollectionFixture(learnedRivals = true).use { fixture ->
            val collection = fixture.input
            val evaluated = fixture.evaluation(job.copy(seed = collection.seeds[0]))
            val reference = JointCollector(collection, "fixture", Long.MAX_VALUE).collect(0)

            val actual = CardAdvantageEvaluator(evaluated, Long.MAX_VALUE).evaluate(0)

            assertThat(actual.terminal).isEqualTo(reference.terminal)
            CardAdvantageEvaluator(evaluated, Long.MAX_VALUE)
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

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
                    purchaseStep = PurchaseStep.SHIPPING,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }

    companion object {
        private val job =
            CardAdvantageEvaluationJob(0, 71, 0, "candidate", "candidate", List(4) { "rival" })
    }
}
