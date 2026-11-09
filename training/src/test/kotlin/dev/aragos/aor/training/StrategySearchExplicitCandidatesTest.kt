package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategySearchExplicitCandidatesTest {
    private val initial =
        BotModel(
            weights =
                BotModels.default.weights +
                    mapOf("planning.tokenValue.enabled" to 1.0, "planning.heldCommodity" to 0.5)
        )
    private val challenger =
        initial.copy(
            weights =
                initial.weights +
                    mapOf("planning.leaderTiming" to 1.0, "planning.heldCommodity" to 0.6)
        )

    @Test
    fun relativeModelPathsLoadExactCandidatesAndPinTheirWeightsInTheIdentity() =
        withDirectory { directory ->
            val specification = readSource(directory)
            val candidates = StrategySearch.candidates(specification)
            val changed =
                specification.copy(
                    explicitCandidates =
                        listOf(
                            initial,
                            challenger.copy(weights = challenger.weights + ("cash" to 2.0)),
                        )
                )

            assertThat(candidates.map { it.model }).containsExactly(initial, challenger).inOrder()
            assertThat(candidates.map { it.label }).containsExactly("current", "sample-1").inOrder()
            assertThat(candidates.first().values).isEmpty()
            assertThat(candidates.last().values)
                .containsExactly("planning.leaderTiming", 1.0, "planning.heldCommodity", 0.6)
            assertThat(changed.identity()).isNotEqualTo(specification.identity())
        }

    @Test
    fun explicitPoolsRequireAnExactInitialAnchorAndMatchingCandidateCount() {
        val specification = specification()

        val wrongAnchor =
            assertFailsWith<IllegalArgumentException> {
                specification.copy(explicitCandidates = listOf(challenger, initial))
            }
        val wrongCount =
            assertFailsWith<IllegalArgumentException> { specification.copy(candidates = 3) }

        assertThat(wrongAnchor).hasMessageThat().contains("candidate zero")
        assertThat(wrongCount).hasMessageThat().contains("model count")
    }

    @Test
    fun sourceCannotCombineExplicitModelsWithSampledParameters() = withDirectory { directory ->
        val failure =
            assertFailsWith<IllegalArgumentException> {
                readSource(directory, mapOf("parameters" to emptyList<Any>()))
            }

        assertThat(failure).hasMessageThat().contains("exactly one")
    }

    @Test
    fun parameterSearchRetainsItsLegacyIdentity() {
        val specification =
            specification()
                .copy(
                    parameters = listOf(StrategySearchParameter("planning.test", 0.0, 2.0)),
                    explicitCandidates = null,
                )
        val expected =
            listOf(
                    specification.arena.identity(),
                    listOf(
                        "StrategySearchParameter(name=planning.test, min=0.0, max=2.0, linkedWeights={})"
                    ),
                    2,
                    2,
                    3,
                    1,
                )
                .joinToString("\n")

        assertThat(specification.identity()).isEqualTo(expected)
    }

    @Test
    fun explicitSearchReusesTheIncumbentBankAndArchivesEveryCandidate() =
        withDirectory { directory ->
            val calls = AtomicInteger()
            val specification = specification()
            val artifacts = TrainingArtifacts(directory)
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

            assertThat(calls.get()).isEqualTo(10)
            assertThat(selected.model).isEqualTo(challenger)
            assertThat(TrainingArtifacts.readModel(directory.resolve("candidate-0.json")))
                .isEqualTo(initial)
            assertThat(TrainingArtifacts.readModel(directory.resolve("candidate-1.json")))
                .isEqualTo(challenger)
            assertThat(artifacts.read("search.json")?.required("complete")?.booleanValue()).isTrue()
        }

    private fun specification(): StrategySearchSpecification =
        StrategySearchSpecification(
            arena =
                ArenaSpecification(
                    purpose = ArenaPurpose.SCREENING,
                    configurations = listOf(TrainingMatrix.stratified.first()),
                    candidate = initial,
                    incumbent = initial,
                    opponents = listOf(BotModels.default),
                    pairsPerConfiguration = 2,
                    seed = 90210,
                ),
            candidates = 2,
            screeningPairs = 2,
            selectionPairs = 3,
            finalists = 1,
            explicitCandidates = listOf(initial, challenger),
        )

    private fun readSource(
        directory: Path,
        fields: Map<String, Any> = emptyMap(),
    ): StrategySearchSpecification {
        val artifacts = TrainingArtifacts(directory)
        artifacts.model(initial, "initial.json")
        artifacts.model(challenger, "challenger.json")
        artifacts.write(
            "source.json",
            mapOf(
                "schemaVersion" to 1,
                "purpose" to "SCREENING",
                "configurations" to listOf(TrainingMatrix.stratified.first().id),
                "candidate" to "initial.json",
                "incumbent" to "initial.json",
                "opponents" to listOf("initial.json"),
                "pairsPerConfiguration" to 2,
                "seed" to 90210,
                "candidateModels" to listOf("initial.json", "challenger.json"),
                "screeningPairs" to 2,
                "selectionPairs" to 3,
                "finalists" to 1,
            ) + fields,
        )
        return StrategySearchSpecification.read(directory.resolve("source.json"))
    }

    private fun game(bank: ArenaSpecification, index: Int, model: BotModel): ArenaGame {
        val players = bank.configurations[index / bank.pairsPerConfiguration].playerCount
        val seat = index % bank.pairsPerConfiguration % players
        val winner = if (model == challenger) seat else (seat + 1) % players
        val values = List(players) { if (it == winner) 1.0 else 0.0 }
        return ArenaGame(
            wins = values,
            rewards = values,
            ranks = values,
            scores =
                List(players) { player ->
                    PilotReplayScore("p-$player", 1, 2, 3, 0, player == winner)
                },
            decisions = 7,
            elapsedMillis = 9L,
        )
    }

    private fun withDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("aor-explicit-search-")
        try {
            action(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
