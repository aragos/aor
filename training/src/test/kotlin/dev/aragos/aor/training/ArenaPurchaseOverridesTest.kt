package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaPurchaseOverridesTest {
    @Test
    fun absentOverridesRetainTheExactSharedPolicyIdentityAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val baselineHash = PurchaseJson.hash(fixture.directory.resolve("baseline.json"))
            val policyHash = PurchaseJson.hash(fixture.directory.resolve("policy.json"))
            val expected = "frozen-purchase-1:$baselineHash:$policyHash"

            assertThat(fixture.frozen.identity()).isEqualTo(expected)
            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(fixture.frozen.metadata())
            assertThat(metadata["frozenPurchase"].properties().map { it.key })
                .containsExactly("baseline", "policy")
        }
    }

    @Test
    fun overrideRolesFreezeTheirOwnExactFileBytes() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()
            val frozen = fixture.frozen
            val output = TrainingArtifacts(fixture.directory.resolve("output"))

            frozen.snapshot(output)

            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(frozen.metadata())
            val roles =
                mapOf(
                    "candidatePolicy" to "candidate",
                    "incumbentPolicy" to "incumbent",
                )
            assertThat(metadata["frozenPurchase"].properties().map { it.key })
                .containsExactly("baseline", "policy", "candidatePolicy", "incumbentPolicy")
            for ((role, side) in roles) {
                val node = metadata["frozenPurchase"][role]
                val path = fixture.directory.resolve("$side-policy.json")
                assertThat(node["path"].asText()).isEqualTo(path.toRealPath().toString())
                assertThat(node["snapshot"].asText()).isEqualTo("frozen-purchase-$side-policy.json")
                assertThat(node["sha256"].asText()).isEqualTo(PurchaseJson.hash(path))
                assertThat(PurchaseJson.hash(output.directory.resolve(node["snapshot"].asText())))
                    .isEqualTo(PurchaseJson.hash(path))
            }
        }
    }

    @Test
    fun candidateActorBytesChangeTheNativeFingerprintWithoutChangingOrdinaryModels() {
        assertOverrideBindsIdentity("candidate")
    }

    @Test
    fun incumbentActorBytesChangeTheNativeFingerprintWithoutChangingOrdinaryModels() {
        assertOverrideBindsIdentity("incumbent")
    }

    @Test
    fun assigningTheSameActorToDifferentRolesChangesItsIdentity() {
        ArenaPurchaseFixture().use { fixture ->
            val candidateSource = PurchaseJson.read(fixture.source) as ObjectNode
            (candidateSource["frozenPurchase"] as ObjectNode).put("candidatePolicy", "policy.json")
            fixture.artifacts.write("arena.json", candidateSource)
            val candidateIdentity = fixture.specification.identity()
            val incumbentSource = candidateSource.deepCopy()
            val frozen = incumbentSource["frozenPurchase"] as ObjectNode
            frozen.remove("candidatePolicy")
            frozen.put("incumbentPolicy", "policy.json")
            fixture.artifacts.write("arena.json", incumbentSource)

            val incumbentIdentity = fixture.specification.identity()

            assertThat(incumbentIdentity).isNotEqualTo(candidateIdentity)
        }
    }

    @Test
    fun explicitNullOverridesAreRejected() {
        for (role in listOf("candidatePolicy", "incumbentPolicy")) {
            ArenaPurchaseFixture().use { fixture ->
                val source = PurchaseJson.read(fixture.source) as ObjectNode
                (source["frozenPurchase"] as ObjectNode).putNull(role)
                fixture.artifacts.write("arena.json", source)

                val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

                assertThat(failure).hasMessageThat().contains("$role must be nonempty text")
            }
        }
    }

    @Test
    fun overrideActorRequiresTheCompleteConfigurationDictionary() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()
            val policy =
                PurchaseJson.read(fixture.directory.resolve("candidate-policy.json")) as ObjectNode
            (policy["stateFeatureNames"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.valueToTree<JsonNode>("0.unrecognized.feature"),
            )
            fixture.artifacts.write("candidate-policy.json", policy)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("complete encoder dictionaries")
        }
    }

    @Test
    fun modelOnlySearchCannotGuessRolesFromEqualOrdinaryModels() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = fixture.withOverrides()

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    StrategyArena.game(specification, 0, specification.candidate, Long.MAX_VALUE)
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun eachSideRoutesOnlyItsEvaluatedSeatThroughItsOwnActor() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()
            val prior = TrainingArtifacts.readModel(fixture.directory.resolve("baseline.json"))
            val shared = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            for (side in ArenaSide.entries) {
                val actor =
                    PurchasePolicyInput.read(
                        fixture.directory.resolve("${side.name.lowercase()}-policy.json")
                    )
                for (seat in 0..3) {
                    val controllers = fixture.frozen.controllers(4, seat, side)
                    for (decision in games.decisions) {
                        val expected =
                            PurchaseActorController(
                                    prior,
                                    if (decision.seat == seat) actor else shared,
                                )
                                .choose(decision)
                        assertThat(
                                requireNotNull(controllers[decision.seat]) {
                                        "Every arena seat requires its frozen controller."
                                    }
                                    .choose(decision)
                            )
                            .isEqualTo(expected)
                    }
                }
            }
        }
    }

    @Test
    fun omittedIncumbentOverrideDefaultsOnlyThatSideToTheSharedActor() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["frozenPurchase"] as ObjectNode).remove("incumbentPolicy")
            fixture.artifacts.write("arena.json", source)
            val prior = TrainingArtifacts.readModel(fixture.directory.resolve("baseline.json"))
            val actor = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val controllers = fixture.frozen.controllers(4, 1, ArenaSide.INCUMBENT)

            val actual =
                games.decisions.map { decision ->
                    requireNotNull(controllers[decision.seat]) {
                            "Every arena seat requires its frozen controller."
                        }
                        .choose(decision)
                }

            assertThat(actual)
                .isEqualTo(
                    games.decisions.map {
                        PurchaseActorController(prior, actor).choose(it)
                    }
                )
        }
    }

    @Test
    fun candidateGameMatchesAnIndependentLineupWithOnlyItsPurchaseActorReplaced() {
        assertThat(games.pair.candidate.copy(elapsedMillis = 0))
            .isEqualTo(games.candidateReference.copy(elapsedMillis = 0))
        assertThat(games.pair.candidate.scores).isNotEqualTo(games.pair.incumbent.scores)
    }

    @Test
    fun incumbentGameMatchesAnIndependentLineupWithOnlyItsPurchaseActorReplaced() {
        assertThat(games.pair.incumbent.copy(elapsedMillis = 0))
            .isEqualTo(games.incumbentReference.copy(elapsedMillis = 0))
    }

    @Test
    fun candidateReplayUsesItsActorAndReproducesItsRecordedCanonicalGame() {
        assertReplay(games.candidateReplay, games.pair.candidate)
    }

    @Test
    fun incumbentReplayUsesItsActorAndReproducesItsRecordedCanonicalGame() {
        assertReplay(games.incumbentReplay, games.pair.incumbent)
    }

    private fun assertReplay(replay: StrategyReplay, game: ArenaGame) {
        assertThat(replay.game.copy(elapsedMillis = 0)).isEqualTo(game.copy(elapsedMillis = 0))
        assertThat(replay.replayVerified).isTrue()
        assertThat(replay.historicalPairVerified).isTrue()
    }

    private fun assertOverrideBindsIdentity(side: String) {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.withOverrides()
            val file = fixture.directory.resolve("$side-policy.json")
            Files.writeString(file, Files.readString(file) + "\n")

            val changed = fixture.specification

            assertThat(changed.candidate).isEqualTo(original.candidate)
            assertThat(changed.incumbent).isEqualTo(original.incumbent)
            assertThat(changed.identity()).isNotEqualTo(original.identity())
            assertThat(trainingSignature("arena-1:${changed.identity()}"))
                .isNotEqualTo(trainingSignature("arena-1:${original.identity()}"))
        }
    }

    private data class Games(
        val pair: ArenaPair,
        val candidateReference: ArenaGame,
        val incumbentReference: ArenaGame,
        val candidateReplay: StrategyReplay,
        val incumbentReplay: StrategyReplay,
        val decisions: List<SimulationDecision>,
    )

    companion object {
        private val games by lazy {
            ArenaPurchaseFixture().use { fixture ->
                val specification = fixture.withOverrides()
                val index = 1
                val lineup = StrategyArena.lineup(specification, index)
                val prior = TrainingArtifacts.readModel(fixture.directory.resolve("baseline.json"))
                val shared = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
                val decisions = mutableListOf<SimulationDecision>()
                fun reference(side: String): ArenaGame {
                    val actor =
                        PurchasePolicyInput.read(fixture.directory.resolve("$side-policy.json"))
                    return ArenaGame.from(
                        Simulation()
                            .play(
                                configuration = lineup.configuration,
                                seed = lineup.seed,
                                competitors =
                                    List(4) {
                                        Competitor(specification.candidate, BotDifficulty.HARD)
                                    },
                                controllers =
                                    (0..3).associateWith { seat ->
                                        val controller =
                                            PurchaseActorController(
                                                prior,
                                                if (seat == lineup.seat) actor else shared,
                                            )
                                        object : SimulationController {
                                            override fun controls(decision: String): Boolean =
                                                controller.controls(decision)

                                            override fun choose(
                                                decision: SimulationDecision
                                            ): String {
                                                decisions.add(decision)
                                                return controller.choose(decision)
                                            }
                                        }
                                    },
                            )
                    )
                }
                val pair = StrategyArena.play(specification, index, Long.MAX_VALUE)
                fixture.artifacts.write("pair.json", pair)
                val recorded = StrategyReplayPair.read(fixture.directory.resolve("pair.json"))
                Games(
                    pair,
                    reference("candidate"),
                    reference("incumbent"),
                    StrategyReplays.play(
                        specification,
                        index,
                        StrategyReplaySide.CANDIDATE,
                        pair = recorded,
                    ),
                    StrategyReplays.play(
                        specification,
                        index,
                        StrategyReplaySide.INCUMBENT,
                        pair = recorded,
                    ),
                    decisions.toList(),
                )
            }
        }
    }
}
