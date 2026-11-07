package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TerritoryValueEncoderVersionTest {
    @Test
    fun exportedVersionTwoSchemaIsExplicitAndCannotOverwriteAnArtifact() {
        TerritoryValueCollectionFixture().use { fixture ->
            val output = fixture.artifacts.directory.resolve("schema2.json")
            val arguments =
                listOf(
                    "--mode",
                    "territory-value-schema",
                    "--configuration",
                    fixture.configuration.id,
                    "--encoder",
                    TerritoryEncoder.EXPANSION_2.version,
                    "--output",
                    output.toString(),
                )
            TerritoryValueSchema.run(arguments)
            val schema = PurchaseJson.read(output)
            assertThat(schema.required("encoderVersion").asText())
                .isEqualTo(TerritoryEncoder.EXPANSION_2.version)
            assertThat(schema.required("stateFeatureNames").size())
                .isEqualTo(fixture.input.bundle.expansionDictionary.stateFeatureNames.size + 30)
            val bytes = Files.readAllBytes(output)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                TerritoryValueSchema.run(arguments)
            }
            assertThat(Files.readAllBytes(output)).isEqualTo(bytes)
        }
    }

    @Test
    fun completeVersionTwoCollectionRetainsItsActualDescriptorAndRejectsLegacyRows() {
        TerritoryValueCollectionFixture(expansionEncoder = TerritoryEncoder.EXPANSION_2).use {
            fixture ->
            val input = fixture.input
            val game = fixture.syntheticGame(input)
            val validator = TerritoryValueTrajectoryValidation(input, "fixture")
            validator.validate(PurchaseJson.mapper.valueToTree(game), 0)
            val row = game.expansionDecisions.single()
            val oldRow =
                row.copy(
                    observation =
                        row.observation.copy(encoderVersion = TerritoryEncoder.EXPANSION_1.version)
                )
            assertFailsWith<IllegalArgumentException> {
                validator.validate(
                    PurchaseJson.mapper.valueToTree(game.copy(expansionDecisions = listOf(oldRow))),
                    0,
                )
            }
            val store = fixture.store(input)
            store.save(game)
            store.finish()
            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(
                    manifest
                        .required("bundle")
                        .required("expansion")
                        .required("encoderVersion")
                        .asText()
                )
                .isEqualTo(TerritoryEncoder.EXPANSION_2.version)
            assertThat(fixture.store(input).completedIndices()).containsExactly(0)
        }
    }

    @Test
    fun versionTwoControllerRecordsPublicContextThroughTheExistingCollectionPath() {
        TerritoryValueCollectionFixture(expansionEncoder = TerritoryEncoder.EXPANSION_2).use {
            fixture ->
            val input = fixture.input
            val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
            val own = players[0].id
            val capitals = listOf("venice", "genoa", "barcelona", "paris")
            val state =
                GameState(
                    header = GameHeader(players, own),
                    stage = Stage.EXPANSION,
                    turnNumber = 1,
                    flow =
                        TurnFlow(
                            actor = own,
                            phaseStarted = true,
                            cathedralUses = setOf(own to players[1].id),
                        ),
                    turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
                    board = BoardState(),
                    players =
                        players
                            .mapIndexed { i, player ->
                                player.id to
                                    PlayerState(
                                        player,
                                        capital = capitals[i],
                                        poolTokens = 10,
                                        stockTokens = 26,
                                        advances =
                                            PlayerAdvances(
                                                if (i == 0)
                                                    listOf(
                                                        AdvanceAcquisition(AdvanceId.CATHEDRAL, 1)
                                                    )
                                                else emptyList()
                                            ),
                                    )
                            }
                            .toMap(),
                )
            val view = state.viewForDecision(own)
            val recorded = mutableListOf<TerritoryValueDecisionRecord>()
            val controller = input.bundle.controller(input.baseline, .2, recorded::add)
            controller.choose(SimulationDecision(0, 0, 103, view, BotObservations.from(state, own)))
            assertThat(recorded.single().observation.encoderVersion)
                .isEqualTo(TerritoryEncoder.EXPANSION_2.version)
            assertThat(recorded.single().observation.stateFeatures)
                .containsEntry("cathedral.used.role.0.role.1", 1.0)
            val registry = fixture.evaluation(103)
            assertThat(registry.policies.keys).containsAtLeast("candidate", "rival")
        }
    }

    @Test
    fun oldDictionaryCannotBeRelabelledAsVersionTwo() {
        TerritoryValueCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("expansion-policy.json")
            val model = PurchaseJson.read(path) as com.fasterxml.jackson.databind.node.ObjectNode
            model.put("encoderVersion", TerritoryEncoder.EXPANSION_2.version)
            fixture.artifacts.write("expansion-policy.json", model)
            fixture.writeBundle("territory.json")
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }
}
