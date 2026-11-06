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
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseOpponentPolicyTest {
    @Test
    fun absentOpponentPolicyKeepsTheLegacyManifestShape() {
        PurchaseCollectionFixture().use { fixture ->
            val input = fixture.input
            fixture.store(input)

            assertThat(input.opponentPurchasePolicy).isNull()
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("collection.json"))
                        .has("opponentPurchasePolicy")
                )
                .isFalse()
            assertThat(Files.exists(fixture.output.resolve("opponent-purchase-policy.json")))
                .isFalse()
        }
    }

    @Test
    fun explicitNullOpponentPolicyKeepsEveryRecordedLegacyChoice() {
        PurchaseCollectionFixture().use { fixture ->
            val absent = PurchaseCollector(fixture.input, "fixture", Long.MAX_VALUE).collect(0)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.putNull("opponentPurchasePolicy")
            PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)

            val explicit = PurchaseCollector(fixture.input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(explicit).isEqualTo(absent)
        }
    }

    @Test
    fun rivalActorIsFrozenSeparatelyFromTheControlledBaselineMode() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = rivals(fixture)
            val actor =
                requireNotNull(input.opponentPurchasePolicy) {
                    "The fixture requires a learned opponent actor."
                }

            fixture.store(input)

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(input.mode).isEqualTo("baseline-imitation")
            assertThat(input.policy).isNull()
            assertThat(manifest["opponentPurchasePolicy"]["path"].asText())
                .isEqualTo(actor.path.toString())
            assertThat(manifest["opponentPurchasePolicy"]["sha256"].asText())
                .isEqualTo(actor.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("opponent-purchase-policy.json")))
                .isEqualTo(actor.sha256)
            assertThat(Files.exists(fixture.output.resolve("policy.json"))).isFalse()
        }
    }

    @Test
    fun matchingRivalPolicyResumeRestoresItsMissingArchive() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = rivals(fixture)
            fixture.store(input, input.signature())
            val manifest = Files.readAllBytes(fixture.output.resolve("collection.json"))
            Files.delete(fixture.output.resolve("opponent-purchase-policy.json"))

            fixture.store(input, input.signature())

            assertThat(Files.readAllBytes(fixture.output.resolve("collection.json")))
                .isEqualTo(manifest)
            assertThat(PurchaseJson.hash(fixture.output.resolve("opponent-purchase-policy.json")))
                .isEqualTo(
                    requireNotNull(input.opponentPurchasePolicy) {
                            "The fixture requires a learned opponent actor."
                        }
                        .sha256
                )
        }
    }

    @Test
    fun changedRivalActorCannotReuseOrRewriteAnExistingCollection() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val original = rivals(fixture)
            fixture.store(original, original.signature())
            val before = Files.readAllBytes(fixture.output.resolve("opponent-purchase-policy.json"))
            val actorPath =
                requireNotNull(original.opponentPurchasePolicy) {
                        "The fixture requires a learned opponent actor."
                    }
                    .path
            val model = PurchaseJson.read(actorPath) as ObjectNode
            model.put("valueBias", .5)
            PurchaseJson.mapper.writeValue(actorPath.toFile(), model)
            val changed = fixture.input

            assertFailsWith<IllegalArgumentException> {
                fixture.store(changed, changed.signature())
            }

            assertThat(changed.signature()).isNotEqualTo(original.signature())
            assertThat(Files.readAllBytes(fixture.output.resolve("opponent-purchase-policy.json")))
                .isEqualTo(before)
        }
    }

    @Test
    fun rivalActorRequiresTheExactConfigurationVocabulary() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val initial = rivals(fixture)
            val path =
                requireNotNull(initial.opponentPurchasePolicy) {
                        "The fixture requires a learned opponent actor."
                    }
                    .path
            val model = PurchaseJson.read(path) as ObjectNode
            val names = model["stateFeatureNames"] as ArrayNode
            names.remove(names.size() - 1)
            model.set<JsonNode>(
                "stateWeights",
                PurchaseJson.mapper.createArrayNode().also { weights ->
                    repeat(64 * names.size()) { weights.add(0.0) }
                },
            )
            PurchaseJson.mapper.writeValue(path.toFile(), model)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.input }

            assertThat(failure).hasMessageThat().contains("complete encoder dictionaries")
        }
    }

    @Test
    fun sourceRejectsAnUntypedOpponentPolicyReference() {
        PurchaseCollectionFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("opponentPurchasePolicy", true)
            PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun learnedRivalsUseSharedSamplingButOnlyTheControlledSeatIsRecorded() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = rivals(fixture, forcePass = true)
            val actor =
                requireNotNull(input.opponentPurchasePolicy) {
                    "The fixture requires a learned opponent actor."
                }
            val records =
                List(input.configuration.playerCount) { mutableListOf<PurchaseDecisionRecord>() }
            val controlledSeat = input.seat(0)
            val lineups = input.opponentIndices(0)
            val competitors = lineups.mapIndexed { seat, opponent ->
                Competitor(
                    if (seat == controlledSeat) input.baseline.model
                    else input.opponents[opponent].model,
                    BotDifficulty.HARD,
                )
            }
            val controllers =
                competitors.indices.associateWith { seat ->
                    PurchaseActorController(
                        competitors[seat].model,
                        if (seat == controlledSeat) null else actor.model,
                    ) {
                        records[seat].add(it)
                    }
                }
            val reference =
                Simulation()
                    .play(
                        input.configuration,
                        input.seeds[0],
                        competitors,
                        verifyReplay = true,
                        controllers = controllers,
                    )

            val actual = PurchaseCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal.scores)
                .isEqualTo(reference.scores.map(PilotReplayScore::from))
            assertThat(actual.terminal.gameDecisions).isEqualTo(reference.steps)
            assertThat(actual.decisions).isEqualTo(records[controlledSeat])
            val rivalRecords = records.filterIndexed { seat, _ -> seat != controlledSeat }.flatten()
            assertThat(rivalRecords).isNotEmpty()
            assertThat(rivalRecords.any { it.selectedIndex != it.observation.baselineIndex })
                .isTrue()
            assertThat(
                    rivalRecords.all { it.observation.actions[it.selectedIndex].command == "pass" }
                )
                .isTrue()
            assertThat(actual.decisions.all { it.behaviorLogProbability == null }).isTrue()
            PurchaseTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

    @Test
    fun opponentControllerUsesOnlyItsOwnHandAndPublicFacts() {
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona")
        val actor = players[1].id
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
                            player.id to
                                PlayerState(
                                    player,
                                    capital = capitals[index],
                                    cash = 100,
                                    hand =
                                        when (index) {
                                            0 ->
                                                listOf(
                                                    HandCard(
                                                        "private",
                                                        CardData.definition("charlemagne"),
                                                    )
                                                )
                                            1 ->
                                                listOf(
                                                    HandCard(
                                                        "own",
                                                        CardData.definition("rashid-ad-din"),
                                                    )
                                                )
                                            else -> emptyList()
                                        },
                                )
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
        val other = state.playerState(players[0].id)
        val changed =
            state.copy(
                players =
                    state.players +
                        (other.player.id to
                            other.copy(
                                hand =
                                    listOf(HandCard("private", CardData.definition("marco-polo")))
                            ))
            )
        val recorded = mutableListOf<PurchaseDecisionRecord>()
        val controller = PurchaseActorController(BotModels.default, null) { recorded.add(it) }
        fun decision(current: GameState) =
            SimulationDecision(
                number = 10,
                seat = 1,
                seed = 71,
                view = current.viewForPlayer(actor),
                observation = BotObservations.from(current, actor),
            )

        controller.choose(decision(state))
        controller.choose(decision(changed))

        assertThat(recorded[1].observation).isEqualTo(recorded[0].observation)
        assertThat(recorded[0].observation.stateFeatures).containsEntry("hand.rashid-ad-din", 1.0)
        assertThat(recorded[0].observation.stateFeatures).doesNotContainKey("hand.charlemagne")
    }

    @Test
    fun opponentControllerLeavesSealedAndNonpurchaseDecisionsWithTheBaseline() {
        val controller = PurchaseActorController(BotModels.default, null)

        assertThat(controller.controls("purchase-shipping")).isTrue()
        assertThat(controller.controls("purchase-advances")).isTrue()
        assertThat(controller.controls("buy-tokens")).isFalse()
        assertThat(controller.controls("play-cards")).isFalse()
        assertThat(controller.controls("stabilization")).isFalse()
    }

    private fun rivals(
        fixture: PurchaseCollectionFixture,
        forcePass: Boolean = false,
    ): PurchaseCollectionInput {
        val path =
            requireNotNull(fixture.input.policyPath) { "The fixture requires its policy file." }
        if (forcePass) {
            val model = PurchaseJson.read(path) as ObjectNode
            val features = model["actionFeatureNames"].map { it.asText() }
            (model["actionWeights"] as ArrayNode).set(
                features.indexOf("kind.pass"),
                PurchaseJson.mapper.nodeFactory.numberNode(8.0),
            )
            (model["actionBias"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.numberNode(-4.0),
            )
            (model["actorWeights"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.numberNode(30.0),
            )
            PurchaseJson.mapper.writeValue(path.toFile(), model)
        }
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.putNull("policy")
        source.put("opponentPurchasePolicy", path.fileName.toString())
        PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)
        return fixture.input
    }
}
