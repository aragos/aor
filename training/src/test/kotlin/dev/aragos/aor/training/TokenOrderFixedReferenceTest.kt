package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TokenOrderReference
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenOrderFixedReferenceTest {
    @Test
    fun residualCollectionRequiresAnExplicitFixedControlledRole() {
        TokenOrderOverlayFixture().use { fixture ->
            fixture.edit {
                put("continuationProtocol", "ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1")
                put("continuationReplicates", 8)
            }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun residualReferenceControllerIsSharedByPrefixAndContinuation() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val specification = fixture.specification
            val lineup = specification.lineup(0)

            val controllers = specification.controllers(lineup)

            assertThat(specification.reference).isEqualTo(TokenOrderReference.FIXED_16_TO_15)
            assertThat(controllers[lineup.seat]?.controls("buy-tokens")).isTrue()
        }
    }

    @Test
    fun ordinaryEightTailProtocolStillRejectsAControlledFixedOverride() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            fixture.edit { put("continuationProtocol", "ARENA_OVERLAYS_EIGHT_TAILS_1") }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun residualCollectionRejectsAnIncumbentSideOverride() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).put("incumbentSelector", "FIXED_16_TO_15")
            }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun residualMetadataBindsFixedReferenceAndOrdinaryProposalOwner() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val specification = fixture.snapshots()

            val metadata =
                TokenOrderContinuationArtifacts.metadata(specification, fixture.directory)

            assertThat(metadata["tokenReference"])
                .isEqualTo(
                    mapOf(
                        "version" to "fixed-token-reference-1",
                        "selector" to "FIXED_16_TO_15",
                        "proposalReference" to "ordinary-token-reference-1",
                        "ordinaryModelSnapshot" to "initial.json",
                        "ordinaryModelSha256" to
                            PurchaseJson.hash(fixture.directory.resolve("initial.json")),
                    )
                )
        }
    }

    @Test
    fun residualReproductionAcceptsCompleteSkippedOriginsWithoutReferences() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val expected = fixture.completed()

            val actual = TokenOrderReproduction.read(fixture.directory)

            assertThat(actual.specification.identity()).isEqualTo(expected.identity())
        }
    }

    @Test
    fun skippedResidualRootsCannotExposeReferenceIndices() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val specification = fixture.specification
            val node =
                PurchaseJson.mapper.valueToTree<ObjectNode>(
                    fixture.skipped(specification, 0, "fixture")
                )
            node.put("referenceIndex", 0)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderCollectionMain.validateOrigin(node, specification, 0, "fixture")
            }
        }
    }

    @Test
    fun collectedResidualRetainsOrdinaryBaselineAndSeparateFixedReference() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val specification = fixture.specification
            val node = collected(specification)

            TokenOrderCollectionMain.validateOrigin(node, specification, 0, "fixture")

            assertThat(node["observation"]["baselineIndex"].asInt()).isEqualTo(0)
            assertThat(node["referenceIndex"].asInt()).isEqualTo(1)
        }
    }

    @Test
    fun collectedResidualCannotMislabelOrdinaryActionAsItsReference() {
        TokenOrderOverlayFixture().use { fixture ->
            residual(fixture)
            val specification = fixture.specification
            val node = collected(specification)
            node.put("referenceIndex", 0)

            assertFailsWith<IllegalArgumentException> {
                TokenOrderCollectionMain.validateOrigin(node, specification, 0, "fixture")
            }
        }
    }

    private fun collected(specification: TokenOrderSpecification): ObjectNode {
        val lineup = specification.lineup(0)
        val request = specification.request(0)
        val commands = listOf("buy-tokens 16", "buy-tokens 15")
        return PurchaseJson.mapper.valueToTree(
            mapOf(
                "schemaVersion" to 1,
                "signature" to "fixture",
                "index" to 0,
                "split" to "TRAINING",
                "configuration" to lineup.configuration.id,
                "gameSeed" to lineup.seed,
                "seat" to lineup.seat,
                "opponentIndices" to lineup.opponentIndices,
                "requestedEpoch" to request.epoch,
                "requestedOrdinal" to request.ordinal,
                "status" to "COLLECTED",
                "actualEpoch" to request.epoch,
                "rootDecision" to 10,
                "originDecisions" to 10,
                "baselineCommand" to commands[0],
                "referenceIndex" to 1,
                "proposedIndices" to listOf(0, 1),
                "observation" to
                    mapOf(
                        "encoderVersion" to "aor-token-observation-2",
                        "baselineIndex" to 0,
                        "actions" to commands.map { mapOf("command" to it) },
                    ),
                "alternatives" to
                    commands.mapIndexed { index, command ->
                        mapOf(
                            "index" to index,
                            "command" to command,
                            "outcomes" to
                                List(8) { replicate ->
                                    mapOf(
                                        "continuationSeed" to
                                            specification.continuationSeed(lineup.seed, replicate),
                                        "reward" to 0.0,
                                        "winShare" to 0.0,
                                        "rankReward" to 0.0,
                                        "scores" to List(4) { 0 },
                                    )
                                },
                        )
                    },
            )
        )
    }

    private fun residual(fixture: TokenOrderOverlayFixture) {
        fixture.edit {
            put("continuationProtocol", "ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1")
            put("continuationReplicates", 8)
            (this["tokenOrder"] as ObjectNode).put("candidateSelector", "FIXED_16_TO_15")
        }
    }
}
