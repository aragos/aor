package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaPurchaseOrdinaryTest {
    @Test
    fun ordinaryIncumbentOmitsOnlyTheEvaluatedSeatsPurchaseController() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)

            val controllers =
                requireNotNull(specification.frozenPurchase) {
                        "The fixture has a purchase environment."
                    }
                    .controllers(4, 2, ArenaSide.INCUMBENT)

            assertThat(controllers.keys).containsExactly(0, 1, 3)
        }
    }

    @Test
    fun ordinaryIncumbentDoesNotDisableTheCandidateOrItsRivals() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)

            val controllers =
                requireNotNull(specification.frozenPurchase) {
                        "The fixture has a purchase environment."
                    }
                    .controllers(4, 2, ArenaSide.CANDIDATE)

            assertThat(controllers.keys).containsExactly(0, 1, 2, 3)
        }
    }

    @Test
    fun ordinaryRoutingIsExplicitInTheNativeIdentityAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.specification.identity()
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)
            val metadata =
                PurchaseJson.mapper.valueToTree<JsonNode>(specification.overlayMetadata())

            assertThat(specification.identity()).isNotEqualTo(original)
            assertThat(metadata["frozenPurchase"]["ordinaryEvaluatedSides"].map(JsonNode::asText))
                .containsExactly("INCUMBENT")
        }
    }

    @Test
    fun anOrdinarySideCannotAlsoNameALearnedPurchaseOverride() {
        ArenaPurchaseFixture().use { fixture ->
            fixture.withOverrides()

            val failure =
                assertFailsWith<IllegalArgumentException> { ordinary(fixture, ArenaSide.INCUMBENT) }

            assertThat(failure).hasMessageThat().contains("ordinary incumbent cannot also declare")
        }
    }

    @Test
    fun modelOnlyExecutionCannotGuessAnOrdinaryEvaluatedSide() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    requireNotNull(specification.frozenPurchase) {
                            "The fixture has a purchase environment."
                        }
                        .controllers(4, 0, side = null)
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun ordinaryIncumbentMatchesAnIndependentFullGameWithOnlyRivalOverlays() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)
            val expected = reference(specification, fixture.directory)

            val actual = StrategyArena.game(specification, 0, ArenaSide.INCUMBENT, Long.MAX_VALUE)

            assertThat(actual.copy(elapsedMillis = 0)).isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    @Test
    fun canonicalReplayRetainsTheOrdinaryIncumbentAndFrozenRivalActors() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = ordinary(fixture, ArenaSide.INCUMBENT)
            val expected = reference(specification, fixture.directory)

            val replay = StrategyReplays.play(specification, 0, StrategyReplaySide.INCUMBENT)

            assertThat(replay.replayVerified).isTrue()
            assertThat(replay.game.copy(elapsedMillis = 0))
                .isEqualTo(expected.copy(elapsedMillis = 0))
        }
    }

    private fun ordinary(fixture: ArenaPurchaseFixture, side: ArenaSide): ArenaSpecification {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        (source["frozenPurchase"] as ObjectNode).putArray("ordinaryEvaluatedSides").add(side.name)
        fixture.artifacts.write("arena.json", source)
        return fixture.specification
    }

    private fun reference(specification: ArenaSpecification, directory: Path): ArenaGame {
        val lineup = StrategyArena.lineup(specification, 0)
        val prior = TrainingArtifacts.readModel(directory.resolve("baseline.json"))
        val actor = PurchasePolicyInput.read(directory.resolve("policy.json"))
        return ArenaGame.from(
            Simulation()
                .play(
                    configuration = lineup.configuration,
                    seed = lineup.seed,
                    competitors =
                        lineup.opponentIndices.mapIndexed { seat, opponent ->
                            Competitor(
                                if (seat == lineup.seat) specification.incumbent
                                else specification.opponents[opponent],
                                BotDifficulty.HARD,
                            )
                        },
                    deadlineNanos = Long.MAX_VALUE,
                    controllers =
                        (0 until lineup.configuration.playerCount)
                            .filter { it != lineup.seat }
                            .associateWith { PurchaseActorController(prior, actor) },
                )
        )
    }
}
