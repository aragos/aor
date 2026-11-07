package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardActorEvaluationTest {
    @Test
    fun registryBindsTheExactActorBytesAndConditionalVersion() {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            val legacy = fixture.evaluation(listOf(job))
            val input = evaluation(fixture)
            val policy = requireNotNull(input.policies["candidate"]) { "The actor is registered." }

            assertThat(policy.cardActor)
                .isEqualTo(CardPolicyInput.read(fixture.directory.resolve("actor.json")))
            assertThat(policy.sha256)
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("actor.json")))
            assertThat(policy.controller(input.environment))
                .isInstanceOf(CardActorController::class.java)
            assertThat(policy.metadata().keys).containsExactly("kind", "path", "sha256")
            assertThat(input.manifest()["cardActorVersion"]).isEqualTo(CardPolicy.ACTOR_VERSION)
            assertThat(legacy.manifest()).doesNotContainKey("cardActorVersion")
        }
    }

    @Test
    fun dictionaryMismatchIsRejectedEvenWhenNumericalShapeIsValid() {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            val path = fixture.directory.resolve("actor.json")
            val model = PurchaseJson.read(path) as ObjectNode
            val names = model.required("stateFeatureNames") as ArrayNode
            names.set(names.size() - 1, PurchaseJson.mapper.valueToTree<JsonNode>("zzz.unknown"))
            PurchaseJson.mapper.writeValue(path.toFile(), model)

            assertFailsWith<IllegalArgumentException> { evaluation(fixture) }
        }
    }

    @Test
    fun purchaseActorCannotBeLoadedAsACardActor() {
        CardAdvantageFixture().use { fixture ->
            val model = PurchaseJson.read(fixture.directory.resolve("purchase.json"))
            fixture.artifacts.write("actor.json", model)

            assertFailsWith<IllegalArgumentException> { evaluation(fixture) }
        }
    }

    @Test
    fun changedActorBytesInvalidateItsEvaluationIdentity() {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            val before = evaluation(fixture)
            val path = fixture.directory.resolve("actor.json")
            val changed = PurchaseJson.read(path) as ObjectNode
            changed.put("valueBias", 0.125)
            PurchaseJson.mapper.writeValue(path.toFile(), changed)
            val after = CardAdvantageEvaluationInput.read(before.source)

            assertThat(after.signature()).isNotEqualTo(before.signature())
            assertThat(after.policies["candidate"]?.sha256)
                .isNotEqualTo(before.policies["candidate"]?.sha256)
        }
    }

    @Test
    fun initialActorStillSamplesAlternativesInOrdinaryCardPlay() {
        assertSamplerParity(Stage.PLAY_CARDS)
    }

    @Test
    fun initialActorStillSamplesAlternativesInFinalCardPlay() {
        assertSamplerParity(Stage.FINAL_CARD_PLAY)
    }

    @Test
    fun registryActorPreservesPurchaseSamplingAndLeavesNestedChoicesToF() {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            val input = evaluation(fixture)
            val controller =
                requireNotNull(input.policies["candidate"]) { "The actor is registered." }
                    .controller(input.environment)
            val purchase =
                PurchaseActorController(
                    input.environment.baseline.model,
                    input.environment.purchase.model,
                )
            listOf(PurchaseStep.SHIPPING, PurchaseStep.ADVANCES).forEach { step ->
                val state =
                    state(Stage.PLAY_CARDS)
                        .copy(
                            stage = Stage.PURCHASE,
                            flow =
                                TurnFlow(
                                    actor = PlayerId("p1"),
                                    purchaseStep = step,
                                    phaseStarted = true,
                                    phaseBookkeepingApplied = true,
                                ),
                        )
                val observation = BotObservations.from(state, PlayerId("p1"))
                listOf(0, 71, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { seed ->
                    val decision =
                        SimulationDecision(
                            10,
                            0,
                            seed,
                            state.viewForPlayer(PlayerId("p1")),
                            observation,
                        )
                    assertThat(controller.choose(decision)).isEqualTo(purchase.choose(decision))
                }
            }
            listOf("buy-tokens", "expand", "prepare-war", "discard-card", "stabilization").forEach {
                assertThat(controller.controls(it)).isFalse()
            }
        }
    }

    @Test
    fun fullGameEvaluationMatchesTheUnchangedOnPolicyCollector() {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            fixture.artifacts.write(
                "card-collection.json",
                mapOf(
                    "schemaVersion" to 1,
                    "configuration" to fixture.configuration.id,
                    "games" to 1,
                    "seed" to 71,
                    "baseline" to "baseline.json",
                    "opponents" to listOf("baseline.json"),
                    "frozenPurchasePolicy" to "purchase.json",
                    "policy" to "actor.json",
                ),
            )
            val collection =
                CardCollectionInput.read(fixture.directory.resolve("card-collection.json"))
            val evaluated =
                evaluation(fixture, job.copy(seed = collection.seeds.single(), seat = 0))

            val reference = CardCollector(collection, "fixture", Long.MAX_VALUE).collect(0)
            val actual = CardAdvantageEvaluator(evaluated, Long.MAX_VALUE).evaluate(0)

            assertThat(reference.decisions).isNotEmpty()
            assertThat(actual.terminal).isEqualTo(reference.terminal)
            CardAdvantageEvaluator(evaluated, Long.MAX_VALUE)
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

    private fun assertSamplerParity(stage: Stage) {
        CardAdvantageFixture().use { fixture ->
            actor(fixture)
            val input = evaluation(fixture)
            val policy = requireNotNull(input.policies["candidate"]) { "The actor is registered." }
            val model = requireNotNull(policy.cardActor) { "This registry entry is a card actor." }
            val state = state(stage)
            val owner = PlayerId("p1")
            val observation = BotObservations.from(state, owner)
            val view = state.viewForPlayer(owner)
            val controller = policy.controller(input.environment)
            val examples =
                (0..1023).asSequence().map { seed ->
                    val baseline =
                        BotPolicy.choose(
                            observation,
                            BotDifficulty.HARD,
                            seed,
                            input.environment.baseline.model,
                        )
                    val encoded = CardObservationEncoder.from(view, owner, observation, baseline)
                    val choice = CardPolicy.choose(encoded, model, CardPolicy.actorSeed(seed))
                    Triple(seed, baseline, encoded.actions[choice.index].command)
                }
            val alternate =
                requireNotNull(examples.firstOrNull { it.second != it.third }) {
                    "The fixed seed range must include a nonbaseline initial-actor choice."
                }
            val decision = SimulationDecision(10, 0, alternate.first, view, observation)

            assertThat(controller.controls("play-cards")).isTrue()
            assertThat(controller.choose(decision)).isEqualTo(alternate.third)
            assertThat(controller.choose(decision)).isNotEqualTo(alternate.second)
        }
    }

    private fun actor(fixture: CardAdvantageFixture) {
        val dictionary = fixture.dictionary
        fixture.artifacts.write(
            "actor.json",
            mapOf(
                "schemaVersion" to 1,
                "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
                "hiddenSize" to 64,
                "baselineProbability" to .97,
                "stateFeatureNames" to dictionary.stateFeatureNames,
                "actionFeatureNames" to dictionary.actionFeatureNames,
                "stateWeights" to List(64 * dictionary.stateFeatureNames.size) { 0.0 },
                "stateBias" to List(64) { 0.0 },
                "actionWeights" to List(64 * dictionary.actionFeatureNames.size) { 0.0 },
                "actionBias" to List(64) { 0.0 },
                "actorWeights" to List(64) { 0.0 },
                "valueWeights" to List(64) { 0.0 },
                "valueBias" to 0.0,
            ),
        )
    }

    private fun evaluation(
        fixture: CardAdvantageFixture,
        selectedJob: CardAdvantageEvaluationJob = job,
    ): CardAdvantageEvaluationInput {
        val original = fixture.evaluation(listOf(selectedJob))
        val source = PurchaseJson.read(original.source) as ObjectNode
        (source.required("policies") as ObjectNode).set<JsonNode>(
            "candidate",
            PurchaseJson.mapper.valueToTree(mapOf("kind" to "card-actor", "path" to "actor.json")),
        )
        PurchaseJson.mapper.writeValue(original.source.toFile(), source)
        return CardAdvantageEvaluationInput.read(original.source)
    }

    private fun state(stage: Stage): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, players.first().id),
            stage = stage,
            turnNumber = 2,
            endingTriggered = stage == Stage.FINAL_CARD_PLAY,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = 100,
                                hand =
                                    if (index == 0)
                                        listOf(
                                            HandCard("a", CardData.definition("charlemagne")),
                                            HandCard("b", CardData.definition("rashid-ad-din")),
                                        )
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = players.first().id,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }

    companion object {
        private val job =
            CardAdvantageEvaluationJob(0, 71, 0, "candidate", "candidate", List(4) { "baseline" })
    }
}
