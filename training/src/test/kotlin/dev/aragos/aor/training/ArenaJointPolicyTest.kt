package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Synthetic decisions and serialized records only; no arena worlds or actor fitting. */
class ArenaJointPolicyTest {
    @Test
    fun omittedJointRolesPreserveTheLegacyIdentityAndMetadata() {
        ArenaJointFixture().use { fixture ->
            fixture.edit { remove("jointPolicy") }
            val parsed = fixture.specification
            val expected =
                ArenaSpecification(
                    ArenaPurpose.SCREENING,
                    listOf(fixture.base.configuration),
                    fixture.ordinary,
                    fixture.ordinary,
                    listOf(fixture.rival),
                    8,
                    71,
                )

            assertThat(parsed.identity()).isEqualTo(expected.identity())
            assertThat(parsed.overlayMetadata()).isEqualTo(expected.overlayMetadata())
            assertThat(json(StrategyArena.pair(parsed, 0, terminal, terminal)).has("jointPolicy"))
                .isFalse()
        }
    }

    @Test
    fun everyBundleRoleIsRequired() {
        ArenaJointFixture().use { fixture ->
            fixture.edit { (this["jointPolicy"] as ObjectNode).remove("incumbentBundle") }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun explicitNullJointRolesAreRejected() {
        ArenaJointFixture().use { fixture ->
            fixture.edit { putNull("jointPolicy") }

            assertFailsWith<IllegalArgumentException> { fixture.specification }
        }
    }

    @Test
    fun purchaseAndJointRolesCannotOverlap() {
        ArenaJointFixture().use { fixture ->
            fixture.edit {
                set<JsonNode>(
                    "frozenPurchase",
                    json(
                        mapOf(
                            "baseline" to "baseline.json",
                            "policy" to "purchase-policy.json",
                        )
                    ),
                )
            }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("mutually exclusive")
        }
    }

    @Test
    fun purchasePriorMustMatchEveryBundle() {
        ArenaJointFixture().use { fixture ->
            fixture.edit { (this["jointPolicy"] as ObjectNode).put("baseline", "ordinary.json") }

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("exact F prior")
        }
    }

    @Test
    fun componentBytesCannotChangeUnderAnExistingBundleHash() {
        ArenaJointFixture().use { fixture ->
            val file = fixture.base.artifacts.directory.resolve("card-policy.json")
            Files.writeString(file, Files.readString(file) + "\n")

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("raw model fingerprint")
        }
    }

    @Test
    fun rolesSnapshotExactRawBundlesAndComponentsIncludingNullCardAnchors() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val output = TrainingArtifacts(fixture.base.output)

            specification.snapshotOverlays(output)

            val metadata = json(specification.overlayMetadata())["jointPolicy"]
            assertThat(metadata["version"].asText()).isEqualTo("arena-joint-policy-1")
            assertThat(metadata["sharedBundle"]["card"].isNull).isTrue()
            assertThat(metadata["incumbentBundle"]["card"].isNull).isTrue()
            val inputs =
                listOf(metadata["source"], metadata["baseline"]) +
                    listOf("sharedBundle", "candidateBundle", "incumbentBundle").flatMap { role ->
                        listOf(metadata[role], metadata[role]["purchase"]) +
                            listOfNotNull(metadata[role]["card"].takeUnless(JsonNode::isNull))
                    }
            for (input in inputs) {
                assertThat(PurchaseJson.hash(output.directory.resolve(input["snapshot"].asText())))
                    .isEqualTo(input["sha256"].asText())
            }
            assertThat(Files.exists(output.directory.resolve("joint-shared-card-policy.json")))
                .isFalse()
        }
    }

    @Test
    fun changedSourceBytesChangeTheSignatureAndCannotBeSnapshottedUnderTheOldBinding() {
        ArenaJointFixture().use { fixture ->
            val original = fixture.specification
            Files.writeString(fixture.source, Files.readString(fixture.source) + "\n")

            assertThat(fixture.specification.identity()).isNotEqualTo(original.identity())
            assertFailsWith<IllegalArgumentException> {
                original.snapshotOverlays(TrainingArtifacts(fixture.base.output))
            }
        }
    }

