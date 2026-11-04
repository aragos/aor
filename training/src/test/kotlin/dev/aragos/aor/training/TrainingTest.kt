package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.CardData
import dev.aragos.aor.CardDefinition
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrainingTest {
    @Test
    fun matrixIncludesEveryPlayerCountAndVariantCombinationExactlyOnce() {
        val configurations = TrainingMatrix.configurations
        assertThat(configurations).hasSize(128)
        assertThat(configurations.map { it.id }.distinct()).hasSize(128)
        for (count in 3..6) {
            for (map in MapRule.entries) {
                for (initial in InitialCardRule.entries) {
                    for (deck in listOf(false, true)) {
                        for (art in listOf(false, true)) {
                            assertThat(configurations)
                                .contains(
                                    TrainingConfiguration(
                                        count,
                                        RuleOptions(deck, initial, art, map),
                                    )
                                )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun shortRunsInterleaveAllCountsAndBothMaps() {
        val firstEight = TrainingMatrix.stratified.take(8)
        assertThat(firstEight.map { it.playerCount }.toSet()).containsExactly(3, 4, 5, 6)
        assertThat(firstEight.map { it.rules.map }.toSet())
            .containsExactly(MapRule.STANDARD, MapRule.EUROPEAN)
    }

    @Test
    fun completeSeededGameReplaysAndConservesObservableCoverage() {
        val result =
            Simulation()
                .play(
                    configuration = TrainingMatrix.stratified.first(),
                    seed = 19790531,
                    competitors = List(3) { Competitor(BotModels.default, BotDifficulty.NORMAL) },
                    verifyReplay = true,
                )
        assertThat(result.scores).hasSize(3)
        assertThat(result.scores.any { it.winner }).isTrue()
        assertThat(result.coverage.epochs).containsExactly(1, 2, 3)
        assertThat(result.coverage.capitals).hasSize(3)
        assertThat(result.coverage.decisions.keys)
            .containsAtLeast(
                "bid-capital",
                "choose-capital",
                "buy-tokens",
                "expand",
                "purchase-advances",
            )
        assertThat(CardData.all.map(CardDefinition::id))
            .containsAtLeastElementsIn(result.coverage.playedCards)
    }

    @Test
    fun reportedSixPlayerTrainingSeedCompletesAndReplays() {
        val configuration =
            TrainingConfiguration(
                playerCount = 6,
                rules =
                    RuleOptions(
                        deckModification = false,
                        initialCard = InitialCardRule.NO_EVENTS,
                        immediateMasterArtDiscard = true,
                        map = MapRule.EUROPEAN,
                    ),
            )

        val result =
            Simulation()
                .play(
                    configuration = configuration,
                    seed = 2101805946,
                    competitors = List(6) { Competitor(BotModels.default) },
                    verifyReplay = true,
                )

        assertThat(result.scores).hasSize(6)
        assertThat(result.coverage.playedCards).contains("war")
    }

    @Test
    fun elapsedDeadlineStopsBeforeAnotherPolicyDecision() {
        assertFailsWith<CancellationException> {
            Simulation()
                .play(
                    configuration = TrainingMatrix.stratified.first(),
                    seed = 1,
                    competitors = List(3) { Competitor(BotModels.default) },
                    deadlineNanos = System.nanoTime() - 1,
                )
        }
    }

    @Test
    fun modelArtifactsRoundTripWithCompatibilityMetadata() {
        val directory = Files.createTempDirectory("aor-training-model-")
        try {
            val artifacts = TrainingArtifacts(directory)
            artifacts.model(BotModels.default)
            assertThat(TrainingArtifacts.readModel(directory.resolve("model.json")))
                .isEqualTo(BotModels.default)
        } finally {
            Files.deleteIfExists(directory.resolve("model.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun explorationPreservesCatastrophePenaltyAndIsSeedReproducible() {
        val features = BotModels.default.weights.keys + "count.6.income"
        val first = ModelExploration.propose(BotModels.default, features, Random(77), 0.2)
        val second = ModelExploration.propose(BotModels.default, features, Random(77), 0.2)
        assertThat(first).isEqualTo(second)
        assertThat(first.weights["chaos"]).isEqualTo(BotModels.default.weights["chaos"])
    }

    @Test
    fun rareDecisionCurriculumIsLegalForEveryPlayerCountAndVariantCombination() {
        val ledger = CoverageLedger()
        val probes = DecisionProbes(TrainingMatrix.configurations, ledger)

        assertThat(probes.observations.map { it.decision }.toSet())
            .containsAtLeast(
                "prepare-war",
                "replace-event-token",
                "place-event-marker",
                "war-surrender",
                "cathedral",
                "windmill",
                "renaissance",
                "civil-war-loss",
                "immediate-master-art-discard",
            )
        assertThat(ledger.report()["probedConfigurations"]).isEqualTo(128)
        assertThat(ledger.report()["observedConfigurations"]).isEqualTo(0)
        assertThat(probes.regret(BotModels.default).isFinite()).isTrue()
    }

    @Test
    fun checkpointResumeRetainsOriginalBaselineAndPopulation() {
        val directory = Files.createTempDirectory("aor-training-resume-")
        try {
            val artifacts = TrainingArtifacts(directory)
            val champion =
                BotModels.default.copy(weights = BotModels.default.weights + ("cash" to 1.1))
            val checkpoint =
                TrainingCheckpoint(
                    signature = "test-signature",
                    initial = BotModels.default,
                    champion = champion,
                    population = listOf(BotModels.default, champion),
                    nextRound = 9,
                    warmupComplete = true,
                )
            checkpoint.save(artifacts, CoverageLedger())
            val root = ObjectMapper().readTree(directory.resolve("state.json").toFile())

            val restored = TrainingCheckpoint.restore(root, "test-signature", CoverageLedger())

            assertThat(restored).isEqualTo(checkpoint)
            assertThat(restored.initial).isNotEqualTo(restored.champion)
            assertFailsWith<IllegalArgumentException> {
                TrainingCheckpoint.restore(root, "different-policy", CoverageLedger())
            }
        } finally {
            Files.deleteIfExists(directory.resolve("state.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun finalEvaluationSeedsStayDisjointEvenWhenExplorationOffsetsWrap() {
        for (offset in listOf(0, 100_000_000, 1_000_000_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val training = tournamentSeed(offset, 127, 999, heldOut = false)
            val heldOut = tournamentSeed(offset, 127, 999, heldOut = true)

            assertThat(training).isAtLeast(0)
            assertThat(training).isLessThan(0x40000000)
            assertThat(heldOut).isAtLeast(0x40000000)
            assertThat(heldOut).isNotEqualTo(training)
        }
    }
}
