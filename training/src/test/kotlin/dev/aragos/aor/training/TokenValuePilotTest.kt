package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenCorrection
import dev.aragos.aor.ai.BotTokenValue
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenValuePilotTest {
    @Test
    fun multiSetupBanksKeepEqualCoverageAndUseDisjointChanceStreams() {
        val initial = specification()
        val setups =
            listOf(
                TrainingMatrix.stratified.first(),
                TrainingMatrix.stratified.first {
                    it.playerCount == 6 && it.rules.drawThreeKeepTwo
                },
            )
        val study =
            initial.copy(
                arena = initial.arena.copy(configurations = setups),
                trainingRoots = 8,
                validationRoots = 4,
            )

        val training = (0 until study.trainingRoots).map(study::lineup)
        val validation = (study.trainingRoots until study.origins).map(study::lineup)

        assertThat(training.groupingBy { it.configuration }.eachCount())
            .containsExactly(setups[0], 4, setups[1], 4)
        assertThat(validation.groupingBy { it.configuration }.eachCount())
            .containsExactly(setups[0], 2, setups[1], 2)
        assertThat(training.filter { it.configuration == setups[1] }.map { it.seat })
            .containsExactly(1, 2, 3, 4)
        assertThat((training + validation).map { it.seed }.distinct()).hasSize(study.origins)
    }

    @Test
    fun correctionFitPreservesTheEnabledIncumbentAndRecordsItsMode() {
        val directory = Files.createTempDirectory("aor-token-correction-fit-")
        try {
            val baseline =
                BotTokenValue.withWeights(
                    BotModels.default,
                    List(BotTokenValue.featureNames.size) { 0.25 },
                )
            val original = specification()
            val specification =
                original.copy(
                    arena = original.arena.copy(candidate = baseline, incumbent = baseline),
                    learningMode = TokenValueLearningMode.CORRECTION,
                )
            val artifacts = TrainingArtifacts(directory)
            val fitted =
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

            assertThat(fitted.weights.filterKeys { !it.startsWith("planning.tokenCorrection.") })
                .isEqualTo(baseline.weights)
            assertThat(fitted.weights[BotTokenCorrection.GATE]).isEqualTo(1.0)
            assertThat(fitted.weights[BotTokenCorrection.coefficientNames.first()])
                .isGreaterThan(0.0)
            assertThat(TrainingArtifacts.readModel(directory.resolve("candidate.json")))
                .isEqualTo(fitted)
            for (name in listOf("dataset.json", "fit.json", "origin-0.json")) {
                assertThat(artifacts.read(name)?.required("learningMode")?.asText())
                    .isEqualTo("CORRECTION")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun correctionResumeRejectsReplacementOrigins() {
        val directory = Files.createTempDirectory("aor-token-correction-checkpoint-")
        try {
            val original = specification()
            val correction = original.copy(learningMode = TokenValueLearningMode.CORRECTION)
            val artifacts = TrainingArtifacts(directory)
            artifacts.write("origin-0.json", origin(original, 0))

            val failure =
                assertFailsWith<ExecutionException> {
                    Tournaments(1, Long.MAX_VALUE).use { workers ->
                        TokenValuePilotRunner(
                                correction,
                                artifacts,
                                workers,
                                Long.MAX_VALUE,
                                collectOrigin = { origin(correction, it) },
                            )
                            .run()
                    }
                }

            assertThat(failure.cause).hasMessageThat().contains("collection learning mode")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun rootRequestsPrecommitEpochAndBoundedOrdinalFromAnIndependentSeed() {
        val specification = specification().copy(trainingRoots = 120, validationRoots = 120)
        val requests = (0 until specification.origins).map(specification::rootRequest)
        val repeated = (0 until specification.origins).map(specification::rootRequest)

        assertThat(requests).isEqualTo(repeated)
        for ((index, request) in requests.withIndex()) {
            assertThat(request.epoch).isEqualTo(specification.bankIndex(index) % 3 + 1)
            assertThat(request.ordinal).isAtLeast(1)
            assertThat(request.ordinal).isAtMost(if (request.epoch == 3) 4 else 6)
        }
        assertThat(requests.filter { it.epoch == 1 }.map { it.ordinal }.toSet()).hasSize(6)
        assertThat(requests.filter { it.epoch == 3 }.map { it.ordinal }.toSet()).hasSize(4)
    }

    @Test
    fun selectedRootStopsBeforeApplyingItsSealedBatch() {
        val session = tokenSession()
        val before = session.state
        val decisions = session.decisions
        val collector = TokenValueCollector(specification(), Long.MAX_VALUE)

        val backbone =
            collector.locate(
                session,
                session.actors().first(),
                List(session.state.playerIds.size) { BotModels.default },
                51,
                TokenValueRootRequest(session.state.currentEpoch.number, 1),
            )

        assertThat(backbone.root).isNotNull()
        assertThat(backbone.skipReason).isNull()
        assertThat(backbone.decisions).isEqualTo(decisions)
        assertThat(backbone.root?.decision).isEqualTo(decisions)
        assertThat(session.decisions).isEqualTo(decisions)
        assertThat(session.state).isEqualTo(before)
    }

    @Test
    fun laterOrdinalIgnoresEarlierRootsAndRegeneratesTheSamePrefix() {
        val initial = tokenSession()
        val first = initial.fork()
        val repeated = initial.fork()
        val collector = TokenValueCollector(specification(), Long.MAX_VALUE)
        val request = TokenValueRootRequest(initial.state.currentEpoch.number, 2)
        fun locate(session: ExperimentSession) =
            collector.locate(
                session,
                initial.actors().first(),
                List(session.state.playerIds.size) { BotModels.default },
                51,
                request,
            )

        val firstBackbone = locate(first)
        val repeatedBackbone = locate(repeated)

        assertThat(firstBackbone.root).isNotNull()
        assertThat(firstBackbone.decisions).isGreaterThan(initial.decisions)
        assertThat(firstBackbone.root?.turn)
            .isGreaterThan(checkNotNull(initial.state.turnNumber) { "The fixture has a turn." })
        assertThat(firstBackbone.decisions).isEqualTo(firstBackbone.root?.decision)
        assertThat(repeatedBackbone.root?.observation).isEqualTo(firstBackbone.root?.observation)
        assertThat(repeatedBackbone.root?.baseline).isEqualTo(firstBackbone.root?.baseline)
        assertThat(repeated.state).isEqualTo(first.state)
        assertThat(repeated.decisions).isEqualTo(first.decisions)
    }

    @Test
    fun anUnreachedRequestedRootHasNoRetrospectiveFallback() {
        val previous = tokenSession()
        val session = ExperimentSession(previous.state.copy(endingTriggered = true), 51L)

        val backbone =
            TokenValueCollector(specification(), Long.MAX_VALUE)
                .locate(
                    session,
                    previous.actors().first(),
                    List(session.state.playerIds.size) { BotModels.default },
                    51,
                    TokenValueRootRequest(previous.state.currentEpoch.number, 2),
                )

        assertThat(backbone.root).isNull()
        assertThat(backbone.skipReason).isEqualTo(TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED)
        assertThat(session.state.stage).isEqualTo(Stage.FINISHED)
        assertThat(backbone.decisions).isGreaterThan(0)
        assertThat(backbone.decisions).isEqualTo(session.decisions)
    }

    @Test
    fun anIneligiblePrecommittedRootIsExplicitlySkipped() {
        val previous = tokenSession()
        val actor = previous.actors().first()
        val state =
            previous.state.copy(
                endingTriggered = true,
                players =
                    previous.state.players +
                        (actor to previous.state.playerState(actor).copy(cash = 0)),
            )
        val session = ExperimentSession(state, 51L)

        val backbone =
            TokenValueCollector(specification(), Long.MAX_VALUE)
                .locate(
                    session,
                    actor,
                    List(state.playerIds.size) { BotModels.default },
                    51,
                    TokenValueRootRequest(state.currentEpoch.number, 1),
                )

        assertThat(backbone.root).isNull()
        assertThat(backbone.skipReason).isEqualTo(TokenValueSkipReason.PRECOMMITTED_ROOT_INELIGIBLE)
        assertThat(session.state.stage).isEqualTo(Stage.FINISHED)
        assertThat(backbone.decisions).isEqualTo(session.decisions)
    }

    @Test
    fun precommittedOriginSerializationRetainsOrdinalAndPrefixLength() {
        val original = origin(specification(), 0)
        val node = ObjectMapper().valueToTree<ObjectNode>(original)

        val restored = TokenValueOrigin.read(node)

        assertThat(restored).isEqualTo(original)
        assertThat(restored.schemaVersion).isEqualTo(2)
        assertThat(restored.selectionProtocol)
            .isEqualTo(TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1)
        assertThat(restored.requestedOrdinal).isEqualTo(specification().rootRequest(0).ordinal)
        assertThat(restored.originDecisions).isEqualTo(restored.rootDecision)
    }

    @Test
    fun oldReservoirOriginsRetainFullGameLengthAndFallbackEpochWhenRead() {
        val original =
            origin(specification(), 0)
                .copy(
                    schemaVersion = 1,
                    selectionProtocol = TokenValueSelectionProtocol.RESERVOIR_V1,
                    requestedOrdinal = null,
                    actualEpoch = 3,
                    originDecisions = 100,
                )
        val node = ObjectMapper().valueToTree<ObjectNode>(original)
        node.remove(listOf("schemaVersion", "selectionProtocol", "requestedOrdinal"))

        val restored = TokenValueOrigin.read(node)

        assertThat(restored).isEqualTo(original)
        assertThat(restored.originDecisions)
            .isGreaterThan(
                checkNotNull(restored.rootDecision) { "A collected root has a decision." }
            )
        assertThat(restored.actualEpoch).isNotEqualTo(restored.requestedEpoch)
    }

    @Test
    fun floatingPointCancellationBecomesAZeroTargetWithoutChangingRawOutcomes() {
        val original = origin(specification(), 0)
        val template = original.baseline().outcomes.single()
        val baseline =
            original
                .baseline()
                .copy(
                    outcomes =
                        listOf(
                            template.copy(reward = 0.2, continuationSeed = 1L),
                            template.copy(reward = 0.2, continuationSeed = 2L),
                        )
                )
        val alternative =
            original.alternatives
                .last()
                .copy(
                    outcomes =
                        listOf(
                            template.copy(reward = 0.3, continuationSeed = 1L),
                            template.copy(reward = 0.1, continuationSeed = 2L),
                        )
                )
        val paired = original.copy(alternatives = listOf(baseline, alternative))
        val raw =
            alternative.outcomes
                .zip(baseline.outcomes) { after, before ->
                    after.reward - before.reward
                }
                .average()

        assertThat(raw).isNotEqualTo(0.0)
        assertThat(paired.delta(alternative)).isEqualTo(0.0)
        assertThat(paired.alternatives.last().outcomes.map { it.reward }).containsExactly(0.3, 0.1)
        assertThat(original.delta(original.alternatives.last())).isEqualTo(1.0)
    }

    @Test
    fun aResumeCannotReuseACheckpointWithAnotherRequestedOrdinal() {
        val directory = Files.createTempDirectory("aor-token-value-ordinal-")
        try {
            val specification = specification()
            val original = origin(specification, 0)
            val requestedOrdinal =
                checkNotNull(original.requestedOrdinal) { "The fixture has an ordinal." }
            val artifacts = TrainingArtifacts(directory)
            artifacts.write(
                "origin-0.json",
                original.copy(requestedOrdinal = requestedOrdinal % 6 + 1),
            )

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

            assertThat(failure.cause)
                .hasMessageThat()
                .contains("root-selection protocol and ordinal")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun trainingAndValidationUseDifferentWholeOriginGameBanks() {
        val specification = specification()
        val train = (0 until specification.trainingRoots).map { specification.lineup(it).seed }
        val validation =
            (specification.trainingRoots until specification.origins).map {
                specification.lineup(it).seed
            }

        assertThat(train.toSet().intersect(validation.toSet())).isEmpty()
        assertThat(validation.first())
            .isEqualTo(StrategyArena.seed(specification.arena.seed + 1, 0))
        assertThat(specification.split(0)).isEqualTo(TokenValueSplit.TRAINING)
        assertThat(specification.split(2)).isEqualTo(TokenValueSplit.VALIDATION)
    }

    @Test
    fun aComparisonSpecCannotSilentlySelectADifferentCollectionBaseline() {
        val initial = specification()
        val changed =
            BotModels.default.copy(
                weights = BotModels.default.weights + ("planning.changed" to 1.0)
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                initial.copy(arena = initial.arena.copy(candidate = changed))
            }

        assertThat(failure).hasMessageThat().contains("identical candidate and incumbent baseline")
    }

    @Test
    fun rootForkKeepsAllSealedOpponentsFixedAndDoesNotMutateTheOrigin() {
        val session = tokenSession()
        val actors = session.actors()
        val controlled = actors.first()
        val batch = actors.mapIndexed { index, actor -> actor to "buy-tokens ${index + 2}" }.toMap()
        val before = session.state
        val root = TokenValueFrozenRoot(session, controlled, batch)

        val first = root.branch("buy-tokens -4", 99L, Long.MAX_VALUE)
        val second = root.branch("buy-tokens 20", 99L, Long.MAX_VALUE)
        val repeated = root.branch("buy-tokens -4", 99L, Long.MAX_VALUE)

        assertThat(session.state).isEqualTo(before)
        assertThat(first.state).isEqualTo(repeated.state)
        for (actor in actors.drop(1)) {
            assertThat(first.state.playerState(actor).tokenPurchase)
                .isEqualTo(second.state.playerState(actor).tokenPurchase)
            assertThat(first.state.playerState(actor).tokenPurchase)
                .isEqualTo(batch[actor]?.substringAfter(' ')?.toInt())
        }
    }

    @Test
    fun incompleteSealedBatchCannotBecomeATrainingFork() {
        val session = tokenSession()
        val actors = session.actors()

        assertFailsWith<IllegalArgumentException> {
            TokenValueFrozenRoot(
                session,
                actors.first(),
                actors.dropLast(1).associateWith { "buy-tokens 0" },
            )
        }
    }

    @Test
    fun aChangedTokenContinuationReplaysThroughTheOrdinaryEngine() {
        val session = tokenSession()
        val controlled = session.actors().first()
        val commands = choices(session)
        val branch =
            TokenValueFrozenRoot(session, controlled, commands)
                .branch("buy-tokens -4", 183L, Long.MAX_VALUE)
        while (branch.state.stage != Stage.FINISHED) {
            check(branch.decisions < 12000) { "The token-value replay fixture must terminate." }
            branch.apply(choices(branch))
        }

        val replayed =
            branch.entries.fold(GameState(branch.state.header)) { state, entry ->
                state.step(TextCodec.parse(entry.toText()))
            }

        assertThat(replayed).isEqualTo(branch.state)
    }

    @Test
    fun completedOriginsResumeWithoutCollectingAgain() {
        val directory = Files.createTempDirectory("aor-token-value-resume-")
        try {
            val specification = specification()
            val calls = AtomicInteger()
            val artifacts = TrainingArtifacts(directory)
            val first =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    TokenValuePilotRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            collectOrigin = { index ->
                                calls.incrementAndGet()
                                origin(specification, index)
                            },
                        )
                        .run()
                }

            val restored =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    TokenValuePilotRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            collectOrigin = { error("A completed origin must be reused.") },
                        )
                        .run()
                }

            assertThat(calls.get()).isEqualTo(4)
            assertThat(restored).isEqualTo(first)
            assertThat(artifacts.read("fit.json")?.required("selectionOnly")?.booleanValue())
                .isTrue()
            assertThat(
                    artifacts
                        .read("fit.json")
                        ?.required("validationRewardDifference")
                        ?.required("count")
                        ?.intValue()
                )
                .isEqualTo(2)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun validationOutcomesDoNotFitTheCoefficients() {
        val directory = Files.createTempDirectory("aor-token-value-split-")
        try {
            val specification = specification()
            val fitted =
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    TokenValuePilotRunner(
                            specification,
                            TrainingArtifacts(directory),
                            workers,
                            Long.MAX_VALUE,
                            collectOrigin = { index ->
                                origin(
                                    specification,
                                    index,
                                    positive =
                                        specification.split(index) == TokenValueSplit.TRAINING,
                                )
                            },
                        )
                        .run()
                }

            assertThat(fitted.weights[BotTokenValue.featureNames.first()]).isGreaterThan(0.0)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun interruptedOriginIsRegeneratedAtTheSameIndexWhileCompletedOriginsSurvive() {
        val directory = Files.createTempDirectory("aor-token-value-interrupted-")
        try {
            val specification = specification()
            val attempts = AtomicIntegerArray(specification.origins)
            val artifacts = TrainingArtifacts(directory)
            assertFailsWith<ExecutionException> {
                Tournaments(1, Long.MAX_VALUE).use { workers ->
                    TokenValuePilotRunner(
                            specification,
                            artifacts,
                            workers,
                            Long.MAX_VALUE,
                            collectOrigin = { index ->
                                val attempt = attempts.incrementAndGet(index)
                                if (index == 1 && attempt == 1) error("Simulated unfinished origin")
                                origin(specification, index)
                            },
                        )
                        .run()
                }
            }
            val completed = Files.readString(directory.resolve("origin-0.json"))

            Tournaments(1, Long.MAX_VALUE).use { workers ->
                TokenValuePilotRunner(
                        specification,
                        artifacts,
                        workers,
                        Long.MAX_VALUE,
                        collectOrigin = { index ->
                            attempts.incrementAndGet(index)
                            origin(specification, index)
                        },
                    )
                    .run()
            }

            assertThat(attempts[0]).isEqualTo(1)
            assertThat(attempts[1]).isEqualTo(2)
            assertThat(Files.readString(directory.resolve("origin-0.json"))).isEqualTo(completed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun skippedOriginRetainsItsIdentityWithoutInventingTrainingExamples() {
        val original = origin(specification(), 0)
        val skipped =
            original.copy(
                status = TokenValueOriginStatus.SKIPPED,
                actualEpoch = null,
                turn = null,
                rootDecision = null,
                baselineCommand = null,
                alternatives = emptyList(),
                skipReason = TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED,
            )

        assertThat(skipped.gameSeed).isEqualTo(original.gameSeed)
        assertThat(skipped.index).isEqualTo(original.index)
        assertThat(skipped.alternatives).isEmpty()
    }

    private fun specification(): TokenValueSpecification =
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
            continuationReplicates = 1,
            regularization = listOf(0.01, 0.1),
        )

    private fun origin(
        specification: TokenValueSpecification,
        index: Int,
        positive: Boolean = true,
    ): TokenValueOrigin {
        val lineup = specification.lineup(index)
        val request = specification.rootRequest(index)
        fun outcome(reward: Double) =
            TokenValueOutcome(
                reward,
                reward,
                reward,
                List(lineup.configuration.playerCount) {
                    PilotReplayScore("p${it + 1}", 10, 10, 0, 20, it == lineup.seat)
                },
                100,
                TokenValueCollector.continuationSeed(lineup.seed, 0),
            )
        val baseline = if (positive) 0.0 else 1.0
        val selected = if (positive) 1.0 else 0.0
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
                        listOf(outcome(baseline)),
                    ),
                    TokenValueAlternative(
                        "buy-tokens 24",
                        List(BotTokenValue.featureNames.size) { if (it == 0) 1.0 else 0.0 },
                        listOf(outcome(selected)),
                    ),
                ),
            requestedOrdinal = request.ordinal,
            learningMode = specification.learningMode,
        )
    }

    private fun tokenSession(): ExperimentSession {
        val session =
            ExperimentSession.start(
                specification().arena.configurations.single(),
                51L,
                recordEntries = true,
            )
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