    @Test
    fun changedSnapshotIsNotOverwritten() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val output = TrainingArtifacts(fixture.base.output)
            specification.snapshotOverlays(output)
            Files.writeString(
                output.directory.resolve("joint-candidate-card-policy.json"),
                "changed",
            )

            assertFailsWith<IllegalArgumentException> { specification.snapshotOverlays(output) }
        }
    }

    @Test
    fun swappingExplicitRolesChangesTheArenaIdentity() {
        ArenaJointFixture().use { fixture ->
            val original = fixture.specification
            fixture.edit {
                (this["jointPolicy"] as ObjectNode)
                    .put("candidateBundle", "rival.json")
                    .put("incumbentBundle", "bundle.json")
            }

            assertThat(fixture.specification.identity()).isNotEqualTo(original.identity())
        }
    }

    @Test
    fun jointRolesCannotGuessTheSideFromEqualOrdinaryModels() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    specification.controllers(
                        4,
                        0,
                        null,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun rivalsRequireActualOpponentIndices() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification

            assertFailsWith<IllegalArgumentException> {
                specification.controllers(4, 0, ArenaSide.CANDIDATE, specification.candidate)
            }
        }
    }

    @Test
    fun rawCardRivalsFollowIndexedOrdinaryModelsRatherThanSeats() {
        ArenaJointFixture().use { fixture ->
            val specification =
                fixture.specification.copy(opponents = listOf(fixture.ordinary, fixture.rival))
            val indices = listOf(1, 0, 1, 0)
            val controllers =
                specification.controllers(
                    4,
                    3,
                    ArenaSide.INCUMBENT,
                    specification.incumbent,
                    indices,
                )
            val decision = fixture.cardDecision()

            val actual =
                (0..3).map { seat -> controllers[seat]?.choose(decision.copy(seat = seat)) }
            val expected =
                (0..3).map { seat ->
                    BotPolicy.choose(
                        decision.observation,
                        BotDifficulty.HARD,
                        decision.seed,
                        if (seat == 3) specification.incumbent
                        else specification.opponents[indices[seat]],
                    )
                }

            assertThat(expected.distinct()).hasSize(2)
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun nullCardIncumbentIsExactlyOrdinaryHardForEverySeed() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val controller =
                requireNotNull(
                    specification
                        .controllers(
                            4,
                            0,
                            ArenaSide.INCUMBENT,
                            specification.incumbent,
                            listOf(0, 0, 0, 0),
                        )[0]
                ) {
                    "The evaluated joint controller is required."
                }
            val decision = fixture.cardDecision()

            assertThat((0..127).map { controller.choose(decision.copy(seed = it)) })
                .isEqualTo(
                    (0..127).map {
                        BotPolicy.choose(
                            decision.observation,
                            BotDifficulty.HARD,
                            it,
                            specification.incumbent,
                        )
                    }
                )
        }
    }

    @Test
    fun sampledCardActorRetainsSeededNonGreedyCommands() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val controller =
                requireNotNull(
                    specification
                        .controllers(
                            4,
                            0,
                            ArenaSide.CANDIDATE,
                            specification.candidate,
                            listOf(0, 0, 0, 0),
                        )[0]
                ) {
                    "The evaluated joint controller is required."
                }
            val decision = fixture.cardDecision()
            val model =
                CardPolicyInput.read(fixture.base.artifacts.directory.resolve("card-policy.json"))
            val expected =
                (0..127).map { seed ->
                    val recommendation =
                        BotPolicy.choose(
                            decision.observation,
                            BotDifficulty.HARD,
                            seed,
                            specification.candidate,
                        )
                    val encoded =
                        CardObservationEncoder.from(
                            decision.view,
                            PlayerId("p1"),
                            decision.observation,
                            recommendation,
                        )
                    encoded.actions[
                            CardPolicy.choose(encoded, model, CardPolicy.actorSeed(seed)).index]
                        .command
                }

            val actual = (0..127).map { controller.choose(decision.copy(seed = it)) }

            assertThat(expected.distinct()).hasSize(2)
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun purchaseSamplingUsesTheIndependentPriorOnBothSides() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val controller =
                requireNotNull(
                    specification
                        .controllers(
                            4,
                            0,
                            ArenaSide.CANDIDATE,
                            specification.candidate,
                            listOf(0, 0, 0, 0),
                        )[0]
                ) {
                    "The evaluated joint controller is required."
                }
            val reference =
                PurchaseActorController(
                    TrainingArtifacts.readModel(
                        fixture.base.artifacts.directory.resolve("baseline.json")
                    ),
                    PurchasePolicyInput.read(
                        fixture.base.artifacts.directory.resolve("purchase-policy.json")
                    ),
                )
            val decision = fixture.purchaseDecision()

            assertThat((0..127).map { controller.choose(decision.copy(seed = it)) })
                .isEqualTo((0..127).map { reference.choose(decision.copy(seed = it)) })
        }
    }

    @Test
    fun purchaseOverridesStayOnTheirEvaluatedSideWhileSharedRivalsStayFrozen() {
        ArenaJointFixture().use { fixture ->
            fixture.purchaseOverrides()
            val specification = fixture.specification
            val decision = fixture.purchaseDecision()
            val prior =
                TrainingArtifacts.readModel(
                    fixture.base.artifacts.directory.resolve("baseline.json")
                )
            for (side in ArenaSide.entries) {
                val controllers =
                    specification.controllers(
                        4,
                        2,
                        side,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )
                val expected =
                    PurchaseActorController(
                        prior,
                        PurchasePolicyInput.read(
                            fixture.base.artifacts.directory.resolve(
                                "${side.name.lowercase()}-purchase.json"
                            )
                        ),
                    )
                val rival =
                    PurchaseActorController(
                        prior,
                        PurchasePolicyInput.read(
                            fixture.base.artifacts.directory.resolve("purchase-policy.json")
                        ),
                    )
                assertThat(
                        (0..31).map { controllers[2]?.choose(decision.copy(seat = 2, seed = it)) }
                    )
                    .isEqualTo((0..31).map { expected.choose(decision.copy(seed = it)) })
                assertThat(
                        (0..31).map { controllers[1]?.choose(decision.copy(seat = 1, seed = it)) }
                    )
                    .isEqualTo((0..31).map { rival.choose(decision.copy(seed = it)) })
            }
            val candidate =
                specification
                    .controllers(
                        4,
                        2,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[2]
            val incumbent =
                specification
                    .controllers(
                        4,
                        2,
                        ArenaSide.INCUMBENT,
                        specification.incumbent,
                        listOf(0, 0, 0, 0),
                    )[2]
            assertThat(candidate?.choose(decision)).isNotEqualTo(incumbent?.choose(decision))
        }
    }

    @Test
    fun existingTokenExpansionOverlaysRetainJointCardAndPurchaseRouting() {
        ArenaJointFixture().use { fixture ->
            fixture.overlays()
            val specification = fixture.specification
            val controller =
                requireNotNull(
                    specification
                        .controllers(
                            4,
                            0,
                            ArenaSide.INCUMBENT,
                            specification.incumbent,
                            listOf(0, 0, 0, 0),
                        )[0]
                ) {
                    "The evaluated joint controller is required."
                }
            val decision = fixture.cardDecision()

            assertThat(controller.controls("expand")).isTrue()
            assertThat(controller.controls("buy-tokens")).isTrue()
            assertThat(controller.controls("play-cards")).isTrue()
            assertThat(controller.controls("purchase-advances")).isTrue()
            assertThat(controller.controls("bid-capital")).isFalse()
            assertThat(controller.choose(decision))
                .isEqualTo(
                    BotPolicy.choose(
                        decision.observation,
                        BotDifficulty.HARD,
                        decision.seed,
                        specification.incumbent,
                    )
                )
        }
    }

    @Test
    fun recordedPairsBindAllJointRolesBeforeReplay() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val pair = StrategyArena.pair(specification, 0, terminal, terminal)
            fixture.base.artifacts.write("pair.json", pair)
            val recorded =
                StrategyReplayPair.read(fixture.base.artifacts.directory.resolve("pair.json"))

            assertThat(recorded.opponentIndices(specification, 0)).isEqualTo(pair.opponentIndices)
            assertThat(json(pair)["jointPolicy"])
                .isEqualTo(json(specification.overlayMetadata())["jointPolicy"])
        }
    }

    @Test
    fun replayRejectsMissingHistoricalJointBinding() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val pair = json(StrategyArena.pair(specification, 0, terminal, terminal)) as ObjectNode
            pair.remove("jointPolicy")
            fixture.base.artifacts.write("pair.json", pair)
            val recorded =
                StrategyReplayPair.read(fixture.base.artifacts.directory.resolve("pair.json"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    recorded.opponentIndices(specification, 0)
                }

            assertThat(failure).hasMessageThat().contains("joint actor roles differ")
        }
    }

    @Test
    fun replayRejectsChangedJointRoleAssignments() {
        ArenaJointFixture().use { fixture ->
            fixture.base.artifacts.write(
                "pair.json",
                StrategyArena.pair(fixture.specification, 0, terminal, terminal),
            )
            val recorded =
                StrategyReplayPair.read(fixture.base.artifacts.directory.resolve("pair.json"))
            fixture.edit {
                (this["jointPolicy"] as ObjectNode).put("candidateBundle", "rival.json")
            }

            assertFailsWith<IllegalArgumentException> {
                recorded.opponentIndices(fixture.specification, 0)
            }
        }
    }

    @Test
    fun unrelatedCollectionAndSearchCannotSilentlyDropJointRoles() {
        ArenaJointFixture().use { fixture ->
            val specification = fixture.specification
            val factories =
                listOf<() -> Any>(
                    { TokenOrderSpecification(specification) },
                    { TokenValueSpecification(specification) },
                    { ExpansionOpportunitySpecification(specification) },
                    { StrategySearchSpecification(specification) },
                )

            assertThat(factories.map { assertFailsWith<IllegalArgumentException> { it() }.message })
                .containsExactlyElementsIn(
                    List(4) {
                        "Joint actor roles are supported only by complete arena and strategy replay."
                    }
                )
        }
    }

    private fun json(value: Any): JsonNode = PurchaseJson.mapper.valueToTree(value)

    companion object {
        private val terminal = ArenaGame(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
    }
}

