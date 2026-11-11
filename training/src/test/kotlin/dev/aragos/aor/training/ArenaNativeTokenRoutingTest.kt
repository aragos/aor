package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaNativeTokenRoutingTest {
    @Test
    fun rivalSelectorsFollowOpponentIndicesRatherThanSeats() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                population(fixture, listOf("ORDINARY", "FIXED_16_TO_15", "ORDINARY_MINUS_TWO"))
                    .copy(opponents = listOf(model(24), model(16), model(24)))
            val controllers =
                specification.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    specification.candidate,
                    listOf(2, 0, 1, 2),
                )
            val decision = decision(fixture)

            assertThat(controllers[1]?.controls("buy-tokens")).isFalse()
            assertThat(controllers[2]?.choose(decision)).isEqualTo("buy-tokens 15")
            assertThat(controllers[3]?.choose(decision)).isEqualTo("buy-tokens 22")
        }
    }

    @Test
    fun rivalsDoNotInstallASelectorOnTheEvaluatedSeat() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = population(fixture, listOf("FIXED_16_TO_15"))

            val controllers =
                specification.controllers(
                    4,
                    2,
                    ArenaSide.INCUMBENT,
                    specification.incumbent,
                    listOf(0, 0, 0, 0),
                )

            assertThat(controllers[2]?.controls("buy-tokens")).isFalse()
        }
    }

    @Test
    fun rivalTokensRetainTheirSharedPurchaseController() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = population(fixture, listOf("ORDINARY_MINUS_TWO"))

            val controller =
                specification
                    .controllers(
                        4,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[1]

            assertThat(controller?.controls("purchase-shipping")).isTrue()
            assertThat(controller?.controls("purchase-advances")).isTrue()
            assertThat(controller?.controls("buy-tokens")).isTrue()
            assertThat(controller?.controls("expand")).isFalse()
        }
    }

    @Test
    fun minusTwoUsesTheActualOrdinaryTokenHead() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = decision(fixture)
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
                model(8)
                    .copy(
                        weights =
                            model(8).weights +
                                mapOf(
                                    BotTokenValue.GATE to 1.0,
                                    BotTokenValue.featureNames.first() to 1.0,
                                )
                    )
            val specification =
                population(fixture, listOf("ORDINARY_MINUS_TWO")).copy(opponents = listOf(baseline))
            val controller =
                specification
                    .controllers(
                        4,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[1]

            val chosen = controller?.choose(initial.copy(observation = observation))

            assertThat(BotPolicy.choose(observation, BotDifficulty.NORMAL, initial.seed, baseline))
                .isEqualTo("buy-tokens 8")
            assertThat(chosen).isEqualTo("buy-tokens 14")
        }
    }

    @Test
    fun minusTwoRetainsBaselineWhenTheExactAlternativeIsNotOffered() {
        ArenaPurchaseFixture().use { fixture ->
            val initial = decision(fixture)
            val restricted =
                initial.copy(
                    observation =
                        initial.observation.copy(
                            candidates =
                                initial.observation.candidates.filter {
                                    it.command != "buy-tokens 14"
                                }
                        )
                )
            val specification =
                population(fixture, listOf("ORDINARY_MINUS_TWO"))
                    .copy(opponents = listOf(model(16)))
            val controller =
                specification
                    .controllers(
                        4,
                        0,
                        ArenaSide.CANDIDATE,
                        specification.candidate,
                        listOf(0, 0, 0, 0),
                    )[1]

            val chosen = controller?.choose(restricted)

            assertThat(chosen).isEqualTo("buy-tokens 16")
        }
    }

    @Test
    fun opponentSelectorsBindIdentityAndNativeMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val before = population(fixture, listOf("FIXED_16_TO_15"))
            val changed = population(fixture, listOf("ORDINARY_MINUS_TWO"))

            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(changed.overlayMetadata())

            assertThat(changed.identity()).isNotEqualTo(before.identity())
            assertThat(metadata["tokenOrder"]["opponentSelectors"].map(JsonNode::asText))
                .containsExactly("ORDINARY_MINUS_TWO")
        }
    }

    @Test
    fun opponentSelectorsRequireExactlyOneRolePerFrozenModel() {
        ArenaPurchaseFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<JsonNode>(
                "tokenOrder",
                PurchaseJson.mapper.valueToTree(
                    mapOf("opponentSelectors" to listOf("ORDINARY", "FIXED_16_TO_15"))
                ),
            )
            fixture.artifacts.write("arena.json", source)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("exactly one entry")
        }
    }

    @Test
    fun rivalControlsRequireAnExplicitLineup() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = population(fixture, listOf("FIXED_16_TO_15"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    specification.controllers(4, 0, ArenaSide.CANDIDATE, specification.candidate)
                }

            assertThat(failure).hasMessageThat().contains("explicit opponent-index lineup")
        }
    }

    private fun population(
        fixture: ArenaPurchaseFixture,
        selectors: List<String>,
    ): ArenaSpecification {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.set<JsonNode>(
            "opponents",
            PurchaseJson.mapper.valueToTree(List(selectors.size) { null }),
        )
        source.put("pairsPerConfiguration", 8)
        source.set<JsonNode>(
            "tokenOrder",
            PurchaseJson.mapper.valueToTree(mapOf("opponentSelectors" to selectors)),
        )
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }

    private fun model(amount: Int) = BotModel(weights = mapOf("test.bid.$amount" to 1.0))

    private fun decision(fixture: ArenaPurchaseFixture): SimulationDecision {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val actor = players.first().id
        val state =
            GameState(
                header = GameHeader(players, actor),
                stage = Stage.TOKEN_PURCHASE,
                rules = fixture.configuration.rules,
                turnNumber = 2,
                players =
                    players
                        .mapIndexed { index, player ->
                            player.id to
                                PlayerState(
                                    player = player,
                                    capital = capitals[index],
                                    cash = 100,
                                    stockTokens = 36,
                                )
                        }
                        .toMap(),
            )
        val observation = BotObservations.from(state, actor)
        return SimulationDecision(
            0,
            0,
            71,
            state.viewForDecision(actor),
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features +
                                    ("test.bid.${candidate.command.substringAfter("buy-tokens ")}" to
                                        1.0)
                        )
                    }
            ),
        )
    }
}
