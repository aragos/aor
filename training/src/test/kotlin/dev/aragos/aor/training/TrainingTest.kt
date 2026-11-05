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
    fun focusedProfileUsesTheReviewedFourPlayerConfiguration() {
        val options = TrainingOptions.parse(listOf("--profile", "strategic-four-player"))

        assertThat(options.configurations)
            .containsExactly(
                TrainingConfiguration(
                    playerCount = 4,
                    rules =
                        RuleOptions(
                            deckModification = true,
                            initialCard = InitialCardRule.NO_CRUSADES,
                            immediateMasterArtDiscard = true,
                            map = MapRule.STANDARD,
                        ),
                )
            )
    }

    @Test
    fun focusedRoundsRepeatDealsRatherThanSelectingOnOnePair() {
        val options =
            TrainingOptions.parse(listOf("--profile", "strategic-four-player", "--seeds", "256"))

        assertThat(options.roundConfigurations(3)).containsExactlyElementsIn(options.configurations)
        assertThat(options.roundSeeds).isEqualTo(16)
        assertThat(options.evaluationSeeds).isEqualTo(256)
    }

    @Test
    fun evaluationReserveUsesMeasuredGamesAndAvailableWorkers() {
        val reserve = evaluationReserveMillis(20_000.0, pairs = 256, workers = 4)

        assertThat(reserve).isEqualTo(3_840_000L)
    }

    @Test
    fun shortEvaluationEstimatesRetainAMinimumAllowance() {
        assertThat(evaluationReserveMillis(10.0, pairs = 256, workers = 4)).isEqualTo(600_000L)
    }

    @Test
    fun fullRoundsRetainTheExistingSixteenConfigurationBatch() {
        val options = TrainingOptions.parse(emptyList())

        assertThat(options.roundConfigurations(0)).hasSize(16)
        assertThat(options.roundSeeds).isEqualTo(1)
        assertThat(options.evaluationSeeds).isEqualTo(2)
    }

    @Test
    fun focusedProfileCannotSelectConfigurationsOutsideItsScope() {
        assertFailsWith<IllegalArgumentException> {
            TrainingOptions.parse(
                listOf("--profile", "strategic-four-player", "--configurations", "2")
            )
        }
    }

    @Test
    fun resumeSettingsDistinguishTheProfileFromAnEquallySizedMatrixPrefix() {
        val focused = TrainingOptions.parse(listOf("--profile", "strategic-four-player"))
        val prefix = TrainingOptions.parse(listOf("--configurations", "1"))

        assertThat(focused.signatureSettings).isNotEqualTo(prefix.signatureSettings)
        assertThat(focused.signatureSettings)
            .contains("players-4/standard/no-crusades/deck-true/art-true")
    }

    @Test
    fun focusedCoverageCanCompleteWithoutClaimingOtherVariantCoverage() {
        val ledger = CoverageLedger(TrainingProfile.STRATEGIC_FOUR_PLAYER)
        val configuration = TrainingProfile.STRATEGIC_FOUR_PLAYER.configurations.single()
        ledger.add(completeCoverageGame(configuration))

        assertThat(ledger.complete).isTrue()
        assertThat(ledger.report()["requiredConfigurations"]).isEqualTo(1)
        assertThat(ledger.report()["profile"]).isEqualTo("strategic-four-player")
        assertThat(ledger.missingConfigurations).isEmpty()
    }

    @Test
    fun completingOneFullMatrixConfigurationCannotPromoteTheFullProfile() {
        val ledger = CoverageLedger()
        ledger.add(completeCoverageGame(TrainingMatrix.stratified.first()))

        assertThat(ledger.complete).isFalse()
        assertThat(ledger.missingConfigurations).hasSize(127)
    }

    @Test
    fun focusedCoverageCannotResumeAsTheFullMatrix() {
        val report = CoverageLedger(TrainingProfile.STRATEGIC_FOUR_PLAYER).report()
        val root = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(report)

        assertFailsWith<IllegalArgumentException> { CoverageLedger().restore(root) }
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
    fun focusedProfileCompletesAndReplaysWithMixedDifficulties() {
        val configuration = TrainingProfile.STRATEGIC_FOUR_PLAYER.configurations.single()
        val difficulties = listOf(BotDifficulty.EASY, BotDifficulty.NORMAL, BotDifficulty.HARD)

        val result =
            Simulation()
                .play(
                    configuration = configuration,
                    seed = 19790531,
                    competitors =
                        List(4) { seat ->
                            Competitor(BotModels.default, difficulties[seat % difficulties.size])
                        },
                    verifyReplay = true,
                )

        assertThat(result.scores).hasSize(4)
        assertThat(result.scores.any { it.winner }).isTrue()
        assertThat(result.coverage.epochs).containsExactly(1, 2, 3)
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
    fun sparseModelsRetainNewDefaultFeaturesLikeBrowserInference() {
        val directory = Files.createTempDirectory("aor-training-sparse-model-")
        try {
            val path = directory.resolve("model.json")
            ObjectMapper()
                .writeValue(
                    path.toFile(),
                    mapOf("schemaVersion" to 1, "weights" to mapOf("cash" to 1.25)),
                )

            val model = TrainingArtifacts.readModel(path)

            assertThat(model.weights["cash"]).isEqualTo(1.25)
            assertThat(model.weights["income"]).isEqualTo(BotModels.default.weights["income"])
            assertThat(model.weights.keys).containsAtLeastElementsIn(BotModels.default.weights.keys)
        } finally {
            Files.deleteIfExists(directory.resolve("model.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun modelsForAnotherCatalogCannotBeUsedAsTheTrainingBaseline() {
        val directory = Files.createTempDirectory("aor-training-obsolete-model-")
        try {
            val path = directory.resolve("model.json")
            ObjectMapper()
                .writeValue(
                    path.toFile(),
                    mapOf(
                        "schemaVersion" to 1,
                        "dataVersion" to "obsolete-catalog",
                        "weights" to mapOf("cash" to 1.25),
                    ),
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TrainingArtifacts.readModel(path)
                }

            assertThat(failure).hasMessageThat().contains("different data version")
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

    private fun completeCoverageGame(configuration: TrainingConfiguration): SimulatedGame =
        SimulatedGame(
            configuration = configuration.id,
            seed = 7,
            steps = 1,
            scores = emptyList(),
            coverage =
                GameCoverage(
                    decisions = CoverageLedger.requiredDecisions.associateWith { 1 },
                    families = emptyMap(),
                    playedCards = CardData.all.map(CardDefinition::id).toSet(),
                    capitals = emptySet(),
                    epochs = setOf(1, 2, 3),
                    features = emptySet(),
                ),
            elapsedMillis = 1,
        )
}
