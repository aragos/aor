package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenCorrection
import dev.aragos.aor.ai.BotTokenValue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenValueCorrectionTest {
    private val incumbent =
        BotTokenValue.withWeights(
            BotModels.default,
            BotTokenValue.featureNames.map {
                if (it == "planning.tokenValue.v1.signedAmount") 100.0 else 0.0
            },
        )

    @Test
    fun correctionSourcesAllowAnEnabledIncumbentHead() {
        val directory = Files.createTempDirectory("aor-token-correction-source-")
        try {
            TrainingArtifacts(directory).model(incumbent, "baseline.json")
            val source = directory.resolve("source.json")
            ObjectMapper()
                .writeValue(
                    source.toFile(),
                    sourceFields() +
                        mapOf(
                            "candidate" to "baseline.json",
                            "incumbent" to "baseline.json",
                            "learningMode" to "CORRECTION",
                        ),
                )

            val parsed = TokenValueSpecification.read(source)

            assertThat(parsed.learningMode).isEqualTo(TokenValueLearningMode.CORRECTION)
            assertThat(parsed.arena.candidate).isEqualTo(incumbent)
            assertThat(parsed.identity()).endsWith("learningMode=CORRECTION")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun absentAndExplicitReplacementModesPreserveTheSameLegacyIdentity() {
        val directory = Files.createTempDirectory("aor-token-replacement-source-")
        try {
            val absent = directory.resolve("absent.json")
            val explicit = directory.resolve("explicit.json")
            ObjectMapper().writeValue(absent.toFile(), sourceFields())
            ObjectMapper()
                .writeValue(explicit.toFile(), sourceFields() + ("learningMode" to "REPLACEMENT"))

            val legacy = TokenValueSpecification.read(absent)
            val parsed = TokenValueSpecification.read(explicit)

            assertThat(parsed).isEqualTo(legacy)
            assertThat(parsed.learningMode).isEqualTo(TokenValueLearningMode.REPLACEMENT)
            assertThat(parsed.identity()).isEqualTo(legacy.identity())
            assertThat(parsed.identity()).doesNotContain("learningMode")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownOrNontextualModesAreRejected() {
        val directory = Files.createTempDirectory("aor-token-invalid-mode-")
        try {
            val source = directory.resolve("source.json")
            for (value in listOf("RESIDUAL", 1, true)) {
                ObjectMapper()
                    .writeValue(source.toFile(), sourceFields() + ("learningMode" to value))

                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        TokenValueSpecification.read(source)
                    }

                assertThat(failure).hasMessageThat().contains("REPLACEMENT or CORRECTION")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun replacementCollectionStillRejectsAnEnabledIncumbentHead() {
        val correction = specification()

        assertFailsWith<IllegalArgumentException> {
            correction.copy(learningMode = TokenValueLearningMode.REPLACEMENT)
        }
    }

    @Test
    fun correctionCollectionRejectsAnAlreadyEnabledCorrection() {
        val correction = specification()
        val fitted =
            BotTokenCorrection.withWeights(incumbent, List(BotTokenValue.featureNames.size) { 0.0 })

        assertFailsWith<IllegalArgumentException> {
            correction.copy(arena = correction.arena.copy(candidate = fitted, incumbent = fitted))
        }
    }

    @Test
    fun correctionCollectorAnchorsAtTheStrongPolicyAndContinuesWithIt() {
        val specification =
            (0..50).map { specification(seed = it) }.first { it.rootRequest(0).ordinal == 1 }
        val lineup = specification.lineup(0)
        val models =
            lineup.opponentIndices.mapIndexed { seat, opponent ->
                if (seat == lineup.seat) incumbent else specification.arena.opponents[opponent]
            }
        val collector = TokenValueCollector(specification, Long.MAX_VALUE)
        val session = ExperimentSession.start(lineup.configuration, lineup.seed.toLong())
        val actor = session.state.playerIds[lineup.seat]
        val root =
            checkNotNull(
                collector
                    .locate(session, actor, models, lineup.seed, specification.rootRequest(0))
                    .root
            ) {
                "The first token root must be reached."
            }
        val continuationSeed = TokenValueCollector.continuationSeed(lineup.seed, 0)
        val expected = root.frozen.branch(root.baseline.command, continuationSeed, Long.MAX_VALUE)
        while (expected.state.stage != Stage.FINISHED) {
            check(expected.decisions < 12000) { "The correction continuation fixture must finish." }
            val commands =
                expected.actors().associateWith { player ->
                    val seat = expected.state.playerIds.indexOf(player)
                    BotPolicy.choose(
                        expected.observation(player),
                        BotDifficulty.HARD,
                        lineup.seed xor (expected.decisions * 65537 + seat * 8191),
                        models[seat],
                    )
                }
            expected.apply(commands)
        }

        val collected = collector.collect(0)

        assertThat(root.baseline.command)
            .isNotEqualTo(
                BotPolicy.choose(root.observation, BotDifficulty.HARD, 0, BotModels.default)
            )
        assertThat(collected.learningMode).isEqualTo(TokenValueLearningMode.CORRECTION)
        assertThat(collected.baselineCommand).isEqualTo(root.baseline.command)
        assertThat(collected.alternatives.map(TokenValueAlternative::command))
            .containsExactlyElementsIn(
                BotTokenValue.alternatives(root.observation, root.baseline).map { it.command }
            )
            .inOrder()
        assertThat(collected.baseline().features)
            .containsExactlyElementsIn(List(BotTokenValue.featureNames.size) { 0.0 })
        assertThat(collected.baseline().outcomes.single().scores)
            .isEqualTo(expected.state.publicView().scores.map(PilotReplayScore::from))
        assertThat(collected.baseline().outcomes.single().decisions).isEqualTo(expected.decisions)
    }

    private fun specification(seed: Int = 20261126): TokenValueSpecification =
        TokenValueSpecification(
            arena =
                ArenaSpecification(
                    ArenaPurpose.SCREENING,
                    listOf(TrainingMatrix.stratified.first()),
                    incumbent,
                    incumbent,
                    listOf(BotModels.default),
                    2,
                    seed,
                ),
            trainingRoots = 2,
            validationRoots = 2,
            continuationReplicates = 1,
            regularization = listOf(0.1),
            learningMode = TokenValueLearningMode.CORRECTION,
        )

    private fun sourceFields(): Map<String, Any?> =
        mapOf(
            "schemaVersion" to 1,
            "purpose" to "SCREENING",
            "configurations" to specification().arena.configurations.map { it.id },
            "candidate" to null,
            "incumbent" to null,
            "opponents" to listOf(null),
            "pairsPerConfiguration" to 2,
            "seed" to 20261126,
            "trainingRoots" to 2,
            "validationRoots" to 2,
            "continuationReplicates" to 1,
            "regularization" to listOf(0.1),
        )
}
