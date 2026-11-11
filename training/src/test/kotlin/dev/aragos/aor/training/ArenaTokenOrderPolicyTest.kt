package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameState
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryValuePolicy
import dev.aragos.aor.ai.TokenObservationEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaTokenOrderPolicyTest {
    @Test
    fun referenceNeighborSelectorRejectsTheOldResidualEnvelope() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, neighborFields())
            }
        }
    }

    @Test
    fun referenceNeighborEnvelopeCannotRunUnderTheOldResidualSelector() {
        ArenaPurchaseFixture().use { fixture ->
            neighborArtifact(fixture)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, residualFields())
            }
        }
    }

    @Test
    fun referenceNeighborEnvelopeRequiresItsExactSelectorVersion() {
        ArenaPurchaseFixture().use { fixture ->
            neighborArtifact(fixture)
            val node = PurchaseJson.read(fixture.directory.resolve("residual.json")) as ObjectNode
            node.put("selectorVersion", TokenOrderPolicy.SELECTOR_VERSION)
            fixture.artifacts.write("residual.json", node)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, neighborFields())
            }
        }
    }

    @Test
    fun referenceNeighborZeroHeadMatchesExplicitFixedTFallback() {
        ArenaPurchaseFixture().use { fixture ->
            neighborArtifact(fixture)
            val specification =
                tokenRoles(fixture, neighborFields() + ("incumbentSelector" to "FIXED_16_TO_15"))
                    .copy(candidate = testBaseline, incumbent = testBaseline)
            val decision = tokenDecision(fixture)

            val choices =
                ArenaSide.entries.map { side ->
                    specification.controllers(4, 0, side, testBaseline)[0]?.choose(decision)
                }

            assertThat(choices).containsExactly("buy-tokens 15", "buy-tokens 15").inOrder()
        }
    }

    @Test
    fun conditionalStressRivalsUseTheirBoundFourteenAndThirteenRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val policy =
                ArenaTokenOrderPolicy.read(
                    fixture.source,
                    PurchaseJson.mapper.valueToTree(
                        mapOf("opponentSelectors" to listOf("FIXED_16_TO_14", "FIXED_16_TO_13"))
                    ),
                )
            val decision = tokenDecision(fixture)

            val controllers =
                policy.opponentControllers(
                    4,
                    0,
                    listOf(0, 0, 1, 0),
                    listOf(testBaseline, testBaseline),
                )

            assertThat(controllers[1]?.choose(decision)).isEqualTo("buy-tokens 14")
            assertThat(controllers[2]?.choose(decision)).isEqualTo("buy-tokens 13")
        }
    }

    @Test
    fun conditionalStressRivalsLeaveSignedOrdinaryChoicesUnchanged() {
        ArenaPurchaseFixture().use { fixture ->
            val decision = tokenDecision(fixture, amount = -4)

            val choices =
                listOf(ArenaTokenSelector.FIXED_16_TO_14, ArenaTokenSelector.FIXED_16_TO_13).map {
                    TokenOrderController(testBaseline, ArenaTokenRole(it, null)).choose(decision)
                }

            assertThat(choices).containsExactly("buy-tokens -4", "buy-tokens -4").inOrder()
        }
    }

    @Test
    fun conditionalStressRivalNeverClipsAnUnavailableTarget() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = tokenDecision(fixture)
            val decision =
                initial.copy(
                    observation =
                        initial.observation.copy(
                            candidates =
                                initial.observation.candidates.filter {
                                    it.command != "buy-tokens 14"
                                }
                        )
                )
            val controller =
                TokenOrderController(
                    testBaseline,
                    ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_14, null),
                )

            val choice = controller.choose(decision)

            assertThat(choice).isEqualTo("buy-tokens 16")
        }
    }

    private fun neighborFields(): Map<String, Any> =
        mapOf(
            "candidateSelector" to "MODEL_FIXED_T_REFERENCE_NEIGHBORS",
            "candidateModel" to "residual.json",
        )

    private fun neighborArtifact(fixture: ArenaPurchaseFixture) {
        residualArtifact(fixture)
        val node = PurchaseJson.read(fixture.directory.resolve("residual.json")) as ObjectNode
        node.put("kind", TokenOrderPolicy.REFERENCE_NEIGHBOR_MODEL_KIND)
        node.put("proposalVersion", TokenOrderPolicy.REFERENCE_NEIGHBOR_PROPOSAL_VERSION)
        node.put("selectorVersion", TokenOrderPolicy.REFERENCE_NEIGHBOR_SELECTOR_VERSION)
        fixture.artifacts.write("residual.json", node)
    }

    @Test
    fun residualSelectorRejectsAnUntaggedOrdinaryModel() {
        ArenaPurchaseFixture().use { fixture ->
            tokenInput(fixture, "ordinary.json", emptyMap())

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(
                    fixture,
                    mapOf(
                        "candidateSelector" to "MODEL_FIXED_T_RESIDUAL",
                        "candidateModel" to "ordinary.json",
                    ),
                )
            }
        }
    }

    @Test
    fun residualArtifactCannotRunUnderTheOrdinarySelector() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, mapOf("candidateModel" to "residual.json"))
            }
        }
    }

    @Test
    fun residualModelMustBindTheFixedReferenceVersion() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)
            val artifact =
                PurchaseJson.read(fixture.directory.resolve("residual.json")) as ObjectNode
            artifact.put("referenceProtocol", "ordinary-token-reference-1")
            fixture.artifacts.write("residual.json", artifact)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, residualFields())
            }
        }
    }

    @Test
    fun residualModelCannotChangeItsAdvantageMargin() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)
            val artifact =
                PurchaseJson.read(fixture.directory.resolve("residual.json")) as ObjectNode
            artifact.put("minimumAdvantage", 0.0)
            fixture.artifacts.write("residual.json", artifact)

            assertFailsWith<IllegalArgumentException> {
                tokenRoles(fixture, residualFields())
            }
        }
    }

    @Test
    fun residualZeroHeadRoutesToFixedFallbackWhileOrdinarySideRetainsSixteen() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)
            tokenInput(fixture, "ordinary.json", emptyMap())
            val specification =
                tokenRoles(fixture, residualFields() + mapOf("incumbentModel" to "ordinary.json"))
                    .copy(candidate = testBaseline, incumbent = testBaseline)

            val chosen =
                ArenaSide.entries.map { side ->
                    requireNotNull(specification.controllers(4, 0, side, testBaseline)[0]) {
                            "Each evaluated side has an explicit learned token controller."
                        }
                        .choose(tokenDecision(fixture))
                }

            assertThat(chosen).containsExactly("buy-tokens 15", "buy-tokens 16").inOrder()
        }
    }

    @Test
    fun residualSnapshotRetainsTheTaggedArtifactBytes() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)
            val specification = tokenRoles(fixture, residualFields())
            val expected = PurchaseJson.hash(fixture.directory.resolve("residual.json"))

            specification.snapshotOverlays(fixture.artifacts)

            assertThat(
                    PurchaseJson.hash(fixture.directory.resolve("token-order-candidate-model.json"))
                )
                .isEqualTo(expected)
            val metadata = specification.overlayMetadata()["tokenOrder"] as Map<*, *>
            assertThat(metadata["candidateSelector"]).isEqualTo("MODEL_FIXED_T_RESIDUAL")
        }
    }

    @Test
    fun residualHistoricalArenaReplayRetainsTheSameOutcomes() {
        ArenaPurchaseFixture().use { fixture ->
            residualArtifact(fixture)
            val specification = tokenRoles(fixture, residualFields())
            val game = StrategyArena.game(specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE)

            assertThat(replay.game.scores).isEqualTo(game.scores)
            assertThat(replay.game.decisions).isEqualTo(game.decisions)
            assertThat(replay.replayVerified).isTrue()
        }
    }

    private fun residualFields(): Map<String, Any> =
        mapOf("candidateSelector" to "MODEL_FIXED_T_RESIDUAL", "candidateModel" to "residual.json")

    @Test
    fun residualCollectionCanResolveTheActualOrdinaryHeadAlongsideFixedContinuation() {
        ArenaPurchaseFixture().use { fixture ->
            val session = firstToken(fixture)
            val actor = session.state.playerIds.first()
            val observation = tokenDecision(fixture).observation
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { testBaseline },
                    mapOf(
                        0 to
                            TokenOrderController(
                                testBaseline,
                                ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_15, null),
                            )
                    ),
                    71,
                    Long.MAX_VALUE,
                )

            val ordinary = continuation.ordinaryCommand(session, 0, observation)
            val fixed = continuation.commands(session, mapOf(actor to observation))[actor]

            assertThat(ordinary).isEqualTo("buy-tokens 16")
            assertThat(fixed).isEqualTo("buy-tokens 15")
        }
    }

    private fun residualArtifact(fixture: ArenaPurchaseFixture) {
        tokenInput(fixture, "raw.json", emptyMap())
        fixture.artifacts.write(
            "residual.json",
            mapOf(
                "schemaVersion" to 1,
                "kind" to TokenOrderPolicy.RESIDUAL_MODEL_KIND,
                "referenceProtocol" to "fixed-token-reference-1",
                "proposalVersion" to TokenOrderPolicy.PROPOSAL_VERSION,
                "minimumAdvantage" to TokenOrderPolicy.MINIMUM_ADVANTAGE,
                "model" to PurchaseJson.read(fixture.directory.resolve("raw.json")),
            ),
        )
    }

    @Test
    fun evaluatedMinusTwoReusesTheRivalControlAndRetainsTheOtherSide() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                tokenRoles(
                        fixture,
                        mapOf(
                            "candidateSelector" to "ORDINARY_MINUS_TWO",
                            "incumbentSelector" to "FIXED_16_TO_15",
                            "opponentSelectors" to listOf("ORDINARY_MINUS_TWO"),
                        ),
                    )
                    .copy(
                        candidate = testBaseline,
                        incumbent = testBaseline,
                        opponents = listOf(testBaseline),
                    )
            val decision = tokenDecision(fixture)
            val candidate =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    testBaseline,
                    listOf(0, 0, 0, 0),
                )
            val incumbent =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.INCUMBENT,
                    testBaseline,
                    listOf(0, 0, 0, 0),
                )

            assertThat(candidate[0]?.choose(decision)).isEqualTo("buy-tokens 14")
            assertThat(candidate[1]?.choose(decision)).isEqualTo(candidate[0]?.choose(decision))
            assertThat(incumbent[0]?.choose(decision)).isEqualTo("buy-tokens 15")
            assertThat(candidate[0]?.controls("purchase-advances")).isTrue()
        }
    }

    @Test
    fun evaluatedMinusTwoRetainsTheHeadWhenTheExactAlternativeIsMissing() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = tokenDecision(fixture)
            val decision =
                initial.copy(
                    observation =
                        initial.observation.copy(
                            candidates =
                                initial.observation.candidates.filter {
                                    it.command != "buy-tokens 14"
                                }
                        )
                )
            val controller =
                TokenOrderController(
                    testBaseline,
                    ArenaTokenRole(ArenaTokenSelector.ORDINARY_MINUS_TWO, null),
                )

            assertThat(controller.choose(decision)).isEqualTo("buy-tokens 16")
        }
    }

    @Test
    fun evaluatedMinusTwoReplaysTheExactWholeGameWithItsBoundRole() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                tokenRoles(fixture, mapOf("candidateSelector" to "ORDINARY_MINUS_TWO"))
            val game = StrategyArena.game(specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE)

            assertThat(replay.replayVerified).isTrue()
            assertThat(replay.game.scores).isEqualTo(game.scores)
            assertThat(replay.game.decisions).isEqualTo(game.decisions)
            assertThat(specification.overlayMetadata()["tokenOrder"])
                .isEqualTo(mapOf("candidateSelector" to "ORDINARY_MINUS_TWO"))
            assertThat(specification.identity()).contains("ORDINARY_MINUS_TWO")
        }
    }

    @Test
    fun explicitSidesSelectDifferentTokenActorsEvenWithEqualOrdinaryModels() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = withTokenRoles(fixture)
            val session = firstToken(fixture)
            val actor = session.state.playerIds.first()
            val decision =
                SimulationDecision(
                    0,
                    0,
                    71,
                    session.state.viewForPlayer(actor),
                    session.observation(actor),
                )

            val choices =
                ArenaSide.entries.map { side ->
                    val controllers = specification.controllers(4, 0, side, specification.candidate)
                    requireNotNull(controllers[0]) { "The evaluated seat has a controller." }
                        .choose(decision)
                }

            assertThat(specification.candidate).isEqualTo(specification.incumbent)
            assertThat(choices).containsExactly("buy-tokens 0", "buy-tokens 36").inOrder()
        }
    }

    @Test
    fun rivalsKeepOrdinaryTokenDecisionsAndFrozenPurchaseControllers() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = withTokenRoles(fixture)

            val controllers =
                specification.controllers(4, 0, ArenaSide.CANDIDATE, specification.candidate)

            for (seat in 1..3) {
                val controller =
                    requireNotNull(controllers[seat]) { "Every seat retains frozen Purchase." }
                assertThat(controller.controls("buy-tokens")).isFalse()
                assertThat(controller.controls("purchase-shipping")).isTrue()
                assertThat(controller.controls("purchase-advances")).isTrue()
                assertThat(controller.controls("expand")).isFalse()
            }
        }
    }

    @Test
    fun anOmittedIncumbentRoleRetainsItsExactOrdinaryTokenPolicy() {
        ArenaPurchaseFixture().use { fixture ->
            withTokenRoles(fixture)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["tokenOrder"] as ObjectNode).remove("incumbentModel")
            fixture.artifacts.write("arena.json", source)
            val specification = fixture.specification

            val controllers =
                specification.controllers(4, 0, ArenaSide.INCUMBENT, specification.incumbent)

            assertThat(
                    requireNotNull(controllers[0]) { "The seat retains Purchase." }
                        .controls("buy-tokens")
                )
                .isFalse()
        }
    }

    @Test
    fun modelOnlyCallsCannotGuessTokenRolesFromEqualOrdinaryModels() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = withTokenRoles(fixture)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    StrategyArena.game(specification, 0, specification.candidate, Long.MAX_VALUE)
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun roleSnapshotsAreImmutableAndBoundIntoNativeIdentity() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = withTokenRoles(fixture)
            val output = TrainingArtifacts(fixture.directory.resolve("snapshots"))
            specification.snapshotOverlays(output)
            val snapshot = output.directory.resolve("token-order-candidate-model.json")
            val before = Files.readAllBytes(snapshot)
            Files.writeString(
                fixture.directory.resolve("candidate-token.json"),
                Files.readString(snapshot) + "\n",
            )
            val changed = fixture.specification

            assertFailsWith<IllegalArgumentException> { changed.snapshotOverlays(output) }

            assertThat(changed.identity()).isNotEqualTo(specification.identity())
            assertThat(Files.readAllBytes(snapshot)).isEqualTo(before)
        }
    }

    @Test
    fun exactArenaReplayRetainsTheTokenOverlayAndCanonicalOutcomes() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = withTokenRoles(fixture)
            val game = StrategyArena.game(specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE)

            assertThat(replay.replayVerified).isTrue()
            assertThat(replay.game.scores).isEqualTo(game.scores)
            assertThat(replay.game.decisions).isEqualTo(game.decisions)
            assertThat(
                    replay.decisions
                        .filter { it.snapshot.decision == "buy-tokens" }
                        .map { it.chosen.command }
                )
                .contains("buy-tokens 0")
        }
    }

    @Test
    fun explicitModelSelectorsPreserveLegacyIdentityMetadataAndChoices() {
        ArenaPurchaseFixture().use { fixture ->
            val legacy = withTokenRoles(fixture)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            val roles = source["tokenOrder"] as ObjectNode
            roles.put("candidateSelector", "MODEL")
            roles.put("incumbentSelector", "MODEL")
            fixture.artifacts.write("arena.json", source)
            val explicit = fixture.specification
            val policy = requireNotNull(legacy.tokenOrder) { "The fixture has token roles." }

            assertThat(policy.identity())
                .isEqualTo(
                    "${TokenOrderPolicy.SELECTOR_VERSION}:${TokenOrderPolicy.PROPOSAL_VERSION}:" +
                        "CANDIDATE=${PurchaseJson.hash(fixture.directory.resolve("candidate-token.json"))}:" +
                        "INCUMBENT=${PurchaseJson.hash(fixture.directory.resolve("incumbent-token.json"))}"
                )
            assertThat(explicit.identity()).isEqualTo(legacy.identity())
            assertThat(explicit.overlayMetadata()).isEqualTo(legacy.overlayMetadata())
            val decision = tokenDecision(fixture)
            assertThat(
                    explicit
                        .controllers(4, 0, ArenaSide.CANDIDATE, explicit.candidate)[0]
                        ?.choose(decision)
                )
                .isEqualTo(
                    legacy
                        .controllers(4, 0, ArenaSide.CANDIDATE, legacy.candidate)[0]
                        ?.choose(decision)
                )
        }
    }

    @Test
    fun adjacentVetoRetainsTheChosenLowerNeighbor() {
        ArenaPurchaseFixture().use { fixture ->
            val role =
                ArenaTokenRole(
                    ArenaTokenSelector.ADJACENT_VETO,
                    tokenInput(fixture, "neighbor.json", mapOf(15 to 1.0)),
                )

            val chosen = TokenOrderController(testBaseline, role).choose(tokenDecision(fixture))

            assertThat(chosen).isEqualTo("buy-tokens 15")
        }
    }

    @Test
    fun adjacentVetoRetainsTheChosenUpperNeighbor() {
        ArenaPurchaseFixture().use { fixture ->
            val role =
                ArenaTokenRole(
                    ArenaTokenSelector.ADJACENT_VETO,
                    tokenInput(fixture, "neighbor.json", mapOf(17 to 1.0)),
                )

            val chosen = TokenOrderController(testBaseline, role).choose(tokenDecision(fixture))

            assertThat(chosen).isEqualTo("buy-tokens 17")
        }
    }

    @Test
    fun vetoedLongJumpDoesNotReselectAProfitableNeighbor() {
        ArenaPurchaseFixture().use { fixture ->
            val role =
                ArenaTokenRole(
                    ArenaTokenSelector.ADJACENT_VETO,
                    tokenInput(fixture, "jump.json", mapOf(0 to 1.0, 15 to .5)),
                )
            val decision = tokenDecision(fixture)
            val unrestricted =
                TokenOrderController(testBaseline, role.copy(selector = ArenaTokenSelector.MODEL))

            val chosen = TokenOrderController(testBaseline, role).choose(decision)

            assertThat(unrestricted.choose(decision)).isEqualTo("buy-tokens 0")
            assertThat(chosen).isEqualTo("buy-tokens 16")
        }
    }

    @Test
    fun adjacentVetoPreservesTheOriginalQAdvantageCutoff() {
        ArenaPurchaseFixture().use { fixture ->
            val role =
                ArenaTokenRole(
                    ArenaTokenSelector.ADJACENT_VETO,
                    tokenInput(fixture, "small.json", mapOf(15 to .01)),
                )

            val chosen = TokenOrderController(testBaseline, role).choose(tokenDecision(fixture))

            assertThat(chosen).isEqualTo("buy-tokens 16")
        }
    }

    @Test
    fun fixedControlUsesTheActualTokenHeadChoiceInsteadOfTheOrdinaryScoreWinner() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = tokenDecision(fixture, amount = 8)
            val observation =
                initial.observation.copy(
                    candidates =
                        initial.observation.candidates.map { candidate ->
                            candidate.copy(
                                features =
                                    candidate.features +
                                        (BotTokenValue.featureNames.first() to
                                            if (candidate.command == "buy-tokens 16") 1.0 else 0.0)
                            )
                        }
                )
            val baseline =
                BotModel(
                    weights =
                        testBaseline.weights +
                            mapOf(
                                BotTokenValue.GATE to 1.0,
                                BotTokenValue.featureNames.first() to 1.0,
                            )
                )
            val controller =
                TokenOrderController(
                    baseline,
                    ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_15, null),
                )

            val chosen = controller.choose(initial.copy(observation = observation))

            assertThat(BotPolicy.choose(observation, BotDifficulty.NORMAL, initial.seed, baseline))
                .isEqualTo("buy-tokens 8")
            assertThat(chosen).isEqualTo("buy-tokens 15")
        }
    }

    @Test
    fun fixedControlRetainsTheBaselineWhenFifteenIsNotOffered() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = tokenDecision(fixture)
            val decision =
                initial.copy(
                    view =
                        initial.view.copy(
                            tokenPurchaseOffers =
                                initial.view.tokenPurchaseOffers.filter { it.amount != 15 }
                        ),
                    observation =
                        initial.observation.copy(
                            candidates =
                                initial.observation.candidates.filter {
                                    it.command != "buy-tokens 15"
                                }
                        ),
                )
            val controller =
                TokenOrderController(
                    testBaseline,
                    ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_15, null),
                )

            val chosen = controller.choose(decision)

            assertThat(chosen).isEqualTo("buy-tokens 16")
        }
    }

    @Test
    fun fixedControlLeavesOtherOrdinaryAmountsUnchanged() {
        ArenaPurchaseFixture().use { fixture ->
            val controller =
                TokenOrderController(
                    testBaseline,
                    ArenaTokenRole(ArenaTokenSelector.FIXED_16_TO_15, null),
                )

            val chosen = controller.choose(tokenDecision(fixture, amount = 17))

            assertThat(chosen).isEqualTo("buy-tokens 17")
        }
    }

    @Test
    fun aFixedRoleNeedsNoModelAndProducesNoUnusedModelSnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = tokenRoles(fixture, mapOf("candidateSelector" to "FIXED_16_TO_15"))
            val output = TrainingArtifacts(fixture.directory.resolve("fixed-snapshots"))

            specification.snapshotOverlays(output)

            assertThat(specification.overlayMetadata()["tokenOrder"])
                .isEqualTo(mapOf("candidateSelector" to "FIXED_16_TO_15"))
            assertThat(Files.exists(output.directory.resolve("token-order-candidate-model.json")))
                .isFalse()
            assertThat(
                    requireNotNull(specification.tokenOrder) {
                            "The fixed control has a token role."
                        }
                        .identity()
                )
                .contains("FIXED_16_TO_15")
        }
    }

    @Test
    fun candidateAndIncumbentSelectorsRouteIndependentlyWithEqualOrdinaryModels() {
        ArenaPurchaseFixture().use { fixture ->
            tokenInput(fixture, "jump.json", mapOf(0 to 1.0))
            val specification =
                tokenRoles(
                        fixture,
                        mapOf(
                            "candidateSelector" to "FIXED_16_TO_15",
                            "incumbentSelector" to "ADJACENT_VETO",
                            "incumbentModel" to "jump.json",
                        ),
                    )
                    .copy(candidate = testBaseline, incumbent = testBaseline)
            val decision = tokenDecision(fixture)

            val chosen =
                ArenaSide.entries.map { side ->
                    requireNotNull(specification.controllers(4, 0, side, testBaseline)[0]) {
                            "The evaluated seat has an explicit token selector."
                        }
                        .choose(decision)
                }

            assertThat(chosen).containsExactly("buy-tokens 15", "buy-tokens 16").inOrder()
        }
    }

    @Test
    fun selectorsRejectMissingUnusedAndUnknownModelRoles() {
        ArenaPurchaseFixture().use { fixture ->
            tokenInput(fixture, "model.json", mapOf(0 to 1.0))
            val invalid =
                listOf(
                    mapOf("candidateSelector" to "MODEL"),
                    mapOf("candidateSelector" to "ADJACENT_VETO"),
                    mapOf(
                        "candidateSelector" to "FIXED_16_TO_15",
                        "candidateModel" to "model.json",
                    ),
                    mapOf(
                        "candidateSelector" to "ORDINARY_MINUS_TWO",
                        "candidateModel" to "model.json",
                    ),
                    mapOf("candidateSelector" to "UNKNOWN"),
                )

            invalid.forEach { fields ->
                assertFailsWith<IllegalArgumentException> { tokenRoles(fixture, fields) }
            }
        }
    }

    @Test
    fun selectorChangesAreBoundIntoIdentityAndReplayMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            tokenInput(fixture, "model.json", mapOf(15 to 1.0))
            val original = tokenRoles(fixture, mapOf("candidateModel" to "model.json"))
            val changed =
                tokenRoles(
                    fixture,
                    mapOf(
                        "candidateModel" to "model.json",
                        "candidateSelector" to "ADJACENT_VETO",
                    ),
                )

            assertThat(changed.identity()).isNotEqualTo(original.identity())
            val metadata = changed.overlayMetadata()["tokenOrder"] as Map<*, *>
            assertThat(metadata["candidateSelector"]).isEqualTo("ADJACENT_VETO")
            assertThat(metadata["candidateModel"])
                .isEqualTo(
                    (original.overlayMetadata()["tokenOrder"] as Map<*, *>)["candidateModel"]
                )
        }
    }

    @Test
    fun diagnosticSelectorsReplayTheSameWholeGameOutcomes() {
        ArenaPurchaseFixture().use { fixture ->
            tokenInput(fixture, "model.json", mapOf(15 to 1.0))
            for (selector in listOf("ADJACENT_VETO", "FIXED_16_TO_15")) {
                val fields =
                    mapOf("candidateSelector" to selector) +
                        if (selector == "ADJACENT_VETO") mapOf("candidateModel" to "model.json")
                        else emptyMap()
                val specification = tokenRoles(fixture, fields)
                val game = StrategyArena.game(specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)

                val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE)

                assertThat(replay.game.scores).isEqualTo(game.scores)
                assertThat(replay.game.decisions).isEqualTo(game.decisions)
                assertThat(replay.replayVerified).isTrue()
            }
        }
    }

    @Test
    fun tokenReplayReportsTheActualOrdinaryHeadBaselineBeforeTheOverlay() {
        ArenaPurchaseFixture().use { fixture ->
            val baseline =
                BotModels.default.copy(
                    weights =
                        BotModels.default.weights +
                            mapOf(
                                BotTokenValue.GATE to 1.0,
                                BotTokenValue.featureNames.first() to -10.0,
                            )
                )
            val specification = withTokenRoles(fixture).copy(candidate = baseline)
            val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.CANDIDATE)
            val decision = replay.decisions.first { it.snapshot.decision == "buy-tokens" }
            val state =
                replay.entries.take(decision.snapshotEntryCount).fold(GameState(replay.header)) {
                    current,
                    text ->
                    current.step(TextCodec.parse(text))
                }
            val observation = BotObservations.from(state, state.playerIds[replay.seat])
            val scoreWinner = observation.candidates.maxBy { BotPolicy.score(it, baseline) }.command

            assertThat(decision.ordinaryTokenBaseline).isEqualTo("buy-tokens -4")
            assertThat(decision.ordinaryTokenBaseline).isNotEqualTo(scoreWinner)
            assertThat(decision.chosen.command).isEqualTo("buy-tokens 0")
            val other = replay.decisions.first { it.snapshot.decision != "buy-tokens" }
            assertThat(
                    PurchaseJson.mapper.valueToTree<JsonNode>(other).has("ordinaryTokenBaseline")
                )
                .isFalse()
        }
    }

    private val testBaseline = BotModel(weights = mapOf("test.baseline" to 1.0))

    private fun tokenDecision(fixture: ArenaPurchaseFixture, amount: Int = 16): SimulationDecision {
        val session = firstToken(fixture)
        val actor = session.state.playerIds.first()
        val observation = session.observation(actor)
        return SimulationDecision(
            0,
            0,
            71,
            session.state.viewForPlayer(actor),
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features +
                                    ("test.baseline" to
                                        if (candidate.command == "buy-tokens $amount") 1.0 else 0.0)
                        )
                    }
            ),
        )
    }

    private fun tokenRoles(
        fixture: ArenaPurchaseFixture,
        fields: Map<String, Any>,
    ): ArenaSpecification {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.set<JsonNode>("tokenOrder", PurchaseJson.mapper.valueToTree(fields))
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }

    private fun tokenInput(
        fixture: ArenaPurchaseFixture,
        name: String,
        scores: Map<Int, Double>,
    ): TerritoryValueInput {
        val dictionary =
            TokenObservationEncoder.dictionary(
                PublicCatalog.forPlayers(4, fixture.configuration.rules),
                TerritoryEncoder.TOKEN_2,
            )
        val choices = scores.entries.toList()
        fixture.artifacts.write(
            name,
            mapOf(
                "schemaVersion" to 1,
                "architecture" to TerritoryValuePolicy.ARCHITECTURE,
                "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
                "hiddenSize" to 64,
                "stateFeatureNames" to dictionary.stateFeatureNames,
                "actionFeatureNames" to dictionary.actionFeatureNames,
                "stateWeights" to List(64 * dictionary.stateFeatureNames.size) { 0.0 },
                "stateBias" to List(64) { 0.0 },
                "actionWeights" to
                    List(64 * dictionary.actionFeatureNames.size) { index ->
                        val choice = choices.getOrNull(index / dictionary.actionFeatureNames.size)
                        if (
                            choice != null &&
                                index % dictionary.actionFeatureNames.size ==
                                    dictionary.actionFeatureNames.indexOf(
                                        "bid.amount.${choice.key}"
                                    )
                        )
                            8.0
                        else 0.0
                    },
                "actionBias" to List(64) { 0.0 },
                "outputWeights" to List(64) { choices.getOrNull(it)?.value ?: 0.0 },
                "outputBias" to .5,
            ),
        )
        val path = fixture.directory.resolve(name)
        return TerritoryValueInput(
            path,
            PurchaseJson.hash(path),
            TerritoryValuePolicyInput.read(path),
        )
    }

    private fun withTokenRoles(fixture: ArenaPurchaseFixture): ArenaSpecification {
        tokenInput(fixture, "candidate-token.json", mapOf(0 to 1.0))
        tokenInput(fixture, "incumbent-token.json", mapOf(36 to 1.0))
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.set<JsonNode>(
            "tokenOrder",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "candidateModel" to "candidate-token.json",
                    "incumbentModel" to "incumbent-token.json",
                )
            ),
        )
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }

    private fun firstToken(fixture: ArenaPurchaseFixture): ExperimentSession {
        val session = ExperimentSession.start(fixture.configuration, 71L)
        while (session.state.stage != Stage.TOKEN_PURCHASE) {
            check(session.decisions < 100) { "The fixture must reach its token batch." }
            session.apply(
                session.actors().associateWith { actor ->
                    BotPolicy.choose(
                        session.observation(actor),
                        BotDifficulty.HARD,
                        71 xor session.decisions,
                    )
                }
            )
        }
        return session
    }
}
