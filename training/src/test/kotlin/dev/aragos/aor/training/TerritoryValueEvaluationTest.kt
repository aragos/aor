package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryValuePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TerritoryValueEvaluationTest {
    @Test
    fun legacyGreedyMetadataAndSignatureRemainExact() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = evaluation(fixture, "territory-value-bundle", -0.0)
            val policy = candidate(input)
            val bundle = requireNotNull(policy.territoryValueBundle) { "The fixture has Q heads." }
            val expectedSignature =
                trainingSignature(
                    listOf(
                            "card-advantage-evaluate-1",
                            input.source,
                            input.sourceSha256,
                            input.environment.baseline.sha256,
                            input.environment.purchase.sha256,
                            input.policies.mapValues { it.value.sha256 },
                            CardAdvantagePolicy.SELECTOR_VERSION,
                            CardProposals.VERSION,
                        )
                        .joinToString("\n") + "\n${TerritoryValueBundle.VERSION}"
                )

            assertThat(input.signature()).isEqualTo(expectedSignature)
            assertThat(policy.metadata())
                .isEqualTo(
                    mapOf(
                        "kind" to "territory-value-bundle",
                        "path" to bundle.path.toString(),
                        "sha256" to bundle.sha256,
                        "bundle" to bundle.metadata(),
                        "frozenCardPurchaseBundle" to bundle.frozen.metadata(),
                        "epsilon" to 0.0,
                    )
                )
        }
    }

    @Test
    fun legacyKindStillRejectsSmallNonzeroExploration() {
        TerritoryValueCollectionFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                evaluation(fixture, "territory-value-bundle", .02)
            }
        }
    }

    @Test
    fun diagnosticDescriptorBindsActualEpsilonAndEveryNestedModel() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = evaluation(fixture, explorationKind, .02)
            val policy = candidate(input)
            val frozen = fixture.input
            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(policy.metadata())

            assertThat(metadata.required("kind").asText()).isEqualTo(explorationKind)
            assertThat(metadata.required("epsilon").asDouble()).isEqualTo(.02)
            assertThat(metadata.required("bundle").required("token").required("sha256").asText())
                .isEqualTo(frozen.bundle.token?.sha256)
            assertThat(
                    metadata.required("bundle").required("expansion").required("sha256").asText()
                )
                .isEqualTo(frozen.bundle.expansion?.sha256)
            assertThat(metadata.required("frozenCardPurchaseBundle"))
                .isEqualTo(PurchaseJson.mapper.valueToTree<JsonNode>(frozen.frozen.metadata()))
            assertThat(policy.controller(input.environment))
                .isInstanceOf(TerritoryValueController::class.java)
            assertThat(policy.controller(input.environment).controls("buy-tokens")).isTrue()
            assertThat(policy.controller(input.environment).controls("expand")).isTrue()
            assertThat(policy.controller(input.environment).controls("prepare-war")).isFalse()
        }
    }

    @Test
    fun diagnosticSignatureBindsEpsilonIndependentlyOfTheSourceFingerprint() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = evaluation(fixture, explorationKind, .02)
            val changed =
                input.copy(
                    policies =
                        input.policies +
                            ("candidate" to candidate(input).copy(territoryValueEpsilon = .2))
                )

            assertThat(changed.signature()).isNotEqualTo(input.signature())
        }
    }

    @Test
    fun diagnosticInputRejectsChangedNestedBytes() {
        for (name in
            listOf(
                "token-policy.json",
                "expansion-policy.json",
                "card-policy.json",
                "purchase-policy.json",
                "bundle.json",
            )) {
            TerritoryValueCollectionFixture().use { fixture ->
                val source = evaluationSource(fixture, explorationKind, .02)
                Files.writeString(
                    fixture.artifacts.directory.resolve(name),
                    "\n",
                    StandardOpenOption.APPEND,
                )

                assertFailsWith<IllegalArgumentException> {
                    CardAdvantageEvaluationInput.read(source)
                }
            }
        }
    }

    @Test
    fun diagnosticAndNativeControllerRejectInvalidProbabilities() {
        TerritoryValueCollectionFixture().use { fixture ->
            val collection = fixture.input
            for (epsilon in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertFailsWith<IllegalArgumentException> {
                    evaluation(fixture, explorationKind, epsilon)
                }
                assertFailsWith<IllegalArgumentException> {
                    collection.bundle.controller(collection.baseline, epsilon)
                }
            }
        }
    }

    @Test
    fun explicitlyGreedyDiagnosticPreservesLegacyCompleteGame() {
        TerritoryValueCollectionFixture().use { fixture ->
            val legacy = fixture.evaluation(71)
            val diagnostic = evaluation(fixture, explorationKind, 0.0)

            val expected = CardAdvantageEvaluator(legacy, Long.MAX_VALUE).evaluate(0)
            val actual = CardAdvantageEvaluator(diagnostic, Long.MAX_VALUE).evaluate(0)

            assertThat(actual.terminal).isEqualTo(expected.terminal)
            assertThat(actual.signature).isNotEqualTo(expected.signature)
        }
    }

    @Test
    fun nativeDiagnosticUsesTheSelectedEpsilonForBothTerritoryPhases() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = evaluation(fixture, explorationKind, .02)
            val policy = candidate(input)
            val bundle = requireNotNull(policy.territoryValueBundle) { "The fixture has Q heads." }
            val actual = policy.controller(input.environment)
            val collectionRate =
                policy.copy(territoryValueEpsilon = .2).controller(input.environment)
            for (phase in TerritoryPhase.entries) {
                val records = mutableListOf<TerritoryValueDecisionRecord>()
                val reference = bundle.controller(input.environment.baseline, .02, records::add)
                val decision = decision(phase)
                val results =
                    diagnosticSeeds(phase).map { seed ->
                        val seeded = decision.copy(seed = seed)
                        val expected = reference.choose(seeded)
                        val selected = actual.choose(seeded)
                        val other = collectionRate.choose(seeded)
                        Triple(selected, expected, other)
                    }

                assertThat(results.all { it.first == it.second }).isTrue()
                assertThat(results.any { it.first != it.third }).isTrue()
                assertThat(records.any { it.selectedIndex != it.observation.baselineIndex })
                    .isTrue()
                for (row in records) {
                    val expectedProbability =
                        .02 / row.observation.actions.size +
                            if (row.selectedIndex == row.observation.baselineIndex) .98 else 0.0
                    assertThat(row.behaviorProbability).isWithin(1e-12).of(expectedProbability)
                    assertThat(row.selectorSeed)
                        .isEqualTo(TerritoryValuePolicy.selectorSeed(phase, row.botSeed))
                    assertThat(row.batchStartNumber).isEqualTo(20)
                    assertThat(row.number).isEqualTo(23)
                }
            }
        }
    }

    private fun evaluation(
        fixture: TerritoryValueCollectionFixture,
        kind: String,
        epsilon: Double,
    ): CardAdvantageEvaluationInput =
        CardAdvantageEvaluationInput.read(evaluationSource(fixture, kind, epsilon))

    private fun evaluationSource(
        fixture: TerritoryValueCollectionFixture,
        kind: String,
        epsilon: Double,
    ): Path {
        val input = fixture.evaluation(71)
        val source = PurchaseJson.read(input.source) as ObjectNode
        val candidate = source.required("policies").required("candidate") as ObjectNode
        candidate.put("kind", kind)
        candidate.put("epsilon", epsilon)
        fixture.artifacts.write("territory-evaluation.json", source)
        return input.source
    }

    private fun candidate(input: CardAdvantageEvaluationInput): CardAdvantageEvaluationPolicy =
        requireNotNull(input.policies["candidate"]) { "The fixture registers its candidate." }

    /** Include both rare exploration tails and draws distinguishing .02 from .2. */
    private fun diagnosticSeeds(phase: TerritoryPhase): List<Int> {
        val draws =
            (0..65535).map { seed ->
                seed to TerritoryValuePolicy.uniform(TerritoryValuePolicy.selectorSeed(phase, seed))
            }
        return listOf(
            draws.minBy { it.second }.first,
            draws.first { it.second in 0.03..0.04 }.first,
            draws.first { it.second in 0.96..0.97 }.first,
            draws.maxBy { it.second }.first,
        )
    }

    private fun decision(phase: TerritoryPhase): SimulationDecision {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val own = players[0].id
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val state =
            GameState(
                header = GameHeader(players, own),
                stage =
                    if (phase == TerritoryPhase.TOKEN) Stage.TOKEN_PURCHASE else Stage.EXPANSION,
                turnNumber = 2,
                board =
                    players.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                        board.placeMarker(ProvinceId(capital), player.id).board
                    },
                turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
                players =
                    players
                        .mapIndexed { index, player ->
                            player.id to
                                PlayerState(
                                    player,
                                    capital = capitals[index],
                                    cash = 100,
                                    stockTokens = 26,
                                    poolTokens = 10,
                                )
                        }
                        .toMap(),
                flow =
                    TurnFlow(
                        actor = if (phase == TerritoryPhase.EXPANSION) own else null,
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                    ),
            )
        return SimulationDecision(
            number = 23,
            seat = 0,
            batchStartNumber = 20,
            seed = 0,
            view = state.viewForDecision(own),
            observation = BotObservations.from(state, own),
        )
    }

    companion object {
        private const val explorationKind =
            CardAdvantageEvaluationPolicy.TERRITORY_VALUE_EXPLORATION_KIND
    }
}
