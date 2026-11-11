package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderOverlayCollectionTest {
    @Test
    fun legacySourceCannotDeclareEvenAnEmptyReplayList() {
        ArenaPurchaseFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<JsonNode>("replayOrigins", PurchaseJson.mapper.createArrayNode())
            fixture.artifacts.write("arena.json", source)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderSpecification.read(fixture.source)
            }
        }
    }

    @Test
    fun omissionRetainsTheLegacyIdentityAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = TokenOrderSpecification(fixture.specification)

            assertThat(specification.identity())
                .isEqualTo(
                    listOf(
                            specification.arena.identity(),
                            96,
                            32,
                            2,
                            TerritoryEncoder.TOKEN_2.version,
                            TokenOrderPolicy.PROPOSAL_VERSION,
                            TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
                        )
                        .joinToString("\n")
                )
            assertThat(TokenOrderContinuationArtifacts.metadata(specification, fixture.directory))
                .isEmpty()
        }
    }

    @Test
    fun optInRequiresProspectiveReplayOrigins() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit { remove("replayOrigins") }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("prospective replay origins")
        }
    }

    @Test
    fun replayOriginsCannotBeRepeated() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.specification

            assertFailsWith<IllegalArgumentException> {
                specification.copy(replayOrigins = listOf(0, 0))
            }
        }
    }

    @Test
    fun replayOriginsMustBelongToTheDeclaredBank() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.specification

            assertFailsWith<IllegalArgumentException> {
                specification.copy(replayOrigins = listOf(4))
            }
        }
    }

    @Test
    fun unknownContinuationProtocolIsRejected() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit { put("continuationProtocol", "FUTURE") }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("Unknown token continuation protocol")
        }
    }

    @Test
    fun controlledFixedTokenRoleIsRejected() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).put("candidateSelector", "FIXED_16_TO_15")
            }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("ordinary token reference")
        }
    }

    @Test
    fun unusedIncumbentTokenRoleIsRejected() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).put("incumbentSelector", "FIXED_16_TO_15")
            }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun ordinaryBrowserTokenGateCannotSilentlyChangeTheReference() {
        TokenOrderOverlayFixture().use { fixture ->
            val model =
                BotModels.default.copy(
                    weights =
                        BotModels.default.weights +
                            ("planning.tokenOrderControl.fixed16To15" to 1.0)
                )
            fixture.artifacts.model(model, "controlled.json")
            fixture.artifacts.model(model, "expansion.json")
            fixture.edit {
                put("candidate", "controlled.json")
                put("incumbent", "controlled.json")
                (this["expansionImitation"] as ObjectNode).remove("opponentModels")
            }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("without the fixed token gate")
        }
    }

    @Test
    fun minusTwoDiagnosticRivalsAreOutsideTheCollectionContract() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).set<JsonNode>(
                    "opponentSelectors",
                    PurchaseJson.mapper.valueToTree(List(4) { "ORDINARY_MINUS_TWO" }),
                )
            }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun unusedIncumbentExpansionRoleIsRejected() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                (this["expansionImitation"] as ObjectNode).put("incumbentModel", "expansion.json")
            }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun explicitRivalIndicesComposeIndependentPhasesAndKeepTheControlledReference() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.specification
            val lineup =
                specification.lineup(0).copy(seat = 2, opponentIndices = listOf(3, 0, 3, 1))

            val controllers = specification.controllers(lineup)

            assertThat(controllers[0]?.controls("buy-tokens")).isTrue()
            assertThat(controllers[0]?.controls("expand")).isTrue()
            assertThat(controllers[1]?.controls("buy-tokens")).isFalse()
            assertThat(controllers[1]?.controls("expand")).isFalse()
            assertThat(controllers[2]?.controls("buy-tokens")).isFalse()
            assertThat(controllers[2]?.controls("expand")).isTrue()
            assertThat(controllers[3]?.controls("buy-tokens")).isFalse()
            assertThat(controllers[3]?.controls("expand")).isTrue()
            assertThat(
                    controllers.values.all {
                        it.controls("purchase-advances") && it.controls("purchase-shipping")
                    }
                )
                .isTrue()
        }
    }

    @Test
    fun sealedRootKeepsTheOrdinaryControlledChoiceAndUntouchedObservation() {
        TokenOrderOverlayFixture().use { fixture ->
            val specification = fixture.specification
            val lineup =
                specification.lineup(0).copy(seat = 0, opponentIndices = listOf(3, 3, 3, 3))
            val session = ExperimentSession.start(lineup.configuration, 71L)
            while (session.state.stage != Stage.TOKEN_PURCHASE) {
                session.apply(
                    session.actors().associateWith {
                        BotPolicy.choose(session.observation(it), BotDifficulty.HARD, 71)
                    }
                )
            }
            val before = session.state
            val observations = session.actors().associateWith(session::observation)
            val actor = session.state.playerIds[0]
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { specification.arena.candidate },
                    specification.controllers(lineup),
                    71,
                    Long.MAX_VALUE,
                )

            val commands = continuation.commands(session, observations)

            assertThat(commands[actor])
                .isEqualTo(
                    BotPolicy.choose(
                        requireNotNull(observations[actor]) {
                            "The token actor has a visible observation."
                        },
                        BotDifficulty.HARD,
                        71 xor (session.decisions * 65537),
                        specification.arena.candidate,
                    )
                )
            assertThat(session.state).isEqualTo(before)
            assertThat(session.actors().associateWith(session::observation)).isEqualTo(observations)
        }
    }

    @Test
    fun noOpArenaOverlaysPreserveLegacyOriginAndEveryContinuationOutcome() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit { remove("tokenOrder") }
            val enabled = fixture.specification
            val legacy =
                enabled.copy(
                    arena = enabled.arena.copy(expansionImitation = null),
                    continuationProtocol = null,
                    replayOrigins = emptyList(),
                )
            val seed =
                (71..200).first { candidate ->
                    legacy.copy(arena = legacy.arena.copy(seed = candidate)).request(0).ordinal == 1
                }
            val old = legacy.copy(arena = legacy.arena.copy(seed = seed))
            val current = enabled.copy(arena = enabled.arena.copy(seed = seed))

            val expected = TokenOrderCollector(old, "parity", Long.MAX_VALUE).collect(0)
            val actual = TokenOrderCollector(current, "parity", Long.MAX_VALUE).collect(0)

            assertThat(actual.status).isEqualTo(TokenValueOriginStatus.COLLECTED)
            assertThat(actual).isEqualTo(expected)
            TokenOrderReproduction.verifyOrigin(PurchaseJson.mapper.valueToTree(expected), actual)
        }
    }
}
