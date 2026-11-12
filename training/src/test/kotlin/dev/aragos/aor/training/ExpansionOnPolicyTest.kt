package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import java.nio.file.Files
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionOnPolicyTest {
    @Test
    fun actualQualifiedEIsTheFullOfferBaselineBeforeSampling() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val decision = fixture.decision(input)
            val reference =
                input.arena.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    input.arena.candidate,
                    List(4) { 0 },
                )
            val expected =
                requireNotNull(reference[0]) { "Reference is required." }.choose(decision)
            val rows = mutableListOf<TerritoryDecisionRecord>()

            requireNotNull(input.controllers(0, rows::add)[0]) { "Learner is required." }
                .choose(decision)

            val observation = rows.single().observation
            assertThat(observation.encoderVersion).isEqualTo(TerritoryEncoder.EXPANSION_2.version)
            assertThat(observation.actions.map { it.command })
                .containsExactlyElementsIn(
                    decision.observation.candidates.map { it.command }.sorted()
                )
                .inOrder()
            assertThat(observation.actions[observation.baselineIndex].command).isEqualTo(expected)
            val probabilities =
                TerritoryPolicy.evaluate(observation, input.actor.model).probabilities
            assertThat(probabilities[observation.baselineIndex]).isWithin(1e-12).of(.97)
            assertThat(probabilities.indexOf(probabilities.max()))
                .isEqualTo(observation.baselineIndex)
        }
    }

    @Test
    fun sampledAlternativeIsNotOverwrittenByTheReferenceOverlay() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val decision = fixture.decision(input)
            val first = decision.observation.candidates.map { it.command }.sorted().first()
            var calls = 0
            val reference =
                object : SimulationController {
                    override fun controls(decision: String) = decision == "expand"

                    override fun choose(decision: SimulationDecision): String {
                        calls++
                        return first
                    }
                }
            val seed =
                (0..10000).first {
                    TerritoryPolicy.uniform(
                        TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, it)
                    ) > .97
                }
            val rows = mutableListOf<TerritoryDecisionRecord>()

            val chosen =
                ExpansionOnPolicyController(reference, input.actor.model, rows::add)
                    .choose(decision.copy(seed = seed))

            assertThat(calls).isEqualTo(1)
            assertThat(chosen).isNotEqualTo(first)
            val row = rows.single()
            assertThat(chosen).isEqualTo(row.observation.actions[row.selectedIndex].command)
            assertThat(exp(row.behaviorLogProbability))
                .isWithin(1e-12)
                .of(
                    TerritoryPolicy.evaluate(row.observation, input.actor.model)
                        .probabilities[row.selectedIndex]
                )
        }
    }

    @Test
    fun unchangedCardPurchaseAndTokenRoutesDelegateWithoutRecording() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val original = fixture.decision(input)
            val decisions = mutableListOf<String>()
            val reference =
                object : SimulationController {
                    override fun controls(decision: String) = true

                    override fun choose(decision: SimulationDecision): String {
                        decisions.add(decision.observation.decision)
                        return "unchanged"
                    }
                }
            val rows = mutableListOf<TerritoryDecisionRecord>()
            val wrapper = ExpansionOnPolicyController(reference, input.actor.model, rows::add)

            val commands =
                listOf("play-card", "purchase-shipping", "purchase-advances", "buy-tokens").map {
                    wrapper.choose(
                        original.copy(
                            observation =
                                original.observation.copy(decision = it, expansionPlanning = null)
                        )
                    )
                }

            assertThat(commands).containsExactlyElementsIn(List(4) { "unchanged" })
            assertThat(decisions)
                .containsExactly(
                    "play-card",
                    "purchase-shipping",
                    "purchase-advances",
                    "buy-tokens",
                )
                .inOrder()
            assertThat(rows).isEmpty()
        }
    }

    @Test
    fun actualRivalCardControllersRemainExact() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val state = jointStateFixture()
            val actor = state.playerIds.first()
            val decision =
                SimulationDecision(
                    11,
                    0,
                    71,
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                )
            val normal =
                input.arena.controllers(
                    4,
                    0,
                    ArenaSide.CANDIDATE,
                    input.arena.candidate,
                    List(4) { 0 },
                )
            val learning = input.controllers(0)

            val actual =
                (0..3).map {
                    requireNotNull(learning[it]) { "All seats are required." }.choose(decision)
                }

            assertThat(actual)
                .containsExactlyElementsIn(
                    (0..3).map {
                        requireNotNull(normal[it]) { "All reference seats are required." }
                            .choose(decision)
                    }
                )
                .inOrder()
        }
    }

    @Test
    fun absoluteNumberAndSampleLikelihoodRoundTripThroughNewValidator() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val rows = mutableListOf<TerritoryDecisionRecord>()
            requireNotNull(input.controllers(0, rows::add)[0]) { "Learner is required." }
                .choose(fixture.decision(input, 19))

            ExpansionOnPolicyValidation(input)
                .validate(
                    ExpansionOnPolicyStore.wire(fixture.game(input, rows)),
                    0,
                )

            assertThat(rows.single().number).isEqualTo(19)
            assertThat(rows.single().batchStartNumber).isEqualTo(19)
            assertThat(rows.single().botSeed).isEqualTo(simulationPolicySeed(input.seeds[0], 19, 0))
        }
    }

    @Test
    fun changedRecordedLikelihoodIsRejected() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val rows = mutableListOf<TerritoryDecisionRecord>()
            requireNotNull(input.controllers(0, rows::add)[0]) { "Learner is required." }
                .choose(fixture.decision(input))
            val changed = rows.map {
                it.copy(behaviorLogProbability = it.behaviorLogProbability - .1)
            }

            assertFailsWith<IllegalArgumentException> {
                ExpansionOnPolicyValidation(input)
                    .validate(ExpansionOnPolicyStore.wire(fixture.game(input, changed)), 0)
            }
        }
    }

    @Test
    fun changedBatchStartIsRejectedBeforeSeedReplay() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val rows = mutableListOf<TerritoryDecisionRecord>()
            requireNotNull(input.controllers(0, rows::add)[0]) { "Learner is required." }
                .choose(fixture.decision(input))
            val changed = rows.map { it.copy(batchStartNumber = it.number - 1) }

            assertFailsWith<IllegalArgumentException> {
                ExpansionOnPolicyValidation(input)
                    .validate(ExpansionOnPolicyStore.wire(fixture.game(input, changed)), 0)
            }
        }
    }

    @Test
    fun missingExpansionRowsStillRetainACompleteOrigin() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            ExpansionOnPolicyValidation(input)
                .validate(ExpansionOnPolicyStore.wire(fixture.game(input, emptyList())), 0)
        }
    }

    @Test
    fun zeroGamePrepareRetainsEveryEcologyRoleSnapshot() {
        ExpansionOnPolicyFixture(replays = listOf(0)).use { fixture ->
            ExpansionOnPolicyMain.run(
                listOf(
                    "--mode",
                    "expansion-on-policy-prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    fixture.output.toString(),
                )
            )
            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))

            assertThat(manifest["actor"]["modelSchemaVersion"].intValue()).isEqualTo(2)
            assertThat(
                    Files.exists(
                        fixture.output.resolve("ecology/expansion-imitation-incumbent.json")
                    )
                )
                .isTrue()
            assertThat(
                    Files.exists(fixture.output.resolve("ecology/joint-shared-card-policy.json"))
                )
                .isTrue()
            assertThat(Files.exists(fixture.output.resolve("ecology/joint-purchase-baseline.json")))
                .isTrue()
            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
        }
    }

    @Test
    fun changedModelBytesCannotReuseSourceIdentity() {
        ExpansionOnPolicyFixture().use { fixture ->
            val model = PurchaseJson.read(fixture.actorPath) as ObjectNode
            model.put("valueBias", .1)
            fixture.files.write("expansion-actor.json", model)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun legacyTerritoryBundleRejectsSchemaTwoComponent() {
        TerritoryCollectionFixture().use { fixture ->
            ExpansionOnPolicyFixture().use { current ->
                fixture.artifacts.write(
                    "expansion-policy.json",
                    PurchaseJson.read(current.actorPath),
                )
                fixture.writeBundle("territory.json")

                assertFailsWith<IllegalArgumentException> { fixture.input }
            }
        }
    }

    @Test
    fun partialBankCannotPublishCompletion() {
        ExpansionOnPolicyFixture().use { fixture ->
            val store = ExpansionOnPolicyStore(fixture.input, fixture.output)
            store.prepare()

            assertFailsWith<IllegalArgumentException> { store.finish(listOf(0)) }
        }
    }

    @Test
    fun completeEmptyPhaseWorldPublishesItsOriginalCompressedHash() {
        ExpansionOnPolicyFixture(games = 1).use { fixture ->
            val input = fixture.input
            val store = ExpansionOnPolicyStore(input, fixture.output)
            store.prepare()
            val index = store.save(fixture.game(input, emptyList()))

            store.finish(listOf(index))

            val receipt = PurchaseJson.read(fixture.output.resolve("finished.json"))
            assertThat(receipt["complete"].booleanValue()).isTrue()
            assertThat(receipt["gameSha256"]["game-0.json.gz"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.output.resolve("game-0.json.gz")))
            assertThat(receipt["canonicalSha256"].size()).isEqualTo(0)
        }
    }

    @Test
    fun completedOriginCannotBeOverwritten() {
        ExpansionOnPolicyFixture(games = 1).use { fixture ->
            val input = fixture.input
            val store = ExpansionOnPolicyStore(input, fixture.output)
            store.prepare()
            val game = fixture.game(input, emptyList())
            store.save(game)

            assertFailsWith<IllegalArgumentException> { store.save(game) }
        }
    }
}