private class ArenaJointFixture : AutoCloseable {
    val base =
        JointCollectionFixture(
            configuration =
                TrainingMatrix.configurations.single {
                    it.id == "players-4/standard/no-crusades/deck-true/art-true"
                }
        )
    val ordinary =
        BotModels.default.copy(weights = BotModels.default.weights + ("points" to -100.0))
    val rival = BotModels.default.copy(weights = BotModels.default.weights + ("points" to 100.0))
    val source = base.artifacts.directory.resolve("arena.json")

    init {
        base.artifacts.model(ordinary, "ordinary.json")
        base.artifacts.model(rival, "ordinary-rival.json")
        base.artifacts.write(
            "arena.json",
            mapOf(
                "schemaVersion" to 1,
                "purpose" to "SCREENING",
                "configurations" to listOf(base.configuration.id),
                "candidate" to "ordinary.json",
                "incumbent" to "ordinary.json",
                "opponents" to listOf("ordinary-rival.json"),
                "pairsPerConfiguration" to 8,
                "seed" to 71,
                "jointPolicy" to
                    mapOf(
                        "baseline" to "baseline.json",
                        "sharedBundle" to "rival.json",
                        "candidateBundle" to "bundle.json",
                        "incumbentBundle" to "rival.json",
                    ),
            ),
        )
    }

