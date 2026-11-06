package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenValueContinuationTest {
    @Test
    fun fourContinuationStreamsRetainTheFirstTwoLegacySeeds() {
        val streams = (0 until 4).map { TokenValueCollector.continuationSeed(-123456789, it) }

        assertThat(streams)
            .containsExactly(-477185661243L, -477185661244L, -477185661241L, -477185661242L)
            .inOrder()
    }

    @Test
    fun replicaLanesAreDistinctAcrossAdjacentAndExtremeOriginSeeds() {
        val origins = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE)

        val streams = origins.flatMap { origin ->
            (0 until 4).map { TokenValueCollector.continuationSeed(origin, it) }
        }

        assertThat(streams.toSet()).hasSize(origins.size * 4)
    }

    @Test
    fun sourceAcceptsFourReplicasAndItsIdentityPreservesTheRequestedCount() {
        val source = Files.createTempFile("aor-token-four-streams-", ".json")
        try {
            val initial = specification(replicates = 2)
            ObjectMapper()
                .writeValue(
                    source.toFile(),
                    mapOf(
                        "schemaVersion" to 1,
                        "purpose" to "SCREENING",
                        "configurations" to initial.arena.configurations.map { it.id },
                        "candidate" to null,
                        "incumbent" to null,
                        "opponents" to listOf(null),
                        "pairsPerConfiguration" to 2,
                        "seed" to initial.arena.seed,
                        "trainingRoots" to 2,
                        "validationRoots" to 2,
                        "continuationReplicates" to 4,
                        "regularization" to initial.regularization,
                    ),
                )

            val parsed = TokenValueSpecification.read(source)

            assertThat(parsed).isEqualTo(initial.copy(continuationReplicates = 4))
            assertThat(parsed.identity()).isNotEqualTo(initial.identity())
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun countsOutsideTheFourStreamLimitAreRejectedAtTheirOwners() {
        val original = origin(specification(replicates = 1), 0)
        val outcome = original.baseline().outcomes.single()

        for (count in listOf(0, 5)) {
            assertFailsWith<IllegalArgumentException> {
                specification(replicates = count)
            }
            assertFailsWith<IllegalArgumentException> {
                original.baseline().copy(outcomes = List(count) { outcome })
            }
        }
        for (replica in listOf(-1, 4)) {
            assertFailsWith<IllegalArgumentException> {
                TokenValueCollector.continuationSeed(0, replica)
            }
        }
    }

    @Test
    fun fourReplicasAggregatePairedDifferencesIntoOneOriginTarget() {
        val original = origin(specification(replicates = 4), 0)
        val baseline =
            original
                .baseline()
                .copy(
                    outcomes =
                        original.baseline().outcomes.zip(listOf(0.2, 0.4, 0.6, 0.8)) {
                            outcome,
                            reward ->
                            outcome.copy(reward = reward)
                        }
                )
        val alternative =
            original.alternatives
                .last()
                .copy(
                    outcomes =
                        original.alternatives.last().outcomes.zip(listOf(0.5, 0.3, 0.9, 0.4)) {
                            outcome,
                            reward ->
                            outcome.copy(reward = reward)
                        }
                )
        val paired = original.copy(alternatives = listOf(baseline, alternative))

        val delta = paired.delta(alternative)

        assertThat(delta).isWithin(1.0e-12).of(0.025)
        assertThat(paired.baseline().outcomes.map { it.continuationSeed })
            .containsExactlyElementsIn(alternative.outcomes.map { it.continuationSeed })
            .inOrder()
    }

    @Test
    fun thirdAndFourthReplicaOutcomesMustRemainPairedInOrder() {
        val original = origin(specification(replicates = 4), 0)
        val alternative = original.alternatives.last()
        val reversedTail =
            alternative.outcomes.take(2) + alternative.outcomes.takeLast(2).reversed()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                original.copy(
                    alternatives =
                        listOf(original.baseline(), alternative.copy(outcomes = reversedTail))
                )
            }

        assertThat(failure).hasMessageThat().contains("same distinct continuation seeds in order")
    }

    @Test
    fun fourReplicaCheckpointRoundTripRetainsEveryOrderedOutcome() {
        val original = origin(specification(replicates = 4), 0)
        val serialized = ObjectMapper().valueToTree<JsonNode>(original)

        val restored = TokenValueOrigin.read(serialized)

        assertThat(restored).isEqualTo(original)
        assertThat(restored.baseline().outcomes).hasSize(4)
    }

    @Test
    fun fourBranchesKeepTheOriginalSealedOpponentCommands() {
        val session = tokenSession()
        val actors = session.actors()
        val actor = actors.first()
        val before = session.state
        val batch =
            actors.mapIndexed { index, player -> player to "buy-tokens ${index + 2}" }.toMap()
        val root = TokenValueFrozenRoot(session, actor, batch)

        val branches =
            (0 until 4).flatMap { replica ->
                val seed = TokenValueCollector.continuationSeed(51, replica)
                listOf("buy-tokens -4", "buy-tokens 20").map { command ->
                    root.branch(command, seed, Long.MAX_VALUE)
                }
            }

        assertThat(session.state).isEqualTo(before)
        for (branch in branches) {
            for (opponent in actors.drop(1)) {
                assertThat(branch.state.playerState(opponent).tokenPurchase)
                    .isEqualTo(batch[opponent]?.substringAfter(' ')?.toInt())
            }
        }
    }

    @Test
    fun replicaCountDoesNotIncreaseTrainingWeightOrIndependentValidationCount() {
        val oneDirectory = Files.createTempDirectory("aor-token-one-stream-")
        val fourDirectory = Files.createTempDirectory("aor-token-four-streams-")
        try {
            fun fit(replicas: Int, artifacts: TrainingArtifacts) =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    val specification = specification(replicas)
                    TokenValuePilotRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            collectOrigin = { origin(specification, it) },
                        )
                        .run()
                }
            val one = fit(1, TrainingArtifacts(oneDirectory))
            val fourArtifacts = TrainingArtifacts(fourDirectory)

            val four = fit(4, fourArtifacts)
            val report =
                checkNotNull(fourArtifacts.read("fit.json")) { "The fit requires a report." }

            assertThat(four).isEqualTo(one)
            assertThat(report.required("trainingExamples").intValue()).isEqualTo(2)
            assertThat(report.required("validationOrigins").intValue()).isEqualTo(2)
            assertThat(report.required("validationRewardDifference").required("count").intValue())
                .isEqualTo(2)
        } finally {
            oneDirectory.toFile().deleteRecursively()
            fourDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun fourReplicaCheckpointsCannotResumeUnderATwoReplicaSpecification() {
        val directory = Files.createTempDirectory("aor-token-stream-count-")
        try {
            val artifacts = TrainingArtifacts(directory)
            artifacts.write("origin-0.json", origin(specification(replicates = 4), 0))
            val specification = specification(replicates = 2)

            val failure =
                assertFailsWith<ExecutionException> {
                    Tournaments(1, Long.MAX_VALUE).use { workers ->
                        TokenValuePilotRunner(
                                specification,
                                artifacts,
                                workers,
                                Long.MAX_VALUE,
                                collectOrigin = { origin(specification, it) },
                            )
                            .run()
                    }
                }

            assertThat(failure.cause).hasMessageThat().contains("paired continuation count")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun specification(replicates: Int): TokenValueSpecification =
        TokenValueSpecification(
            arena =
                ArenaSpecification(
                    ArenaPurpose.SCREENING,
                    listOf(TrainingMatrix.stratified.first()),
                    BotModels.default,
                    BotModels.default,
                    listOf(BotModels.default),
                    2,
                    20261126,
                ),
            trainingRoots = 2,
            validationRoots = 2,
            continuationReplicates = replicates,
            regularization = listOf(0.1),
        )

    private fun origin(specification: TokenValueSpecification, index: Int): TokenValueOrigin {
        val lineup = specification.lineup(index)
        val request = specification.rootRequest(index)
        fun outcomes(reward: Double) =
            List(specification.continuationReplicates) { replica ->
                TokenValueOutcome(
                    reward,
                    reward,
                    reward,
                    List(lineup.configuration.playerCount) {
                        PilotReplayScore("p${it + 1}", 10, 10, 0, 20, it == lineup.seat)
                    },
                    100,
                    TokenValueCollector.continuationSeed(lineup.seed, replica),
                )
            }
        return TokenValueOrigin(
            index,
            specification.split(index),
            lineup.configuration.id,
            lineup.seed,
            lineup.seat,
            lineup.opponentIndices,
            request.epoch,
            10,
            TokenValueOriginStatus.COLLECTED,
            actualEpoch = request.epoch,
            turn = 1,
            rootDecision = 10,
            baselineCommand = "buy-tokens 16",
            alternatives =
                listOf(
                    TokenValueAlternative(
                        "buy-tokens 16",
                        List(BotTokenValue.featureNames.size) { 0.0 },
                        outcomes(0.0),
                    ),
                    TokenValueAlternative(
                        "buy-tokens 24",
                        List(BotTokenValue.featureNames.size) { if (it == 0) 1.0 else 0.0 },
                        outcomes(1.0),
                    ),
                ),
            requestedOrdinal = request.ordinal,
        )
    }

    private fun tokenSession(): ExperimentSession {
        val session = ExperimentSession.start(specification(4).arena.configurations.single(), 51L)
        while (session.state.stage != Stage.TOKEN_PURCHASE) {
            check(session.decisions < 100) { "The fixture must reach its first token purchase." }
            session.apply(choices(session))
        }
        return session
    }

    private fun choices(session: ExperimentSession): Map<PlayerId, String> =
        session.actors().associateWith { actor ->
            BotPolicy.choose(
                session.observation(actor),
                BotDifficulty.HARD,
                51 xor session.decisions,
            )
        }
}
