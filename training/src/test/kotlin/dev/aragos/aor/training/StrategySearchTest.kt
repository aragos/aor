package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonProcessingException
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategySearchTest {
    @Test
    fun linkedWeightsFollowTheSampleInCandidateValuesAndModels() {
        val specification =
            specification()
                .copy(
                    parameters = listOf(linkedParameter()),
                    candidates = 6,
                )

        val candidates = StrategySearch.candidates(specification)

        for (candidate in candidates) {
            val primary =
                checkNotNull(candidate.values["planning.test"]) {
                    "A linked candidate requires its sampled primary weight."
                }
            val expected = if (primary == 0.0) 0.0 else primary * -0.8
            assertThat(candidate.values.keys).containsExactly("planning.test", "planning.linked")
            assertThat(candidate.values["planning.linked"]).isEqualTo(expected)
            assertThat(candidate.model.weights["planning.linked"]).isEqualTo(expected)
        }
    }

    @Test
    fun theZeroAnchorDisablesExistingPrimaryAndLinkedWeights() {
        val original = specification()
        val specification =
            original.copy(
                parameters = listOf(linkedParameter()),
                arena =
                    original.arena.copy(
                        candidate =
                            BotModel(
                                weights =
                                    BotModels.default.weights +
                                        mapOf("planning.test" to 1.25, "planning.linked" to -1.0)
                            )
                    ),
            )

        val zero = StrategySearch.candidates(specification).first()

        assertThat(zero.values).containsExactly("planning.test", 0.0, "planning.linked", 0.0)
        assertThat(zero.model.weights["planning.test"]).isEqualTo(0.0)
        assertThat(zero.model.weights["planning.linked"]).isEqualTo(0.0)
    }

    @Test
    fun aCurrentAnchorIncludesItsMatchingLinkedWeights() {
        val original = specification()
        val specification =
            original.copy(
                parameters = listOf(linkedParameter()),
                arena =
                    original.arena.copy(
                        candidate =
                            BotModel(
                                weights =
                                    BotModels.default.weights +
                                        mapOf("planning.test" to 1.25, "planning.linked" to -1.0)
                            )
                    ),
            )

        val current = StrategySearch.candidates(specification).single { it.label == "current" }

        assertThat(current.values).containsExactly("planning.test", 1.25, "planning.linked", -1.0)
    }

    @Test
    fun anInconsistentCurrentVectorIsNotInventedAsAnAnchor() {
        val original = specification()
        val specification =
            original.copy(
                parameters = listOf(linkedParameter()),
                arena =
                    original.arena.copy(
                        candidate =
                            BotModel(
                                weights =
                                    BotModels.default.weights +
                                        mapOf("planning.test" to 1.25, "planning.linked" to -0.9)
                            )
                    ),
            )

        val candidates = StrategySearch.candidates(specification)

        assertThat(candidates.map { it.label }).doesNotContain("current")
        assertThat(candidates).hasSize(specification.candidates)
    }

    @Test
    fun linkedObjectOrderDoesNotChangeIdentityOrCandidateVectors() {
        val original =
            specification()
                .copy(
                    parameters =
                        listOf(
                            StrategySearchParameter(
                                "planning.test",
                                0.0,
                                2.0,
                                linkedMapOf("planning.a" to -0.8, "planning.b" to 0.5),
                            )
                        )
                )
        val reordered =
            original.copy(
                parameters =
                    listOf(
                        original.parameters
                            .single()
                            .copy(
                                linkedWeights =
                                    linkedMapOf("planning.b" to 0.5, "planning.a" to -0.8)
                            )
                    )
            )

        assertThat(original.identity()).isEqualTo(reordered.identity())
        assertThat(StrategySearch.candidates(original))
            .isEqualTo(StrategySearch.candidates(reordered))
    }

    @Test
    fun aLinkedWeightCannotAlsoBeAnIndependentDimension() {
        assertFailsWith<IllegalArgumentException> {
            specification()
                .copy(
                    parameters =
                        listOf(
                            linkedParameter(),
                            StrategySearchParameter("planning.linked", -2.0, 0.0),
                        )
                )
        }
    }

    @Test
    fun twoDimensionsCannotWriteTheSameLinkedWeight() {
        assertFailsWith<IllegalArgumentException> {
            specification()
                .copy(
                    parameters =
                        listOf(linkedParameter(), linkedParameter().copy(name = "planning.b"))
                )
        }
    }

    @Test
    fun linkedTargetsMustBeOtherPlanningWeights() {
        for (target in listOf("cash", "planning.", "planning.test")) {
            assertFailsWith<IllegalArgumentException> {
                linkedParameter().copy(linkedWeights = mapOf(target to 1.0))
            }
        }
    }

    @Test
    fun linkedMultipliersMustBeFiniteAndNonzero() {
        for (multiplier in listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                linkedParameter().copy(linkedWeights = mapOf("planning.linked" to multiplier))
            }
        }
    }

    @Test
    fun bothLinkedEndpointsMustStayWithinModelBounds() {
        for (multiplier in listOf(600000.0, -600000.0, Double.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                StrategySearchParameter(
                    "planning.test",
                    -2.0,
                    1.0,
                    mapOf("planning.linked" to multiplier),
                )
            }
        }
    }

    @Test
    fun sourceParsesAnExplicitLinkedWeightObject() {
        val specification =
            readSource(
                """{"name":"planning.test","min":0,"max":2,"linkedWeights":{"planning.linked":-0.8}}"""
            )

        assertThat(specification.parameters.single()).isEqualTo(linkedParameter())
    }

    @Test
    fun sourceRejectsNonObjectOrNonnumericLinkedWeights() {
        for (value in listOf("null", "[]", "0.8", "{\"planning.linked\":\"-0.8\"}")) {
            assertFailsWith<IllegalArgumentException> {
                readSource("""{"name":"planning.test","min":0,"max":2,"linkedWeights":$value}""")
            }
        }
    }

    @Test
    fun sourceRejectsDuplicateLinkedWeightKeysInsteadOfTakingTheLastValue() {
        assertFailsWith<JsonProcessingException> {
            readSource(
                """{"name":"planning.test","min":0,"max":2,"linkedWeights":{"planning.linked":-0.8,"planning.linked":-0.5}}"""
            )
        }
    }

    @Test
    fun unlinkedSearchRetainsTheFrozenV5SampleVector() {
        // Recorded from the packaged v5 executor before linked-weight support was introduced.
        val specification = specification().copy(candidates = 6)

        val candidates = StrategySearch.candidates(specification)

        assertThat(candidates.map { it.values["planning.test"] })
            .containsExactly(
                0.0,
                1.0,
                1.7351987463282548,
                1.1414373013669088,
                0.9752249989634789,
                0.35168847139132453,
            )
            .inOrder()
    }

    @Test
    fun omittedAndEmptyLinksParseToTheSameLegacySpecification() {
        val absent = readSource("""{"name":"planning.test","min":0,"max":2}""")
        val explicit = readSource("""{"name":"planning.test","min":0,"max":2,"linkedWeights":{}}""")

        assertThat(absent).isEqualTo(explicit)
        assertThat(StrategySearch.candidates(absent)).isEqualTo(StrategySearch.candidates(explicit))
    }

    @Test
    fun candidatesStayWithinBoundsAndAreDeterministic() {
        val specification =
            specification()
                .copy(
                    candidates = 24,
                    parameters =
                        listOf(
                            StrategySearchParameter("planning.a", -1.0, 3.0),
                            StrategySearchParameter("planning.b", 0.0, 2.0),
                        ),
                )

        val candidates = StrategySearch.candidates(specification)

        assertThat(candidates).isEqualTo(StrategySearch.candidates(specification))
        assertThat(candidates).hasSize(24)
        assertThat(candidates.map { it.values }.distinct()).hasSize(24)
        assertThat(
                candidates.all { candidate ->
                    specification.parameters.all { parameter ->
                        checkNotNull(candidate.values[parameter.name]) {
                            "Each candidate requires every configured parameter."
                        } in parameter.min..parameter.max
                    }
                }
            )
            .isTrue()
    }

    @Test
    fun candidatesIncludeZeroAndCenterAnchors() {
        val candidates = StrategySearch.candidates(specification())

        assertThat(candidates.first().values).containsExactly("planning.test", 0.0)
        assertThat(candidates[1].values).containsExactly("planning.test", 1.0)
    }

    @Test
    fun symmetricBoundsShareTheZeroAndCenterAnchor() {
        val specification =
            specification()
                .copy(parameters = listOf(StrategySearchParameter("planning.test", -2.0, 2.0)))

        val candidates = StrategySearch.candidates(specification)

        assertThat(candidates).hasSize(specification.candidates)
        assertThat(candidates.count { it.values["planning.test"] == 0.0 }).isEqualTo(1)
        assertThat(candidates.first().label).isEqualTo("zero-and-center")
    }

    @Test
    fun aRefinementSearchRetainsTheCurrentInRangeParameterVector() {
        val original = specification()
        val specification =
            original.copy(
                arena =
                    original.arena.copy(
                        candidate =
                            BotModel(
                                weights = BotModels.default.weights + ("planning.test" to 0.75)
                            )
                    )
            )

        val candidates = StrategySearch.candidates(specification)

        assertThat(candidates.single { it.label == "current" }.values)
            .containsExactly("planning.test", 0.75)
    }

    @Test
    fun replacingParametersPreservesEveryUnlistedWeight() {
        val original =
            BotModel(
                weights =
                    BotModels.default.weights +
                        mapOf("planning.unlisted" to 8.0, "planning.test" to 7.0)
            )
        val initial = specification()
        val specification = initial.copy(arena = initial.arena.copy(candidate = original))

        val candidates = StrategySearch.candidates(specification)

        assertThat(
                candidates.all {
                    it.model.weights.filterKeys { name -> name != "planning.test" } ==
                        original.weights.filterKeys { name -> name != "planning.test" }
                }
            )
            .isTrue()
        assertThat(candidates.first().model.weights["planning.test"]).isEqualTo(0.0)
    }

    @Test
    fun screeningAndSelectionSeedBanksNeverOverlap() {
        val specification = specification().copy(screeningPairs = 32, selectionPairs = 128)
        val screening = specification.bank(StrategySearchStage.SCREENING)
        val selection = specification.bank(StrategySearchStage.SELECTION)
        val first =
            List(screening.configurations.size * screening.pairsPerConfiguration) {
                StrategyArena.seed(screening.seed, it)
            }
        val second =
            List(selection.configurations.size * selection.pairsPerConfiguration) {
                StrategyArena.seed(selection.seed, it)
            }

        assertThat(first.toSet().intersect(second.toSet())).isEmpty()
        assertThat(first.distinct()).hasSize(first.size)
        assertThat(second.distinct()).hasSize(second.size)
    }

    @Test
    fun boundsExcludingTheBaselineAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            StrategySearchParameter("planning.test", 1.0, 2.0)
        }
    }

    @Test
    fun confirmationPurposeCannotBeUsedForSearch() {
        val specification = specification()

        assertFailsWith<IllegalArgumentException> {
            specification.copy(
                arena = specification.arena.copy(purpose = ArenaPurpose.CONFIRMATION)
            )
        }
    }

    @Test
    fun searchReusesIncumbentsAndCompletedPairsOnRestart() {
        val directory = Files.createTempDirectory("aor-search-resume-")
        try {
            val artifacts = TrainingArtifacts(directory)
            val calls = AtomicInteger()
            val specification = specification()
            val selected =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    StrategySearchRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            playGame = { bank, index, model, _ ->
                                calls.incrementAndGet()
                                game(bank, index, model)
                            },
                        )
                        .run()
                }
            val report = artifacts.read("search.json")

            val restored =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    StrategySearchRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            playGame = { _, _, _, _ ->
                                error("A completed search must not replay games.")
                            },
                        )
                        .run()
                }

            assertThat(calls.get()).isEqualTo(12)
            assertThat(restored).isEqualTo(selected)
            assertThat(artifacts.read("search.json")).isEqualTo(report)
            assertThat(report?.required("selectionOnly")?.booleanValue()).isTrue()
            assertThat(report?.required("promoted")?.booleanValue()).isFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun interruptedSearchRetainsCompletedIncumbentBank() {
        val directory = Files.createTempDirectory("aor-search-interrupted-")
        try {
            val artifacts = TrainingArtifacts(directory)
            val specification = specification()
            val interrupted = AtomicBoolean()
            assertFailsWith<ExecutionException> {
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    StrategySearchRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            playGame = { bank, index, model, _ ->
                                if (
                                    (model.weights["planning.test"] ?: 0.0) > 0.0 &&
                                        !interrupted.getAndSet(true)
                                )
                                    error("Simulated worker interruption")
                                game(bank, index, model)
                            },
                        )
                        .run()
                }
            }
            val cachedIncumbent = Files.readString(directory.resolve("screening-incumbent-0.json"))

            Tournaments(1, Long.MAX_VALUE).use { workers ->
                StrategySearchRunner(
                        specification,
                        artifacts,
                        workers,
                        Long.MAX_VALUE,
                        playGame = { bank, index, model, _ ->
                            check(
                                bank.seed != specification.arena.seed || model != bank.incumbent
                            ) {
                                "Completed screening incumbents must survive interruption."
                            }
                            game(bank, index, model)
                        },
                    )
                    .run()
            }

            assertThat(Files.readString(directory.resolve("screening-incumbent-0.json")))
                .isEqualTo(cachedIncumbent)
            assertThat(artifacts.read("search.json")?.required("complete")?.booleanValue()).isTrue()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun specification(): StrategySearchSpecification =
        StrategySearchSpecification(
            arena =
                ArenaSpecification(
                    purpose = ArenaPurpose.SCREENING,
                    configurations = listOf(TrainingMatrix.stratified.first()),
                    candidate = BotModels.default,
                    incumbent = BotModels.default,
                    opponents = listOf(BotModels.default),
                    pairsPerConfiguration = 2,
                    seed = 90210,
                ),
            parameters = listOf(StrategySearchParameter("planning.test", 0.0, 2.0)),
            candidates = 3,
            screeningPairs = 2,
            selectionPairs = 3,
            finalists = 1,
        )

    private fun linkedParameter(): StrategySearchParameter =
        StrategySearchParameter("planning.test", 0.0, 2.0, mapOf("planning.linked" to -0.8))

    private fun readSource(parameter: String): StrategySearchSpecification {
        val source = Files.createTempFile("aor-linked-search-", ".json")
        try {
            Files.writeString(
                source,
                """
                {
                  "schemaVersion":1,
                  "purpose":"SCREENING",
                  "configurations":["${TrainingMatrix.stratified.first().id}"],
                  "candidate":null,
                  "incumbent":null,
                  "opponents":[null],
                  "pairsPerConfiguration":2,
                  "seed":90210,
                  "parameters":[$parameter],
                  "candidates":3,
                  "screeningPairs":2,
                  "selectionPairs":3,
                  "finalists":1
                }
                """
                    .trimIndent(),
            )
            return StrategySearchSpecification.read(source)
        } finally {
            Files.deleteIfExists(source)
        }
    }

    private fun game(bank: ArenaSpecification, index: Int, model: BotModel): ArenaGame {
        val players = bank.configurations[index / bank.pairsPerConfiguration].playerCount
        val seat = index % bank.pairsPerConfiguration % players
        val result = if ((model.weights["planning.test"] ?: 0.0) > 0.0) 1.0 else 0.0
        val values = List(players) { if (it == seat) result else 0.0 }
        return ArenaGame(
            values,
            values,
            values,
            List(players) { player ->
                PilotReplayScore(
                    "p-$player",
                    1,
                    2,
                    if (player == 0) null else 3,
                    if (player == 0) null else 0,
                    player == seat && result > 0,
                )
            },
            7,
            9L,
        )
    }
}