    val specification: ArenaSpecification
        get() = ArenaSpecification.read(source)

    fun edit(change: ObjectNode.() -> Unit) {
        val node = PurchaseJson.read(source) as ObjectNode
        node.change()
        base.artifacts.write("arena.json", node)
    }

    fun cardDecision(): SimulationDecision {
        val state = jointStateFixture()
        val actor = PlayerId("p1")
        val original = BotObservations.from(state, actor)
        return SimulationDecision(
            4,
            0,
            71,
            state.viewForPlayer(actor),
            original.copy(
                leaderPlanning = null,
                candidates =
                    original.candidates
                        .filter { it.command in setOf("pass", "play held") }
                        .map {
                            it.copy(
                                features =
                                    if (it.command == "pass") emptyMap() else mapOf("points" to 1.0)
                            )
                        },
            ),
        )
    }

    fun purchaseDecision(): SimulationDecision {
        val actor = PlayerId("p1")
        val state =
            jointStateFixture()
                .copy(
                    stage = Stage.PURCHASE,
                    flow =
                        TurnFlow(
                            actor = actor,
                            purchaseStep = PurchaseStep.ADVANCES,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                        ),
                )
        val original = BotObservations.from(state, actor)
        return SimulationDecision(
            4,
            0,
            71,
            state.viewForPlayer(actor),
            original.copy(
                advancePlanning = null,
                reserveShortfallBasis = null,
                candidates =
                    listOf(
                        original.candidates
                            .first { it.family == "advance" }
                            .copy(features = mapOf("points" to 1.0)),
                        original.candidates
                            .single { it.command == "pass" }
                            .copy(features = emptyMap()),
                    ),
            ),
        )
    }

