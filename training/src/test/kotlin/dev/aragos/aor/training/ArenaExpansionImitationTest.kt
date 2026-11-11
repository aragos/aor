package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaExpansionImitationTest {
    @Test
    fun evaluatedExpansionRetainsSharedPurchaseAndLeavesOtherDecisionsOrdinary() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)
            val controller =
                specification.controllers(4, 2, ArenaSide.CANDIDATE, specification.candidate)[2]

            assertThat(controller?.controls("expand")).isTrue()
            assertThat(controller?.controls("purchase-advances")).isTrue()
            assertThat(controller?.controls("purchase-shipping")).isTrue()
            assertThat(controller?.controls("buy-tokens")).isFalse()
            assertThat(controller?.controls("play-cards")).isFalse()
            assertThat(controller?.controls("bid-capital")).isFalse()
        }
    }

    @Test
    fun rivalsFollowExplicitOpponentIndexWithoutReplacingTheEvaluatedSeat() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture, rivals = true)

            val controllers =
                specification.controllers(
                    4,
                    1,
                    ArenaSide.INCUMBENT,
                    specification.incumbent,
                    listOf(1, 1, 0, 1),
                )

            assertThat(controllers[0]?.controls("expand")).isTrue()
            assertThat(controllers[1]?.controls("expand")).isFalse()
            assertThat(controllers[2]?.controls("expand")).isFalse()
            assertThat(controllers[3]?.controls("expand")).isTrue()
        }
    }

    @Test
    fun controllerUsesTheUnchangedHardPlannerAndPolicySeed() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)
            val session = ExperimentSession.start(fixture.configuration, 82L)
            while (session.state.stage != Stage.EXPANSION) {
                check(session.decisions < 500) {
                    "Expansion fixture must reach an expansion phase."
                }
                session.apply(
                    session.actors().associateWith { actor ->
                        BotPolicy.choose(session.observation(actor), BotDifficulty.HARD, 82)
                    }
                )
            }
            val actor = session.actors().first()
            val decision =
                SimulationDecision(
                    session.decisions,
                    0,
                    82,
                    session.state.viewForPlayer(actor),
                    session.observation(actor),
                )
            val model = TrainingArtifacts.readModel(fixture.source.parent.resolve("imitation.json"))

            val selected =
                specification
                    .controllers(4, 0, ArenaSide.CANDIDATE, specification.candidate)[0]
                    ?.choose(decision)

            assertThat(selected)
                .isEqualTo(BotPolicy.choose(decision.observation, BotDifficulty.HARD, 82, model))
        }
    }

    @Test
    fun undeclaredWeightChangesAreRejected() {
        ArenaPurchaseFixture().use { fixture ->
            specification(fixture)
            fixture.artifacts.model(
                BotModels.default.copy(weights = BotModels.default.weights + ("cards" to 100.0)),
                "imitation.json",
            )

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("eight declared weights")
        }
    }

    @Test
    fun changingModelBytesChangesIdentityAndSnapshotMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val original = specification(fixture)
            fixture.artifacts.model(
                BotModels.default.copy(
                    weights = BotModels.default.weights + ("action.expansion" to 50.0)
                ),
                "imitation.json",
            )
            val changed = fixture.specification

            assertThat(changed.identity()).isNotEqualTo(original.identity())
            assertThat(changed.overlayMetadata()).isNotEqualTo(original.overlayMetadata())
        }
    }

    @Test
    fun snapshotRejectsChangedInputBytes() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)
            fixture.artifacts.model(BotModels.default, "imitation.json")

            assertFailsWith<IllegalArgumentException> {
                specification.snapshotOverlays(fixture.artifacts)
            }
        }
    }

    @Test
    fun ambiguousWeightOnlySearchIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)

            assertFailsWith<IllegalArgumentException> { fixture.search(specification) }
        }
    }

    @Test
    fun frozenExpansionContinuationRejectsImitationRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)

            assertFailsWith<IllegalArgumentException> {
                ExpansionOpportunitySpecification(specification)
            }
        }
    }

    @Test
    fun frozenTokenContinuationRejectsImitationRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture)

            assertFailsWith<IllegalArgumentException> { TokenOrderSpecification(specification) }
        }
    }

    @Test
    fun absentOverlayRetainsOrdinaryMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = fixture.specification

            assertThat(specification.overlayMetadata()).doesNotContainKey("expansionImitation")
            assertThat(specification.identity()).doesNotContain("expansion-imitation")
            assertThat(
                    specification
                        .controllers(4, 0, ArenaSide.CANDIDATE, specification.candidate)[0]
                        ?.controls("expand")
                )
                .isFalse()
        }
    }

    @Test
    fun rotatedFullGameAndCanonicalReplayUseIdenticalImitationBindings() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = specification(fixture, rivals = true)
            val expected = StrategyArena.game(specification, 5, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            val actual = StrategyReplays.play(specification, 5, StrategyReplaySide.CANDIDATE)

            assertThat(actual.replayVerified).isTrue()
            assertThat(actual.game.copy(elapsedMillis = 0))
                .isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    @Test
    fun explicitUnchangedExpansionModelPreservesFullGame() {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.specification.copy(pairsPerConfiguration = 8)
            val expected = StrategyArena.game(original, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)
            specification(fixture)
            fixture.artifacts.model(BotModels.default, "imitation.json")

            val actual =
                StrategyArena.game(fixture.specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            assertThat(actual.copy(elapsedMillis = 0)).isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    private fun specification(
        fixture: ArenaPurchaseFixture,
        rivals: Boolean = false,
    ): ArenaSpecification {
        fixture.artifacts.model(
            BotModels.default.copy(
                weights = BotModels.default.weights + ("action.expansion" to 25.0)
            ),
            "imitation.json",
        )
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.put("pairsPerConfiguration", 8)
        source.set<JsonNode>(
            "opponents",
            PurchaseJson.mapper.valueToTree(List(if (rivals) 2 else 1) { null }),
        )
        source.set<JsonNode>(
            "expansionImitation",
            PurchaseJson.mapper.valueToTree(
                mapOf("candidateModel" to "imitation.json") +
                    if (rivals) mapOf("opponentModels" to listOf(null, "imitation.json"))
                    else emptyMap()
            ),
        )
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }
}
