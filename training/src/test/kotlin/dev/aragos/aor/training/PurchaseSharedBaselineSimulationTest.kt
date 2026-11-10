package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test

class PurchaseSharedBaselineSimulationTest {
    @Test
    fun sharedPriorCollectorMatchesTheNativeHybridArena() {
        val games = learnedRivals

        assertThat(games.reference.copy(elapsedMillis = 0))
            .isEqualTo(
                requireNotNull(games.arena) { "Missing fixture arena." }.copy(elapsedMillis = 0)
            )
        assertThat(games.collected.terminal.scores).isEqualTo(games.reference.scores)
        assertThat(games.collected.terminal.gameDecisions).isEqualTo(games.reference.decisions)
        assertThat(games.collected.terminal.reward).isEqualTo(games.reference.rewards[0])
    }

    @Test
    fun sharedPriorPreservesControlledActorSeedsAndBehaviorLikelihoods() {
        val games = learnedRivals

        assertThat(games.changedPriorSeats).contains(0)
        assertThat(games.collected.decisions).isNotEmpty()
        assertThat(games.collected.decisions).isEqualTo(games.records[0])
        assertThat(games.collected.decisions.all { it.behaviorLogProbability != null }).isTrue()
    }

    @Test
    fun learnedRivalsUseTheSharedPriorWhenTheirOrdinaryRecommendationsDisagree() {
        val games = learnedRivals

        assertThat(games.changedPriorSeats.filter { it != 0 }).isNotEmpty()
        assertThat(games.records.drop(1).all { it.isNotEmpty() }).isTrue()
        assertThat(games.collected.terminal.scores).isEqualTo(games.reference.scores)
        assertThat(games.collected.decisions).isEqualTo(games.records[0])
    }

    @Test
    fun nonpurchaseDecisionsKeepTheSeparateOrdinaryModel() {
        val games = learnedRivals

        assertThat(games.changedOrdinarySeats).contains(0)
        assertThat(games.changedOrdinarySeats.filter { it != 0 }).isNotEmpty()
        assertThat(games.collected.terminal.scores).isEqualTo(games.reference.scores)
        assertThat(games.collected.decisions).isEqualTo(games.records[0])
    }

    @Test
    fun sharedPriorAlsoControlsRivalsWithoutALearnedActor() {
        val games = baselineRivals

        assertThat(games.changedPriorSeats.filter { it != 0 }).isNotEmpty()
        assertThat(games.records.drop(1).flatten().all { it.behaviorLogProbability == null })
            .isTrue()
        assertThat(games.collected.terminal.scores).isEqualTo(games.reference.scores)
        assertThat(games.collected.terminal.gameDecisions).isEqualTo(games.reference.decisions)
        assertThat(games.collected.decisions).isEqualTo(games.records[0])
    }

    private data class Games(
        val reference: ArenaGame,
        val arena: ArenaGame?,
        val collected: PurchaseGameTrajectory,
        val records: List<List<PurchaseDecisionRecord>>,
        val changedPriorSeats: Set<Int>,
        val changedOrdinarySeats: Set<Int>,
    )

    companion object {
        private val learnedRivals by lazy { games(learnedRivals = true) }
        private val baselineRivals by lazy { games(learnedRivals = false) }

        private fun games(learnedRivals: Boolean): Games =
            ArenaPurchaseFixture().use { fixture ->
                val actorPath = fixture.directory.resolve("policy.json")
                val actor = PurchaseJson.read(actorPath) as ObjectNode
                val features = actor["actionFeatureNames"].map { it.asText() }
                (actor["actionWeights"] as ArrayNode).set(
                    features.indexOf("kind.pass"),
                    PurchaseJson.mapper.nodeFactory.numberNode(8.0),
                )
                (actor["actorWeights"] as ArrayNode).set(
                    0,
                    PurchaseJson.mapper.nodeFactory.numberNode(.25),
                )
                actor.put("valueBias", .25)
                fixture.artifacts.write("policy.json", actor)
                val ordinary =
                    BotModels.default.copy(
                        weights =
                            BotModels.default.weights +
                                mapOf("bidPreference" to -8.0) +
                                AdvanceId.entries.associate {
                                    "advance.${it.letter}" to 1_000_000.0
                                }
                    )
                fixture.artifacts.model(ordinary, "ordinary.json")
                fixture.artifacts.write(
                    "collection-source.json",
                    mapOf(
                        "schemaVersion" to 1,
                        "configuration" to fixture.configuration.id,
                        "games" to 1,
                        "seed" to 71,
                        "baseline" to "ordinary.json",
                        "opponents" to listOf("ordinary.json"),
                        "policy" to "policy.json",
                        "opponentPurchasePolicy" to if (learnedRivals) "policy.json" else null,
                        "sharedPurchaseBaseline" to "baseline.json",
                    ),
                )
                val input =
                    PurchaseCollectionInput.read(
                        fixture.directory.resolve("collection-source.json")
                    )
                val shared =
                    requireNotNull(input.sharedPurchaseBaseline) { "Missing fixture prior." }.model
                val records = List(4) { mutableListOf<PurchaseDecisionRecord>() }
                val changedPriorSeats = mutableSetOf<Int>()
                val changedOrdinarySeats = mutableSetOf<Int>()
                val reference =
                    Simulation()
                        .play(
                            input.configuration,
                            input.seeds[0],
                            List(4) { Competitor(ordinary, BotDifficulty.HARD) },
                            verifyReplay = true,
                            controllers =
                                (0..3).associateWith { seat ->
                                    val actor =
                                        PurchaseActorController(
                                            shared,
                                            if (seat == 0) input.policy
                                            else input.opponentPurchasePolicy?.model,
                                        ) {
                                            records[seat].add(it)
                                        }
                                    object : SimulationController {
                                        override fun controls(decision: String): Boolean = true

                                        override fun choose(decision: SimulationDecision): String? {
                                            val purchase =
                                                actor.controls(decision.observation.decision)
                                            if (
                                                BotPolicy.choose(
                                                    decision.observation,
                                                    BotDifficulty.HARD,
                                                    decision.seed,
                                                    shared,
                                                ) !=
                                                    BotPolicy.choose(
                                                        decision.observation,
                                                        BotDifficulty.HARD,
                                                        decision.seed,
                                                        ordinary,
                                                    )
                                            ) {
                                                if (purchase) {
                                                    changedPriorSeats.add(seat)
                                                } else {
                                                    changedOrdinarySeats.add(seat)
                                                }
                                            }
                                            return if (purchase) actor.choose(decision) else null
                                        }
                                    }
                                },
                        )
                val specification =
                    fixture.specification.copy(
                        candidate = ordinary,
                        incumbent = ordinary,
                        opponents = listOf(ordinary),
                    )
                val collected =
                    PurchaseCollector(input, input.signature(), Long.MAX_VALUE).collect(0)
                PurchaseTrajectoryValidation(input, input.signature())
                    .validate(PurchaseJson.mapper.valueToTree(collected), 0)
                Games(
                    ArenaGame.from(reference),
                    if (learnedRivals) {
                        StrategyArena.game(specification, 0, ArenaSide.CANDIDATE, Long.MAX_VALUE)
                    } else {
                        null
                    },
                    collected,
                    records.map { it.toList() },
                    changedPriorSeats.toSet(),
                    changedOrdinarySeats.toSet(),
                )
            }
    }
}
