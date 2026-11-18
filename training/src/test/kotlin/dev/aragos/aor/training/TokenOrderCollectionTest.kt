package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.TerritoryEncoder
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderCollectionTest {
    @Test
    fun directResumeCannotRenewTheOriginalNativeDeadline() {
        ArenaPurchaseFixture().use { fixture ->
            val started = Instant.parse("2026-11-10T10:00:00Z")
            val original = TokenOrderCollectionMain.originalDeadline(fixture.directory, started, .5)

            val resumed =
                TokenOrderCollectionMain.originalDeadline(fixture.directory, started, 72.0)

            assertThat(resumed).isEqualTo(original)
            assertThat(resumed).isEqualTo(Instant.parse("2026-11-10T10:30:00Z"))
        }
    }

    @Test
    fun collectionRequiresSharedPurchaseRolesAndNeverSilentlyUsesAnArenaOverride() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TokenOrderSpecification(fixture.specification)
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun sealedPrefixCommandsUseTheOrdinaryModelAndOneCommonSeedBase() {
        ArenaPurchaseFixture().use { fixture ->
            val session = firstToken(fixture)
            val before = session.state
            val seed = 71
            val model =
                BotModels.default.copy(
                    weights = BotModels.default.weights + ("tokenPreference" to 80.0)
                )
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { model },
                    fixture.frozen.controllers(4, 0, null),
                    seed,
                    Long.MAX_VALUE,
                )
            val observations = session.actors().associateWith(session::observation)

            val commands = continuation.commands(session, observations)

            assertThat(commands)
                .isEqualTo(
                    observations.mapValues { (actor, observation) ->
                        val seat = session.state.playerIds.indexOf(actor)
                        BotPolicy.choose(
                            observation,
                            BotDifficulty.HARD,
                            seed xor (session.decisions * 65537 + seat * 8191),
                            model,
                        )
                    }
                )
            assertThat(session.state).isEqualTo(before)
        }
    }

    @Test
    fun purchaseRoutingKeepsItsFrozenPriorWhenTheOrdinaryModelDisagrees() {
        ArenaPurchaseFixture().use { fixture ->
            val session = firstToken(fixture)
            while (session.state.stage != Stage.PURCHASE) {
                session.apply(ordinaryChoices(session))
            }
            val actor = session.actors().single()
            val observation = session.observation(actor)
            val seat = session.state.playerIds.indexOf(actor)
            val seed = 71 xor (session.decisions * 65537 + seat * 8191)
            val prior = TrainingArtifacts.readModel(fixture.directory.resolve("baseline.json"))
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            val decision =
                SimulationDecision(
                    session.decisions,
                    seat,
                    seed,
                    session.state.viewForPlayer(actor),
                    observation,
                )
            val expected = PurchaseActorController(prior, policy).choose(decision)
            val forcePass = prior.copy(weights = prior.weights + ("action.pass" to 1.0e6))
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { forcePass },
                    fixture.frozen.controllers(4, 0, null),
                    71,
                    Long.MAX_VALUE,
                )

            val chosen = continuation.commands(session, mapOf(actor to observation))

            assertThat(chosen[actor]).isEqualTo(expected)
            assertThat(BotPolicy.choose(observation, BotDifficulty.HARD, seed, forcePass))
                .isEqualTo("pass")
        }
    }

    @Test
    fun pairedFullTailsRetainTheActualBaselineAndSameChanceStreamsForEveryProposal() {
        ArenaPurchaseFixture().use { fixture ->
            val initial =
                TokenOrderSpecification(
                    fixture.specification,
                    trainingRoots = 2,
                    validationRoots = 2,
                )
            val seed =
                (71..200).first { candidate ->
                    val specification = initial.copy(arena = initial.arena.copy(seed = candidate))
                    specification.request(0).ordinal == 1
                }
            val specification = initial.copy(arena = initial.arena.copy(seed = seed))

            val origin = TokenOrderCollector(specification, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(origin.status).isEqualTo(TokenValueOriginStatus.COLLECTED)
            val observation =
                requireNotNull(origin.observation) { "A collected fixture has an observation." }
            val alternatives =
                requireNotNull(origin.alternatives) { "A collected fixture has outcomes." }
            assertThat(observation.encoderVersion).isEqualTo(TerritoryEncoder.TOKEN_2.version)
            assertThat(alternatives.first().index).isEqualTo(observation.baselineIndex)
            assertThat(alternatives.first().command).isEqualTo(origin.baselineCommand)
            assertThat(alternatives)
                .hasSize(
                    requireNotNull(origin.proposedIndices) { "A collected fixture has proposals." }
                        .size
                )
            assertThat(
                    alternatives.all { alternative ->
                        alternative.outcomes.map(TokenValueOutcome::continuationSeed) ==
                            alternatives
                                .first()
                                .outcomes
                                .map(TokenValueOutcome::continuationSeed) &&
                            alternative.outcomes.all {
                                it.decisions > origin.originDecisions && it.scores.size == 4
                            }
                    }
                )
                .isTrue()
            TokenOrderCollectionMain.validateOrigin(
                PurchaseJson.mapper.valueToTree(origin),
                specification,
                0,
                "fixture",
            )
        }
    }

    @Test
    fun expiredCollectorCannotPublishAPartialOrigin() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                TokenOrderSpecification(
                    fixture.specification,
                    trainingRoots = 2,
                    validationRoots = 2,
                )

            assertFailsWith<CancellationException> {
                TokenOrderCollector(specification, "fixture", 0).collect(0)
            }

            assertThat(fixture.artifacts.read("origin-0.json")).isNull()
            assertThat(fixture.artifacts.read("finished.json")).isNull()
        }
    }

    @Test
    fun schemaExportIncludesOnlyTheExplicitTokenVersionTwoAdditions() {
        ArenaPurchaseFixture().use { fixture ->
            val output = fixture.directory.resolve("token-schema.json")
            TerritoryValueSchema.run(
                listOf(
                    "--mode",
                    "territory-value-schema",
                    "--configuration",
                    fixture.configuration.id,
                    "--encoder",
                    TerritoryEncoder.TOKEN_2.version,
                    "--output",
                    output.toString(),
                )
            )

            val schema = PurchaseJson.read(output)

            assertThat(schema["encoderVersion"].asText())
                .isEqualTo(TerritoryEncoder.TOKEN_2.version)
            assertThat(schema["stateFeatureNames"].map(JsonNode::asText))
                .contains("role.0.capitalChoicePosition")
            assertThat(schema["actionFeatureNames"].map(JsonNode::asText))
                .containsAtLeast("bid.amount.15", "bid.amount.16")
        }
    }

    private fun firstToken(fixture: ArenaPurchaseFixture): ExperimentSession {
        val session = ExperimentSession.start(fixture.configuration, 71L)
        while (session.state.stage != Stage.TOKEN_PURCHASE) {
            check(session.decisions < 100) { "The fixture must reach its first token decision." }
            session.apply(ordinaryChoices(session))
        }
        return session
    }

    private fun ordinaryChoices(session: ExperimentSession): Map<PlayerId, String> =
        session.actors().associateWith { actor ->
            BotPolicy.choose(
                session.observation(actor),
                BotDifficulty.HARD,
                71 xor session.decisions,
            )
        }
}
