package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaPurchasePolicyTest {
    @Test
    fun absentPurchaseEnvironmentPreservesTheLegacyIdentity() {
        Fixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.remove("frozenPurchase")
            fixture.artifacts.write("arena.json", source)

            val specification = fixture.specification

            assertThat(specification.frozenPurchase).isNull()
            assertThat(specification.identity())
                .isEqualTo(
                    listOf(
                            specification.purpose,
                            specification.configurations.map { it.id },
                            specification.pairsPerConfiguration,
                            specification.seed,
                            specification.candidate.weights.toSortedMap(),
                            specification.incumbent.weights.toSortedMap(),
                            specification.opponents.map { it.weights.toSortedMap() },
                        )
                        .joinToString("\n")
                )
        }
    }

    @Test
    fun policyFileBytesBindTheArenaAndInheritedSearchIdentity() {
        Fixture().use { fixture ->
            val before = fixture.specification
            val path = fixture.directory.resolve("policy.json")
            Files.writeString(path, Files.readString(path) + "\n")

            val changed = fixture.specification

            assertThat(changed.identity()).isNotEqualTo(before.identity())
            assertThat(fixture.search(changed).identity())
                .isNotEqualTo(fixture.search(before).identity())
        }
    }

    @Test
    fun purchasePriorBytesBindTheArenaIdentityIndependentlyOfCandidateWeights() {
        Fixture().use { fixture ->
            val before = fixture.specification
            val path = fixture.directory.resolve("baseline.json")
            Files.writeString(path, Files.readString(path) + "\n")

            val changed = fixture.specification

            assertThat(changed.candidate).isEqualTo(before.candidate)
            assertThat(changed.identity()).isNotEqualTo(before.identity())
        }
    }

    @Test
    fun purchaseEnvironmentRejectsUnsupportedConfigurations() {
        Fixture().use { fixture ->
            val specification = fixture.specification

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    specification.copy(configurations = listOf(TrainingMatrix.stratified.first()))
                }

            assertThat(failure).hasMessageThat().contains("requires players-4/standard/no-crusades")
        }
    }

    @Test
    fun purchaseEnvironmentRejectsAChangedDictionary() {
        Fixture().use { fixture ->
            val policy = PurchaseJson.read(fixture.directory.resolve("policy.json")) as ObjectNode
            (policy["stateFeatureNames"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.valueToTree<JsonNode>("0.unrecognized.feature"),
            )
            fixture.artifacts.write("policy.json", policy)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("complete encoder dictionaries")
        }
    }

    @Test
    fun snapshotsRestoreMissingEvidenceAndRecordExactFileHashes() {
        Fixture().use { fixture ->
            val frozen = fixture.frozen
            val output = TrainingArtifacts(fixture.directory.resolve("output"))
            frozen.snapshot(output)
            Files.delete(output.directory.resolve("frozen-purchase-policy.json"))

            frozen.snapshot(output)

            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(frozen.metadata())
            for (role in listOf("baseline", "policy")) {
                val node = metadata["frozenPurchase"][role]
                assertThat(PurchaseJson.hash(output.directory.resolve(node["snapshot"].asText())))
                    .isEqualTo(node["sha256"].asText())
                assertThat(node["sha256"].asText())
                    .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("$role.json")))
            }
        }
    }

    @Test
    fun changedSnapshotCannotOverwriteExistingEvidence() {
        Fixture().use { fixture ->
            val output = TrainingArtifacts(fixture.directory.resolve("output"))
            val path = output.directory.resolve("frozen-purchase-policy.json")
            Files.writeString(path, "different evidence")

            assertFailsWith<IllegalArgumentException> { fixture.frozen.snapshot(output) }

            assertThat(Files.readString(path)).isEqualTo("different evidence")
        }
    }

    @Test
    fun arenaUsesTheFrozenLearnedPurchaseControllerInEverySeat() {
        assertThat(games.reference.copy(elapsedMillis = 0))
            .isEqualTo(games.arena.copy(elapsedMillis = 0))
        assertThat(games.purchaseSeats).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun changedCandidatePurchaseWeightsCannotChangeTheFrozenPrior() {
        assertThat(games.changedPriorDecisions).isGreaterThan(0)
        assertThat(games.mutated.copy(elapsedMillis = 0))
            .isEqualTo(games.arena.copy(elapsedMillis = 0))
    }

    @Test
    fun zeroCandidatePreservesActualFrozenEnvironmentSimulationResults() {
        assertThat(games.zero.copy(elapsedMillis = 0))
            .isEqualTo(games.arena.copy(elapsedMillis = 0))
    }

    @Test
    fun replayPreservesTheFrozenPurchaseEnvironmentAndVerifiesItsEntries() {
        assertThat(games.replay.game.copy(elapsedMillis = 0))
            .isEqualTo(games.arena.copy(elapsedMillis = 0))
        assertThat(games.replay.replayVerified).isTrue()
    }

    @Test
    fun tokenCollectionRejectsAnUnsupportedFrozenPurchaseEnvironment() {
        Fixture().use { fixture ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenValueSpecification.read(fixture.source)
                }

            assertThat(failure)
                .hasMessageThat()
                .contains("does not support an arena frozenPurchase")
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("aor-arena-purchase-")
        val artifacts = TrainingArtifacts(directory)
        val source = directory.resolve("arena.json")
        val configuration =
            TrainingMatrix.configurations.single {
                it.id == "players-4/standard/no-crusades/deck-true/art-true"
            }

        init {
            artifacts.model(BotModels.default, "baseline.json")
            val dictionary =
                PurchaseObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            artifacts.write(
                "policy.json",
                mapOf(
                    "schemaVersion" to 1,
                    "encoderVersion" to PurchaseObservationEncoder.ENCODER_VERSION,
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
            artifacts.write(
                "arena.json",
                mapOf(
                    "schemaVersion" to 1,
                    "purpose" to "SCREENING",
                    "configurations" to listOf(configuration.id),
                    "candidate" to null,
                    "incumbent" to null,
                    "opponents" to listOf(null),
                    "pairsPerConfiguration" to 1,
                    "seed" to 71,
                    "frozenPurchase" to
                        mapOf("baseline" to "baseline.json", "policy" to "policy.json"),
                ),
            )
        }

        val specification: ArenaSpecification
            get() = ArenaSpecification.read(source)

        val frozen: ArenaPurchasePolicy
            get() =
                requireNotNull(specification.frozenPurchase) { "The fixture requires its actor." }

        fun search(arena: ArenaSpecification): StrategySearchSpecification =
            StrategySearchSpecification(
                arena,
                listOf(StrategySearchParameter("planning.navigationReplacement", -1.0, 1.0)),
            )

        override fun close() {
            directory.toFile().deleteRecursively()
        }
    }

    private data class Games(
        val reference: ArenaGame,
        val arena: ArenaGame,
        val mutated: ArenaGame,
        val zero: ArenaGame,
        val replay: StrategyReplay,
        val purchaseSeats: Set<Int>,
        val changedPriorDecisions: Int,
    )

    companion object {
        private val games by lazy {
            Fixture().use { fixture ->
                val specification = fixture.specification
                val mutated =
                    BotModels.default.copy(
                        weights =
                            BotModels.default.weights +
                                AdvanceId.entries.associate {
                                    "advance.${it.letter}" to 1_000_000.0
                                }
                    )
                val decisions = mutableListOf<SimulationDecision>()
                val baseline =
                    TrainingArtifacts.readModel(fixture.directory.resolve("baseline.json"))
                val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
                val reference =
                    Simulation()
                        .play(
                            fixture.configuration,
                            StrategyArena.seed(specification.seed, 0),
                            List(4) { Competitor(BotModels.default, BotDifficulty.HARD) },
                            verifyReplay = true,
                            controllers =
                                (0..3).associateWith {
                                    val actor = PurchaseActorController(baseline, policy)
                                    object : SimulationController {
                                        override fun controls(decision: String): Boolean =
                                            actor.controls(decision)

                                        override fun choose(decision: SimulationDecision): String {
                                            decisions.add(decision)
                                            return actor.choose(decision)
                                        }
                                    }
                                },
                        )
                val zero = StrategySearch.candidates(fixture.search(specification)).first().model
                Games(
                    ArenaGame.from(reference),
                    StrategyArena.game(specification, 0, specification.candidate, Long.MAX_VALUE),
                    StrategyArena.game(specification, 0, mutated, Long.MAX_VALUE),
                    StrategyArena.game(specification, 0, zero, Long.MAX_VALUE),
                    StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE),
                    decisions.map { it.seat }.toSet(),
                    decisions.count {
                        BotPolicy.choose(it.observation, BotDifficulty.HARD, it.seed, baseline) !=
                            BotPolicy.choose(it.observation, BotDifficulty.HARD, it.seed, mutated)
                    },
                )
            }
        }
    }
}
