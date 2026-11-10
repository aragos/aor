package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryValuePolicy
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaTokenOrderPolicyTest {
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

    private fun withTokenRoles(fixture: ArenaPurchaseFixture): ArenaSpecification {
        val dictionary =
            TokenObservationEncoder.dictionary(
                PublicCatalog.forPlayers(4, fixture.configuration.rules),
                TerritoryEncoder.TOKEN_2,
            )
        for ((role, amount) in listOf("candidate" to 0, "incumbent" to 36)) {
            fixture.artifacts.write(
                "$role-token.json",
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
                        List(64 * dictionary.actionFeatureNames.size) {
                            if (it == dictionary.actionFeatureNames.indexOf("bid.amount.$amount"))
                                8.0
                            else 0.0
                        },
                    "actionBias" to List(64) { 0.0 },
                    "outputWeights" to List(64) { if (it == 0) 1.0 else 0.0 },
                    "outputBias" to .5,
                ),
            )
        }
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
