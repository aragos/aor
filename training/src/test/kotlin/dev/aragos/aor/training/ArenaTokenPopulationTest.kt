package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaTokenPopulationTest {
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

    @Test
    fun weightOnlySearchRejectsRivalTokenRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = population(fixture, listOf("FIXED_16_TO_15"))

            val failure =
                assertFailsWith<IllegalArgumentException> { fixture.search(specification) }

            assertThat(failure).hasMessageThat().contains("cannot assign token-order")
        }
    }

    @Test
    fun tokenCollectionRejectsRivalTokenRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = population(fixture, listOf("FIXED_16_TO_15"))

            val failure =
                assertFailsWith<IllegalArgumentException> { TokenOrderSpecification(specification) }

            assertThat(failure).hasMessageThat().contains("without a learned token overlay")
        }
    }

    @Test
    fun arenaMatchesIndependentRivalOnlyControllersAcrossARotatedLineup() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                population(fixture, listOf("ORDINARY_MINUS_TWO", "ORDINARY", "FIXED_16_TO_15"))
            val expected = reference(specification, fixture, 5)

            val actual = StrategyArena.game(specification, 5, ArenaSide.CANDIDATE, Long.MAX_VALUE)

            assertThat(actual.copy(elapsedMillis = 0)).isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    @Test
    fun canonicalReplayUsesTheSameRotatedRivalControllers() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                population(fixture, listOf("ORDINARY_MINUS_TWO", "ORDINARY", "FIXED_16_TO_15"))
            val expected = reference(specification, fixture, 5)

            val replay = StrategyReplays.play(specification, 5, StrategyReplaySide.CANDIDATE)

            assertThat(replay.replayVerified).isTrue()
            assertThat(replay.game.copy(elapsedMillis = 0))
                .isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    @Test
    fun explicitOrdinaryRivalsPreserveBaselineFullGame() {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.specification
            val expected = StrategyArena.game(original, 0, ArenaSide.INCUMBENT, Long.MAX_VALUE)
            val declared = population(fixture, listOf("ORDINARY"))

            val actual = StrategyArena.game(declared, 0, ArenaSide.INCUMBENT, Long.MAX_VALUE)

            assertThat(actual.copy(elapsedMillis = 0)).isEqualTo(expected.copy(elapsedMillis = 0))
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
        val actor = session.actors().first()
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
                                    ("test.bid.${candidate.command.substringAfter("buy-tokens ")}" to
                                        1.0)
                        )
                    }
            ),
        )
    }

    private fun reference(
        specification: ArenaSpecification,
        fixture: ArenaPurchaseFixture,
        index: Int,
    ): ArenaGame {
        val lineup = StrategyArena.lineup(specification, index)
        val purchase =
            requireNotNull(specification.frozenPurchase) { "The fixture has Purchase overlays." }
                .controllers(4, lineup.seat, ArenaSide.CANDIDATE)
        val roles = listOf("ORDINARY_MINUS_TWO", "ORDINARY", "FIXED_16_TO_15")
        val controllers = purchase.mapValues { (seat, previous) ->
            val opponent = lineup.opponentIndices[seat]
            val role = roles[opponent]
            object : SimulationController {
                override fun controls(decision: String) =
                    previous.controls(decision) ||
                        (seat != lineup.seat && role != "ORDINARY" && decision == "buy-tokens")

                override fun choose(decision: SimulationDecision): String? {
                    if (decision.observation.decision != "buy-tokens")
                        return previous.choose(decision)
                    val ordinary =
                        BotPolicy.choose(
                            decision.observation,
                            BotDifficulty.HARD,
                            decision.seed,
                            specification.opponents[opponent],
                        )
                    val desired =
                        when {
                            role == "ORDINARY_MINUS_TWO" ->
                                "buy-tokens ${ordinary.substringAfter("buy-tokens ").toLong() - 2L}"
                            ordinary == "buy-tokens 16" -> "buy-tokens 15"
                            else -> ordinary
                        }
                    return if (decision.observation.candidates.any { it.command == desired })
                        desired
                    else ordinary
                }
            }
        }
        return ArenaGame.from(
            Simulation()
                .play(
                    configuration = fixture.configuration,
                    seed = lineup.seed,
                    competitors =
                        lineup.opponentIndices.mapIndexed { seat, opponent ->
                            Competitor(
                                if (seat == lineup.seat) specification.candidate
                                else specification.opponents[opponent],
                                BotDifficulty.HARD,
                            )
                        },
                    deadlineNanos = Long.MAX_VALUE,
                    controllers = controllers,
                )
        )
    }
}
