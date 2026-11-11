package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotCathedralTactics
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaCathedralTacticsTest {
    @Test
    fun disabledWrapperPreservesTheCompleteNativePhaseController() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val baseline =
                specification
                    .controllers(
                        4,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[0]
            val expansion =
                requireNotNull(specification.expansionImitation?.model(ArenaSide.CANDIDATE)) {
                    "The fixture requires its expansion model."
                }
            val wrapped =
                CathedralTacticalController(baseline, specification.candidate, expansion, false)
            for (stage in listOf(Stage.TOKEN_PURCHASE, Stage.PURCHASE, Stage.EXPANSION)) {
                val decision = decision(fixture, stage)
                val expected =
                    baseline
                        ?.takeIf { it.controls(decision.observation.decision) }
                        ?.choose(decision)
                        ?: BotPolicy.choose(
                            decision.observation,
                            BotDifficulty.HARD,
                            decision.seed,
                            specification.candidate,
                        )

                assertThat(wrapped.choose(decision)).isEqualTo(expected)
                assertThat(wrapped.controls(decision.observation.decision))
                    .isEqualTo(baseline?.controls(decision.observation.decision) == true)
            }
        }
    }

    @Test
    fun enabledResearchLeavesEveryNonExpansionPhaseWithTheCompleteController() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val baseline =
                specification
                    .controllers(
                        4,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[0]
            val wrapped =
                CathedralTacticalController(
                    baseline,
                    specification.candidate,
                    requireNotNull(specification.expansionImitation?.model(ArenaSide.CANDIDATE)) {
                        "The fixture requires E."
                    },
                    true,
                )
            val decision = decision(fixture, Stage.PURCHASE)

            assertThat(wrapped.choose(decision)).isEqualTo(baseline?.choose(decision))
        }
    }

    @Test
    fun candidateOnlyWrapperPreservesRivalInstancesAndTheIncumbentSide() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val baseline =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    listOf(0, 0, 0, 0),
                )
            val policy = policy(true)

            val wrapped =
                policy.wrap(
                    baseline,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    specification.expansionImitation,
                )
            val incumbent =
                policy.wrap(
                    baseline,
                    0,
                    ArenaSide.INCUMBENT,
                    specification.incumbent,
                    specification.expansionImitation,
                )

            assertThat(wrapped[0]).isInstanceOf(CathedralTacticalController::class.java)
            for (seat in 1..3) {
                assertThat(wrapped[seat]).isSameInstanceAs(baseline[seat])
            }
            assertThat(incumbent).isSameInstanceAs(baseline)
        }
    }

    @Test
    fun absentFlagAndExplicitDisabledFlagShareExactlyTheSameControllerMap() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val baseline =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    listOf(0, 0, 0, 0),
                )

            val disabled =
                policy(false)
                    .wrap(
                        baseline,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        specification.expansionImitation,
                    )

            assertThat(disabled).isSameInstanceAs(baseline)
        }
    }

    @Test
    fun sourceOptInBindsNativeIdentityAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val original = complete(fixture)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<ObjectNode>("cathedralTactics", document(true))
            fixture.artifacts.write("arena.json", source)

            val enabled = fixture.specification

            assertThat(enabled.identity()).isNotEqualTo(original.identity())
            assertThat(enabled.overlayMetadata()).containsKey("cathedralTactics")
            assertThat(
                    enabled
                        .controllers(
                            4,
                            0,
                            ArenaSide.CANDIDATE,
                            enabled.candidate,
                            listOf(0, 0, 0, 0),
                        )[0]
                )
                .isInstanceOf(CathedralTacticalController::class.java)
        }
    }

    @Test
    fun enabledSourceRejectsAnUnboundExpansionModel() {
        assertFailsWith<IllegalArgumentException> { policy(true).validate(BotModels.default, null) }
    }

    @Test
    fun enabledSourceRejectsBrowserGateSubstitutionForNativeRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val gated =
                specification.candidate.copy(
                    weights =
                        specification.candidate.weights +
                            ("planning.tokenOrderControl.fixed16To15" to 1.0)
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    policy(true).validate(gated, specification.expansionImitation)
                }

            assertThat(failure).hasMessageThat().contains("native raw model roles")
        }
    }

    @Test
    fun recordedPairRejectsADifferentWrapperBeforeSimulation() {
        ArenaPurchaseFixture().use { fixture ->
            val original = complete(fixture)
            val enabled = original.copy(cathedralTactics = policy(true))
            val terminal = ArenaGame(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
            val pair = StrategyArena.pair(enabled, 0, terminal, terminal)
            fixture.artifacts.write("pair.json", pair)
            val recorded = StrategyReplayPair.read(fixture.artifacts.directory.resolve("pair.json"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    recorded.opponentIndices(original, 0)
                }

            assertThat(failure).hasMessageThat().contains("Cathedral tactics differ")
        }
    }

    @Test
    fun recordedPairAcceptsTheExactDeclaredWrapper() {
        ArenaPurchaseFixture().use { fixture ->
            val enabled = complete(fixture).copy(cathedralTactics = policy(true))
            val terminal = ArenaGame(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
            val pair = StrategyArena.pair(enabled, 0, terminal, terminal)
            fixture.artifacts.write("pair.json", pair)
            val recorded = StrategyReplayPair.read(fixture.artifacts.directory.resolve("pair.json"))

            assertThat(recorded.opponentIndices(enabled, 0)).isEqualTo(pair.opponentIndices)
        }
    }

    @Test
    fun legacyPairOmitsTheNewFieldAndRetainsItsReplayContract() {
        ArenaPurchaseFixture().use { fixture ->
            val original = complete(fixture)
            val terminal = ArenaGame(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
            val pair = StrategyArena.pair(original, 0, terminal, terminal)
            fixture.artifacts.write("pair.json", pair)
            val document = PurchaseJson.mapper.valueToTree<JsonNode>(pair)
            val recorded = StrategyReplayPair.read(fixture.artifacts.directory.resolve("pair.json"))

            assertThat(document.has("cathedralTactics")).isFalse()
            assertThat(recorded.opponentIndices(original, 0)).isEqualTo(pair.opponentIndices)
        }
    }

    @Test
    fun enabledReplayRejectsALegacyPairWithoutItsWrapperBinding() {
        ArenaPurchaseFixture().use { fixture ->
            val original = complete(fixture)
            val terminal = ArenaGame(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
            fixture.artifacts.write(
                "pair.json",
                StrategyArena.pair(original, 0, terminal, terminal),
            )
            val recorded = StrategyReplayPair.read(fixture.artifacts.directory.resolve("pair.json"))

            assertFailsWith<IllegalArgumentException> {
                recorded.opponentIndices(original.copy(cathedralTactics = policy(true)), 0)
            }
        }
    }

    @Test
    fun replayCallbackRecordsTheCompleteIncumbentAndBoundedChoice() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = complete(fixture)
            val baseline =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    listOf(0, 0, 0, 0),
                )
            val captured = mutableListOf<CathedralTacticalDecision>()
            val wrapped =
                policy(true)
                    .wrap(
                        baseline,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        specification.expansionImitation,
                        captured::add,
                    )
            val decision = decision(fixture, Stage.EXPANSION)

            val selected = wrapped[0]?.choose(decision)

            assertThat(captured).hasSize(1)
            assertThat(captured.single().incumbent).isEqualTo(baseline[0]?.choose(decision))
            assertThat(captured.single().result.command).isEqualTo(selected)
            assertThat(captured.single().number).isEqualTo(decision.number)
        }
    }

    private fun policy(enabled: Boolean) = ArenaCathedralTactics.read(document(enabled))

    private fun document(enabled: Boolean): ObjectNode =
        PurchaseJson.mapper
            .createObjectNode()
            .put("version", BotCathedralTactics.VERSION)
            .put("candidateEnabled", enabled)

    private fun complete(fixture: ArenaPurchaseFixture): ArenaSpecification {
        fixture.withOverrides()
        fixture.artifacts.model(
            BotModels.default.copy(weights = BotModels.default.weights + ("progress" to 9.0)),
            "expansion.json",
        )
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source
            .putObject("expansionImitation")
            .put("candidateModel", "expansion.json")
            .put("incumbentModel", "expansion.json")
        source
            .putObject("tokenOrder")
            .put("candidateSelector", "FIXED_16_TO_15")
            .put("incumbentSelector", "FIXED_16_TO_15")
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }

    private fun decision(fixture: ArenaPurchaseFixture, stage: Stage): SimulationDecision {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val actor = players.first().id
        val board =
            BoardState()
                .placeMarker(ProvinceId("naples"), actor)
                .board
                .placeMarker(ProvinceId("florence"), players[1].id)
                .board
        val state =
            GameState(
                header = GameHeader(players, actor),
                stage = stage,
                rules = fixture.configuration.rules,
                turnNumber = 2,
                turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
                board = board,
                flow =
                    TurnFlow(
                        actor = actor,
                        phaseStarted = true,
                        purchaseStep = PurchaseStep.ADVANCES,
                    ),
                players =
                    players
                        .mapIndexed { index, player ->
                            player.id to
                                PlayerState(
                                    player = player,
                                    capital = capitals[index],
                                    cash = 100,
                                    poolTokens = if (stage == Stage.TOKEN_PURCHASE) 0 else 10,
                                    stockTokens = if (stage == Stage.TOKEN_PURCHASE) 36 else 26,
                                    advances =
                                        PlayerAdvances(
                                            if (index == 1) {
                                                listOf(AdvanceAcquisition(AdvanceId.CATHEDRAL, 1))
                                            } else {
                                                emptyList()
                                            }
                                        ),
                                )
                        }
                        .toMap(),
            )
        return SimulationDecision(
            7,
            0,
            117,
            state.viewForDecision(actor),
            BotObservations.from(state, actor),
        )
    }
}
