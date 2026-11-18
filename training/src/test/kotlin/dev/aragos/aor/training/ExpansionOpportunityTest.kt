package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionOpportunityTest {
    @Test
    fun prospectiveRequestsRotateSeatsAndEpochsWithoutReplacingIndices() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                ExpansionOpportunitySpecification(fixture.specification, diagnosticRoots = 12)

            val requests =
                (0 until 12).map { specification.lineup(it).seat to specification.epoch(it) }

            assertThat(requests)
                .containsExactlyElementsIn((0..3).flatMap { seat -> (1..3).map { seat to it } })
        }
    }

    @Test
    fun proposalSamplerKeepsBaselineAndDistinctPublicActionFamilies() {
        val observation = observation()

        val chosen = ExpansionOpportunityProposals.indices(observation, 71)

        assertThat(chosen.first()).isEqualTo(observation.baselineIndex)
        assertThat(chosen).hasSize(4)
        assertThat(
                chosen
                    .drop(1)
                    .map { ExpansionOpportunityProposals.family(observation, it) }
                    .distinct()
            )
            .hasSize(3)
    }

    @Test
    fun proposalSamplerIgnoresUnrelatedPublicFeatureValues() {
        val original = observation()
        val changed =
            original.copy(
                stateFeatures = mapOf("unused.public.fixture" to 9.0),
                actions =
                    original.actions.map {
                        it.copy(features = it.features + ("unused.public.quote" to 7.0))
                    },
            )

        val choices =
            listOf(original, changed).map { value ->
                ExpansionOpportunityProposals.indices(value, 71).map { value.actions[it].command }
            }

        assertThat(choices[0]).isEqualTo(choices[1])
    }

    @Test
    fun diagnosticsRejectTokenPopulationOverlays() {
        ArenaPurchaseFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.set<JsonNode>(
                "tokenOrder",
                PurchaseJson.mapper.valueToTree(
                    mapOf("opponentSelectors" to listOf("FIXED_16_TO_15"))
                ),
            )
            fixture.artifacts.write("arena.json", source)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ExpansionOpportunitySpecification(fixture.specification)
                }

            assertThat(failure).hasMessageThat().contains("no token overlays")
        }
    }

    @Test
    fun diagnosticsRejectSideSpecificPurchaseRoles() {
        ArenaPurchaseFixture().use { fixture ->
            val specification = fixture.withOverrides()

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ExpansionOpportunitySpecification(specification)
                }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun diagnosticsCannotExpandTheirContinuationBudget() {
        ArenaPurchaseFixture().use { fixture ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ExpansionOpportunitySpecification(fixture.specification, diagnosticRoots = 129)
                }

            assertThat(failure).hasMessageThat().contains("1–128")
        }
    }

    @Test
    fun collectedRootIsAnActualOrdinaryPassWithRemainingTokens() {
        val origin = captured.origin
        val root = rootState(origin)
        val actor = root.playerIds[origin.seat]
        val observation = BotObservations.from(root, actor)
        val policySeed = simulationPolicySeed(origin.gameSeed, origin.originDecisions, origin.seat)

        val baseline =
            BotPolicy.choose(
                observation,
                BotDifficulty.HARD,
                policySeed,
                captured.specification.arena.candidate,
            )

        assertThat(origin.status).isEqualTo(ExpansionOpportunityStatus.COLLECTED)
        assertThat(root.stage).isEqualTo(Stage.EXPANSION)
        assertThat(baseline).isEqualTo("pass")
        assertThat(root.viewForDecision(actor).players.single { it.id == actor }.poolTokens)
            .isGreaterThan(0)
        assertThat(origin.actualEpoch).isEqualTo(origin.requestedEpoch)
    }

    @Test
    fun completedBranchesRetainTheirExactCanonicalPrefixAndTerminalOutcome() {
        val origin = captured.origin
        val prefix = requireNotNull(origin.prefixEntries) { "The fixture collected a prefix." }
        val header = requireNotNull(origin.header) { "The fixture collected a header." }
        val arms = requireNotNull(origin.alternatives) { "The fixture collected branches." }

        val terminals = arms.flatMap { arm ->
            arm.outcomes.map { sample ->
                assertThat(sample.replayEntries.take(prefix.size)).isEqualTo(prefix)
                assertThat(sample.replayVerified).isTrue()
                val state =
                    sample.replayEntries.fold(GameState(header)) { state, entry ->
                        state.step(TextCodec.parse(entry))
                    }
                assertThat(state.publicView().scores.map(PilotReplayScore::from))
                    .isEqualTo(sample.terminal.scores)
                state.stage
            }
        }

        assertThat(terminals).containsExactlyElementsIn(List(terminals.size) { Stage.FINISHED })
    }

    @Test
    fun branchesUseIdenticalFutureStreamStartsAndPairedDeltas() {
        val origin = captured.origin
        val arms = requireNotNull(origin.alternatives) { "The fixture collected branches." }
        val seeds = List(2) { TokenValueCollector.continuationSeed(origin.gameSeed, it) }

        ExpansionOpportunityMain.validateOrigin(
            PurchaseJson.mapper.valueToTree(origin),
            captured.specification,
            0,
            "fixture",
        )

        assertThat(
                arms.all { it.outcomes.map { sample -> sample.terminal.continuationSeed } == seeds }
            )
            .isTrue()
        assertThat(arms.first().pairedRewardDifferences).containsExactly(0.0, 0.0)
        assertThat(arms.first().pairedWinShareDifferences).containsExactly(0.0, 0.0)
        assertThat(origin.meanAlternativeRewardDifference)
            .isEqualTo(arms.drop(1).map { it.meanRewardDifference }.average())
    }

    @Test
    fun malformedPairedLabelsCannotReplaceTheBaselineDifference() {
        val node = PurchaseJson.mapper.valueToTree<ObjectNode>(captured.origin)
        (node["alternatives"][1] as ObjectNode).put("meanRewardDifference", 999.0)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExpansionOpportunityMain.validateOrigin(node, captured.specification, 0, "fixture")
            }

        assertThat(failure).hasMessageThat().contains("paired arm-minus-baseline")
    }

    @Test
    fun skippedOriginsHaveNoTargetsAndCannotMasqueradeAsCollectedRoots() {
        val lineup = captured.specification.lineup(0)
        val skipped =
            ExpansionOpportunityOrigin(
                signature = "fixture",
                index = 0,
                configuration = lineup.configuration.id,
                gameSeed = lineup.seed,
                seat = lineup.seat,
                opponentIndices = lineup.opponentIndices,
                requestedEpoch = 1,
                proposalSeed = ExpansionOpportunityProposals.seed(lineup.seed),
                originDecisions = 42,
                status = ExpansionOpportunityStatus.SKIPPED,
                skipReason = ExpansionOpportunitySkip.NO_ELIGIBLE_PASS,
            )
        val node = PurchaseJson.mapper.valueToTree<ObjectNode>(skipped)
        node.putArray("alternatives")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExpansionOpportunityMain.validateOrigin(node, captured.specification, 0, "fixture")
            }

        assertThat(failure).hasMessageThat().contains("Skipped expansion origins")
    }

    @Test
    fun emptyPoolCannotBeAnEligiblePassRoot() {
        val origin = captured.origin
        val root = rootState(origin)
        val actor = root.playerIds[origin.seat]
        val view = root.viewForDecision(actor)
        val empty =
            view.copy(
                players = view.players.map { if (it.id == actor) it.copy(poolTokens = 0) else it }
            )

        val eligible =
            ExpansionOpportunityProposals.eligible(
                empty,
                actor,
                BotObservations.from(root, actor),
                "pass",
            )

        assertThat(eligible).isFalse()
    }

    @Test
    fun diagnosticCollectionChecksItsDeadlineBeforeAnyPrefixWork() {
        ArenaPurchaseFixture().use { fixture ->
            val specification =
                ExpansionOpportunitySpecification(fixture.specification, diagnosticRoots = 1)

            assertFailsWith<CancellationException> {
                ExpansionOpportunityCollector(specification, "fixture", 0).collect(0)
            }
        }
    }

    @Test
    fun nativeDiagnosticWritesCompleteHashedEvidence() {
        ArenaPurchaseFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("diagnosticRoots", 1)
            fixture.artifacts.write("arena.json", source)
            val output = fixture.directory.resolve("diagnostic")
            val arguments =
                listOf(
                    "--mode",
                    "expansion-opportunity-collect",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.toString(),
                    "--workers",
                    "1",
                    "--hours",
                    ".1",
                )

            ExpansionOpportunityMain.run(arguments)

            val dataset = PurchaseJson.read(output.resolve("dataset.json"))
            assertThat(dataset["complete"].asBoolean()).isTrue()
            assertThat(dataset["maximumTerminalContinuations"].asInt()).isEqualTo(8)
            assertThat(
                    PurchaseJson.read(output.resolve("finished.json"))["originSha256"].map(
                        JsonNode::asText
                    )
                )
                .containsExactly(PurchaseJson.hash(output.resolve("origin-0.json")))
        }
    }

    @Test
    fun eachBranchAppliesItsDeclaredAlternativeBeforeTheOrdinaryTail() {
        val origin = captured.origin
        val root = rootState(origin)
        val prefixSize =
            requireNotNull(origin.prefixEntries) { "The fixture retains its prefix." }.size
        val actor = root.playerIds[origin.seat]
        val alternatives =
            requireNotNull(origin.alternatives) { "The fixture retains its alternatives." }

        for (alternative in alternatives) {
            val expected = TextCodec.parseAction(alternative.command, root, actor = actor)
            assertThat(alternative.outcomes.map { TextCodec.parse(it.replayEntries[prefixSize]) })
                .containsExactly(expected, expected)
        }
    }

    @Test
    fun nativeDiagnosticRejectsAnExistingAttemptWithoutRenewingIt() {
        ArenaPurchaseFixture().use { fixture ->
            val output = fixture.directory.resolve("existing")
            val artifacts = TrainingArtifacts(output)
            artifacts.write("state.json", mapOf("phase" to "collecting"))
            val arguments =
                listOf(
                    "--mode",
                    "expansion-opportunity-collect",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.toString(),
                    "--workers",
                    "1",
                    "--hours",
                    ".1",
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ExpansionOpportunityMain.run(arguments)
                }

            assertThat(failure).hasMessageThat().contains("existing attempts cannot be renewed")
        }
    }

    private fun observation(): TerritoryObservation {
        val actions =
            listOf(
                    TerritoryAction("pass", mapOf("kind.pass" to 1.0)),
                    TerritoryAction("place venice 1", mapOf("kind.place" to 1.0)),
                    TerritoryAction("place genoa 1", mapOf("kind.place" to 1.0)),
                    TerritoryAction("attack paris", mapOf("kind.attack" to 1.0)),
                    TerritoryAction("reduce london", mapOf("kind.reduce" to 1.0)),
                    TerritoryAction("buy-card", mapOf("kind.buyCard" to 1.0)),
                )
                .sortedBy(TerritoryAction::command)
        return TerritoryObservation(
            TerritoryEncoder.EXPANSION_2.version,
            emptyMap(),
            actions,
            actions.indexOfFirst { it.command == "pass" },
        )
    }

    private fun rootState(origin: ExpansionOpportunityOrigin): GameState =
        requireNotNull(origin.prefixEntries) { "The fixture retains its prefix." }
            .fold(GameState(requireNotNull(origin.header) { "The fixture retains its header." })) {
                state,
                entry ->
                state.step(TextCodec.parse(entry))
            }

    companion object {
        private data class Captured(
            val specification: ExpansionOpportunitySpecification,
            val origin: ExpansionOpportunityOrigin,
        )

        private val captured by lazy {
            ArenaPurchaseFixture().use { fixture ->
                val model =
                    BotModels.default.copy(
                        weights = BotModels.default.weights + ("action.pass" to 1.0e6)
                    )
                val arena = fixture.specification.copy(candidate = model, incumbent = model)
                val specification = ExpansionOpportunitySpecification(arena, diagnosticRoots = 1)
                Captured(
                    specification,
                    ExpansionOpportunityCollector(specification, "fixture", Long.MAX_VALUE)
                        .collect(0),
                )
            }
        }
    }
}
