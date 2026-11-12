package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.TerritoryPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TripleCollectionTest {
    @Test
    fun newInputIsNotAcceptedAsAnOldJointSource() {
        TripleCollectionFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> { JointCollectionInput.read(fixture.source) }
        }
    }

    @Test
    fun newTrajectoryIsNotAcceptedAsAnOldJointEpisode() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.game(input, fixture.rows(input))
            assertFailsWith<IllegalArgumentException> {
                JointTrajectoryValidation(input.joint, input.signature)
                    .validate(TripleCollectionStore.wire(game), 0)
            }
        }
    }

    @Test
    fun allThreeRowsUseTheirActualBehaviorAndOneTerminal() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.game(input, fixture.rows(input))
            val wire = TripleCollectionStore.wire(game)
            TripleTrajectoryValidation(input).validate(wire, 0)
            assertThat(wire["kind"].asText()).isEqualTo("triple-on-policy-trajectory-1")
            assertThat(wire["joint"]).isNull()
            assertThat(wire["canonicalReplay"]).isNull()
            assertThat(wire["cardDecisions"].size()).isEqualTo(1)
            assertThat(wire["purchaseDecisions"].size()).isEqualTo(1)
            assertThat(wire["expansionDecisions"].size()).isEqualTo(1)
        }
    }

    @Test
    fun expansionUsesTheExistingReferenceThenExactSampler() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val collector = JointCollector(input.joint, input.signature, Long.MAX_VALUE)
            val decision = fixture.expansion.decision()
            val normal =
                requireNotNull(collector.controllers(0, {}, {}, {})[0]) { "Reference required." }
            val expected = normal.choose(decision)
            val rows = fixture.rows(input)
            val row = rows.single()
            assertThat(row.observation.actions[row.observation.baselineIndex].command)
                .isEqualTo(expected)
            assertThat(row.selectedIndex)
                .isEqualTo(
                    TerritoryPolicy.choose(row.observation, input.expansion.model, row.actorSeed)
                        .index
                )
        }
    }

    @Test
    fun addingExpansionRecordingPreservesActualCardAndPurchaseChoices() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val collector = JointCollector(input.joint, input.signature, Long.MAX_VALUE)
            val originalCards = mutableListOf<CardDecisionRecord>()
            val originalPurchases = mutableListOf<PurchaseDecisionRecord>()
            val cards = mutableListOf<CardDecisionRecord>()
            val purchases = mutableListOf<PurchaseDecisionRecord>()
            val expansions = mutableListOf<TerritoryDecisionRecord>()
            val original =
                requireNotNull(
                    collector.controllers(0, originalCards::add, originalPurchases::add, {})[0]
                ) {
                    "Original actor required."
                }
            val triple =
                requireNotNull(
                    collector
                        .controllers(
                            0,
                            cards::add,
                            purchases::add,
                            {},
                            JointCollector.TripleRecording(input, expansions),
                        )[0]
                ) {
                    "Triple actor required."
                }
            val actor = PlayerId("p1")
            val cardState = jointStateFixture()
            val purchaseState =
                cardState.copy(
                    stage = Stage.PURCHASE,
                    flow =
                        TurnFlow(
                            actor = actor,
                            purchaseStep = PurchaseStep.ADVANCES,
                            phaseStarted = true,
                            phaseBookkeepingApplied = true,
                        ),
                )
            val decisions =
                listOf(cardState, purchaseState).mapIndexed { number, state ->
                    SimulationDecision(
                        number,
                        0,
                        simulationPolicySeed(input.joint.seeds[0], number, 0),
                        state.viewForPlayer(actor),
                        BotObservations.from(state, actor),
                    )
                }
            assertThat(decisions.map(triple::choose)).isEqualTo(decisions.map(original::choose))
            assertThat(cards).isEqualTo(originalCards)
            assertThat(purchases).isEqualTo(originalPurchases)
            assertThat(expansions).isEmpty()
        }
    }

    @Test
    fun otherSeatsNeverAppendControlledPhaseRows() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val cards = mutableListOf<CardDecisionRecord>()
            val purchases = mutableListOf<PurchaseDecisionRecord>()
            val expansions = mutableListOf<TerritoryDecisionRecord>()
            val collector = JointCollector(input.joint, input.signature, Long.MAX_VALUE)
            val controllers =
                collector.controllers(
                    0,
                    cards::add,
                    purchases::add,
                    {},
                    JointCollector.TripleRecording(input, expansions),
                )
            val actor = PlayerId("p2")
            val initial = expansionPairedState()
            val state =
                initial.copy(
                    flow = initial.flow.copy(actor = actor),
                    players =
                        initial.players.mapValues { (id, player) ->
                            player.copy(
                                poolTokens = if (id == actor) 36 else 0,
                                stockTokens = if (id == actor) 0 else 36,
                            )
                        },
                )
            val decision =
                SimulationDecision(
                    7,
                    1,
                    simulationPolicySeed(input.joint.seeds[0], 7, 1),
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                )
            requireNotNull(controllers[1]) { "Rival required." }.choose(decision)
            assertThat(cards).isEmpty()
            assertThat(purchases).isEmpty()
            assertThat(expansions).isEmpty()
        }
    }

    @Test
    fun repeatedAbsoluteNumberAcrossExpansionAndPurchaseIsRejected() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.game(input, fixture.rows(input, number = 1))
            assertFailsWith<IllegalArgumentException> {
                TripleTrajectoryValidation(input).validate(TripleCollectionStore.wire(game), 0)
            }
        }
    }

    @Test
    fun changedExpansionLikelihoodIsRejected() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val changed = fixture.rows(input).single().copy(behaviorValue = .9)
            val game = fixture.game(input, listOf(changed))
            assertFailsWith<IllegalArgumentException> {
                TripleTrajectoryValidation(input).validate(TripleCollectionStore.wire(game), 0)
            }
        }
    }

    @Test
    fun changedExpansionModelCannotHideBehindTheSourceHash() {
        TripleCollectionFixture().use { fixture ->
            val model = PurchaseJson.read(fixture.expansion.actorPath) as ObjectNode
            model.put("valueBias", .2)
            fixture.files.write("expansion-actor.json", model)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun rawCardRivalIsNotACompleteFixedG3Role() {
        TripleCollectionFixture().use { fixture ->
            fixture.base.writeBundle("rival.json", cardName = null)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun omittedCurrentEcologyIsRejected() {
        TripleCollectionFixture().use { fixture ->
            val node = PurchaseJson.read(fixture.base.source) as ObjectNode
            node.remove(JointEcology.FIELDS)
            node.put("baseline", "baseline.json")
            fixture.files.write("source.json", node)
            fixture.refresh()
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun emptyWorldRetainsAllThreeEmptyArraysAndOriginalTerminal() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.prepare()
            store.finish(
                listOf(
                    store.save(fixture.game(input, fixture.rows(input))),
                    store.save(fixture.game(input, emptyList(), 1)),
                )
            )
            val empty =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(fixture.base.output.resolve("game-1.json.gz"))
                ) {
                    "Empty origin retained."
                }
            assertThat(
                    listOf("cardDecisions", "purchaseDecisions", "expansionDecisions").map {
                        empty[it].size()
                    }
                )
                .containsExactly(0, 0, 0)
            assertThat(
                    PurchaseJson.read(fixture.base.output.resolve("finished.json"))["gameSha256"]
                        .size()
                )
                .isEqualTo(2)
        }
    }

    @Test
    fun completionCannotInventAnUnwrittenWorld() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.prepare()
            store.save(fixture.game(input, fixture.rows(input)))
            assertFailsWith<IllegalArgumentException> { store.finish(listOf(0)) }
            assertThat(Files.exists(fixture.base.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun exactSnapshotMappingBindsEveryBehaviorAndPrior() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            fixture.store(input).prepare()
            val manifest = PurchaseJson.read(fixture.base.output.resolve("collection.json"))
            manifest["snapshots"].properties().forEach { (name, subject) ->
                assertThat(PurchaseJson.hash(fixture.base.output.resolve(name)))
                    .isEqualTo(subject["sha256"].asText())
            }
            assertThat(manifest["controlledBehavior"])
                .isEqualTo(PurchaseJson.mapper.valueToTree<JsonNode>(input.controlledBehavior))
        }
    }

    @Test
    fun sampledExpansionAlternativeSurvivesEveryLaterController() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val number =
                (0..256).first {
                    val row = fixture.rows(input, it).single()
                    row.selectedIndex != row.observation.baselineIndex
                }
            val rows = mutableListOf<TerritoryDecisionRecord>()
            val collector = JointCollector(input.joint, input.signature, Long.MAX_VALUE)
            val actor =
                requireNotNull(
                    collector
                        .controllers(0, {}, {}, {}, JointCollector.TripleRecording(input, rows))[0]
                ) {
                    "Controlled actor required."
                }
            val chosen = actor.choose(fixture.expansion.decision(number = number))
            val row = rows.single()
            assertThat(chosen).isEqualTo(row.observation.actions[row.selectedIndex].command)
            assertThat(chosen)
                .isNotEqualTo(row.observation.actions[row.observation.baselineIndex].command)
        }
    }

    @Test
    fun canonicalEntryMutationCannotKeepTheOriginalReceipt() {
        TripleCollectionFixture().use { fixture ->
            val input = fixture.input
            val replay = PurchaseCanonicalReplay(expansionPairedState().header, listOf("pass"))
            val node =
                TripleCollectionStore.wire(
                    TripleCollectionStore.replayDocument(input, 0, "a".repeat(64), replay)
                ) as ObjectNode
            node.set<JsonNode>("entries", PurchaseJson.mapper.valueToTree(listOf("changed")))
            assertFailsWith<IllegalArgumentException> {
                fixture.store(input).validateReplay(node, 0, "a".repeat(64))
            }
        }
    }

    @Test
    fun prepareModeCannotOverwriteAnExistingOutputDirectory() {
        TripleCollectionFixture().use { fixture ->
            Files.createDirectories(fixture.base.output)
            assertFailsWith<IllegalArgumentException> {
                TripleCollectionMain.run(
                    listOf(
                        "--mode",
                        "triple-on-policy-prepare",
                        "--source",
                        fixture.source.toString(),
                        "--output",
                        fixture.base.output.toString(),
                    )
                )
            }
        }
    }
}
