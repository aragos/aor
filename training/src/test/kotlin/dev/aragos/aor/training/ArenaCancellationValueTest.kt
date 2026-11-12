package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.CancellationValueInput
import dev.aragos.aor.ai.CancellationValueModel
import dev.aragos.aor.ai.CancellationValuePolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaCancellationValueTest {
    private fun model() =
        CancellationValueModel(
            CancellationValuePolicy.VERSION,
            CancellationValuePolicy.featureNames,
            List(58) { 0.0 },
            List(58) { 0.0 },
            List(58) { 1.0 },
            0.2,
            "a".repeat(64),
        )

    private fun overlay(fixture: ArenaPurchaseFixture): ArenaSpecification {
        fixture.artifacts.write("cancel.json", model())
        val document = PurchaseJson.read(fixture.source) as ObjectNode
        document.set<ObjectNode>(
            "cancellationValue",
            PurchaseJson.mapper.valueToTree(mapOf("candidateModel" to "cancel.json")),
        )
        fixture.artifacts.write("arena.json", document)
        return fixture.specification
    }

    private fun state(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        return GameState(
            header = GameHeader(players, players[0].id),
            stage = Stage.DRAW_CARD,
            turnNumber = 2,
            adjustments = mapOf(Commodity.WOOL to listOf(CommodityAdjustment.SHORTAGE)),
            deck = listOf("stone-1", "stone-2", "wool-1"),
            flow = TurnFlow(actor = players[0].id, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = 40,
                            )
                    }
                    .toMap(),
        )
    }

    private fun decision(seed: Int = 71): SimulationDecision {
        val state = state()
        val actor = state.playerIds[0]
        return SimulationDecision(
            4,
            0,
            seed,
            state.viewForDecision(actor),
            BotObservations.from(state, actor),
        )
    }

    @Test
    fun candidateOnlyRoutesCancellationAndKeepsPurchaseController() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = overlay(fixture)
            val candidate =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    listOf(0, 0, 0, 0),
                )
            val incumbent =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.INCUMBENT,
                    specification.incumbent,
                    listOf(0, 0, 0, 0),
                )
            assertThat(candidate[0]?.choose(decision()))
                .isEqualTo("cancel-adjustment wool shortage")
            assertThat(candidate[0]?.controls("purchase-advances")).isTrue()
            assertThat(incumbent[0]?.controls("cancel-adjustment")).isFalse()
            assertThat(candidate[1]?.controls("cancel-adjustment")).isFalse()
        }
    }

    @Test
    fun selectorDoesNotDependOnPolicySeed() {
        val controller = CancellationValueController(model())
        assertThat(controller.choose(decision(1))).isEqualTo(controller.choose(decision(-999)))
    }

    @Test
    fun unrelatedDecisionFallsBack() {
        val controller = CancellationValueController(model())
        val decision = decision()
        assertThat(
                controller.choose(
                    decision.copy(observation = decision.observation.copy(decision = "expand"))
                )
            )
            .isNull()
    }

    @Test
    fun hiddenDeckOrderDoesNotChangeInputs() {
        val state = state()
        val actor = state.playerIds[0]
        val changed = state.copy(deck = state.deck.reversed())
        assertThat(CancellationValueInput.from(state.viewForDecision(actor), actor))
            .isEqualTo(CancellationValueInput.from(changed.viewForDecision(actor), actor))
    }

    @Test
    fun explicitSideIsRequired() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = overlay(fixture)
            assertFailsWith<IllegalArgumentException> {
                specification.controllers(4, 0, null, specification.candidate)
            }
        }
    }

    @Test
    fun absentOverlayKeepsLegacyIdentityAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = fixture.specification
            assertThat(specification.copy(cancellationValue = null).identity())
                .isEqualTo(specification.identity())
            assertThat(specification.overlayMetadata()).doesNotContainKey("cancellationValue")
        }
    }

    @Test
    fun metadataAndSnapshotBindExactModelBytes() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = overlay(fixture)
            val output = Files.createTempDirectory("aor-cancel-snapshot-")
            try {
                specification.snapshotOverlays(TrainingArtifacts(output))
                assertThat(PurchaseJson.hash(output.resolve("cancellation-value-candidate.json")))
                    .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("cancel.json")))
                assertThat(specification.overlayMetadata()).containsKey("cancellationValue")
            } finally {
                output.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun tamperedModelCannotBeSnapshotted() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = overlay(fixture)
            Files.writeString(fixture.directory.resolve("cancel.json"), "{}")
            assertFailsWith<IllegalArgumentException> {
                specification.snapshotOverlays(fixture.artifacts)
            }
        }
    }

    @Test
    fun missingOfferedCommandFailsClosed() {
        val original = decision()
        val missing =
            original.copy(
                observation =
                    original.observation.copy(
                        candidates = original.observation.candidates.filter { it.command == "pass" }
                    )
            )
        assertFailsWith<IllegalArgumentException> {
            CancellationValueController(model()).choose(missing)
        }
    }
}