    fun purchaseOverrides() {
        for ((name, weight) in listOf("candidate" to 30.0, "incumbent" to -30.0)) {
            val model =
                PurchaseJson.read(base.artifacts.directory.resolve("purchase-policy.json"))
                    as ObjectNode
            val names = model["actionFeatureNames"].map(JsonNode::asText)
            (model["actionWeights"] as ArrayNode).set(
                names.indexOf("kind.pass"),
                PurchaseJson.mapper.nodeFactory.numberNode(8.0),
            )
            (model["actionBias"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.numberNode(-4.0),
            )
            (model["actorWeights"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.numberNode(weight),
            )
            base.artifacts.write("$name-purchase.json", model)
            base.writeBundle(
                "$name-bundle.json",
                cardName = null,
                purchaseName = "$name-purchase.json",
            )
        }
        edit {
            (this["jointPolicy"] as ObjectNode)
                .put("candidateBundle", "candidate-bundle.json")
                .put("incumbentBundle", "incumbent-bundle.json")
        }
    }

    fun overlays() {
        base.artifacts.model(
            ordinary.copy(weights = ordinary.weights + ("tokens" to 2.0)),
            "expand.json",
        )
        base.artifacts.model(
            rival.copy(weights = rival.weights + ("tokens" to 2.0)),
            "expand-rival.json",
        )
        edit {
            set<JsonNode>(
                "expansionImitation",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "candidateModel" to "expand.json",
                        "incumbentModel" to "expand.json",
                        "opponentModels" to listOf("expand-rival.json"),
                    )
                ),
            )
            set<JsonNode>(
                "tokenOrder",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "candidateSelector" to "FIXED_16_TO_15",
                        "incumbentSelector" to "FIXED_16_TO_15",
                        "opponentSelectors" to listOf("FIXED_16_TO_15"),
                    )
                ),
            )
        }
    }

    override fun close() = base.close()
}
