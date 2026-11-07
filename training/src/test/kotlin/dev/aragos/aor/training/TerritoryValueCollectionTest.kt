package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryValuePolicy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TerritoryValueCollectionTest {
    @Test
    fun collectionRequiresExplicitPointTwoAndEvaluationRequiresZero() {
        TerritoryValueCollectionFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("epsilon", 0.0)
            fixture.artifacts.write("territory-source.json", source)
            assertFailsWith<IllegalArgumentException> { fixture.input }
            fixture.evaluation(123)
            val path = fixture.artifacts.directory.resolve("territory-evaluation.json")
            val evaluation = PurchaseJson.read(path) as ObjectNode
            (evaluation.required("policies").required("candidate") as ObjectNode).put("epsilon", .2)
            fixture.artifacts.write("territory-evaluation.json", evaluation)
            assertFailsWith<IllegalArgumentException> { CardAdvantageEvaluationInput.read(path) }
        }
    }

    @Test
    fun ppoAndLinearValueModelsCannotBeReinterpreted() {
        TerritoryValueCollectionFixture().use { fixture ->
            val valuePath = fixture.artifacts.directory.resolve("token-policy.json")
            assertFailsWith<IllegalArgumentException> { TerritoryPolicyInput.read(valuePath) }
            val value = PurchaseJson.read(valuePath) as ObjectNode
            value.put("baselineProbability", .97)
            fixture.artifacts.write("with-ppo-field.json", value)
            assertFailsWith<IllegalArgumentException> {
                TerritoryValuePolicyInput.read(
                    fixture.artifacts.directory.resolve("with-ppo-field.json")
                )
            }
        }
    }

    @Test
    fun recordedProbabilityAndSelectedQMustMatchTheFrozenModel() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.syntheticGame(input)
            val wrong =
                game.copy(
                    tokenDecisions = listOf(game.tokenDecisions.single().copy(selectedQ = .7))
                )
            assertFailsWith<IllegalArgumentException> {
                TerritoryValueTrajectoryValidation(input, "fixture")
                    .validate(PurchaseJson.mapper.valueToTree(wrong), 0)
            }
            val probability =
                game.copy(
                    tokenDecisions =
                        listOf(game.tokenDecisions.single().copy(behaviorProbability = .97))
                )
            assertFailsWith<IllegalArgumentException> {
                TerritoryValueTrajectoryValidation(input, "fixture")
                    .validate(PurchaseJson.mapper.valueToTree(probability), 0)
            }
        }
    }

    @Test
    fun exploratoryCollectionRecordsOnlyItsSeatAndActualSealedSeeds() {
        TerritoryValueCollectionFixture(games = 4, learnedRivals = true).use { fixture ->
            val input = fixture.input
            val collected = TerritoryValueCollector(input, "fixture", Long.MAX_VALUE).collect(3)

            assertThat(collected.tokenDecisions).isNotEmpty()
            assertThat(collected.expansionDecisions).isNotEmpty()
            assertThat(collected.tokenDecisions.any { it.number > it.batchStartNumber }).isTrue()
            for (row in collected.tokenDecisions + collected.expansionDecisions) {
                val expected = input.seeds[3] xor (row.batchStartNumber * 65537 + 3 * 8191)
                val phase = TerritoryPhase.entries.single { it.decision == row.decision }
                assertThat(row.botSeed).isEqualTo(expected)
                assertThat(row.selectorSeed)
                    .isEqualTo(TerritoryValuePolicy.selectorSeed(phase, expected))
                assertThat(row.selectedQ).isEqualTo(.5)
                val count = row.observation.actions.size
                val probability =
                    .2 / count + if (row.selectedIndex == row.observation.baselineIndex) .8 else 0.0
                assertThat(row.behaviorProbability).isWithin(1e-12).of(probability)
            }
            TerritoryValueTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(collected), 3)
        }
    }

    @Test
    fun greedyInitialValuesAndDisabledHeadsPreserveFrozenJointFullTrace() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = fixture.input
            val disabled = input.opponents.single()
            val registry = fixture.evaluation(input.seeds[0])
            val oldTrace = CanonicalTrace()
            val newTrace = CanonicalTrace()
            fun play(useValues: Boolean, trace: CanonicalTrace): SimulatedGame =
                Simulation()
                    .play(
                        configuration = input.configuration,
                        seed = input.seeds[0],
                        competitors =
                            List(4) { Competitor(input.baseline.model, BotDifficulty.HARD) },
                        trace = trace,
                        controllers =
                            (0..3).associateWith { seat ->
                                val prior = input.frozen.controller(input.baseline)
                                val empty = disabled.controller(input.baseline, 0.0)
                                val delegate =
                                    if (useValues) {
                                        requireNotNull(
                                                registry.policies[
                                                        if (seat == 0) "candidate" else "rival"]
                                            ) {
                                                "The fixture registry contains each role."
                                            }
                                            .controller(registry.environment)
                                    } else prior
                                object : SimulationController {
                                    override fun controls(decision: String) =
                                        delegate.controls(decision)

                                    override fun choose(decision: SimulationDecision): String? {
                                        val scope = decision.observation.decision
                                        val original =
                                            if (prior.controls(scope)) prior.choose(decision)
                                            else null
                                        val fallback =
                                            if (empty.controls(scope)) empty.choose(decision)
                                            else null
                                        assertThat(fallback).isEqualTo(original)
                                        return delegate.choose(decision)
                                    }
                                }
                            },
                    )
            val original = play(false, oldTrace)
            val actual = play(true, newTrace)

            assertThat(actual.scores).isEqualTo(original.scores)
            assertThat(actual.steps).isEqualTo(original.steps)
            assertThat(newTrace.header).isEqualTo(oldTrace.header)
            assertThat(newTrace.entries).isEqualTo(oldTrace.entries)
            assertThat(disabled.controller(input.baseline, 0.0).controls("buy-tokens")).isFalse()
            assertThat(disabled.controller(input.baseline, 0.0).controls("expand")).isFalse()
            assertThat(disabled.controller(input.baseline, 0.0).controls("prepare-war")).isFalse()
        }
    }

    @Test
    fun sealedFourthSeatProvenanceRejectsNumberDerivedSeeds() {
        TerritoryValueCollectionFixture(games = 4).use { fixture ->
            val input = fixture.input
            val original = fixture.syntheticGame(input, 3)
            TerritoryValueTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(original), 3)
            val token = original.tokenDecisions.single()
            val wrongSeed = input.seeds[3] xor (token.number * 65537 + 3 * 8191)
            val changed =
                original.copy(
                    tokenDecisions =
                        listOf(
                            token.copy(
                                botSeed = wrongSeed,
                                selectorSeed =
                                    TerritoryValuePolicy.selectorSeed(
                                        TerritoryPhase.TOKEN,
                                        wrongSeed,
                                    ),
                            )
                        )
                )

            assertFailsWith<IllegalArgumentException> {
                TerritoryValueTrajectoryValidation(input, "fixture")
                    .validate(PurchaseJson.mapper.valueToTree(changed), 3)
            }
        }
    }

    @Test
    fun emptyPhaseRetainsItsOriginAndExactResumeDoesNotRewriteIt() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)
            store.save(fixture.syntheticGame(input).copy(expansionDecisions = emptyList()))
            store.finish()
            val path = fixture.output.resolve("game-0.json.gz")
            val before = Files.getLastModifiedTime(path)
            val hash = PurchaseJson.hash(path)

            val resumed = fixture.store(input)
            resumed.finish()

            assertThat(resumed.completedIndices()).containsExactly(0)
            assertThat(PurchaseJson.hash(path)).isEqualTo(hash)
            assertThat(Files.getLastModifiedTime(path)).isEqualTo(before)
            assertThat(
                    requireNotNull(CardTrajectoryFiles.readIfPresent(path)) {
                            "The completed game is retained."
                        }
                        .required("expansionDecisions")
                        .size()
                )
                .isEqualTo(0)
        }
    }

    @Test
    fun controlledBundleRequiresBothTypedHeads() {
        TerritoryValueCollectionFixture().use { fixture ->
            fixture.writeBundle("territory.json", token = false)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun bundleRequiresItsExactFrozenContinuation() {
        TerritoryValueCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("territory.json")
            val raw = PurchaseJson.read(path) as ObjectNode
            raw.put("frozenCardPurchaseBundleSha256", "0".repeat(64))
            fixture.artifacts.write("territory.json", raw)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun cancellationCannotWriteAPartiallyObservedGame() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            assertFailsWith<CancellationException> {
                store.save(TerritoryValueCollector(input, "fixture", Long.MIN_VALUE).collect(0))
            }

            assertThat(store.completedIndices()).isEmpty()
            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
        }
    }

    @Test
    fun changedComponentBytesCannotHideBehindTheSameBundle() {
        TerritoryValueCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("expansion-policy.json")
            val model = PurchaseJson.read(path) as ObjectNode
            model.put("outputBias", .1)
            fixture.artifacts.write("expansion-policy.json", model)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun phaseArraysCannotReuseGlobalNumbers() {
        TerritoryValueCollectionFixture().use { fixture ->
            val input = fixture.input
            val original = fixture.syntheticGame(input)
            val changed =
                original.copy(
                    expansionDecisions =
                        listOf(
                            original.expansionDecisions
                                .single()
                                .copy(number = 0, batchStartNumber = 0)
                        )
                )

            assertFailsWith<IllegalArgumentException> { fixture.store(input).save(changed) }
        }
    }

    @Test
    fun incompleteGamesCannotPublishCompletion() {
        TerritoryValueCollectionFixture(games = 2).use { fixture ->
            val store = fixture.store()
            store.save(fixture.syntheticGame())

            assertFailsWith<IllegalArgumentException> { store.finish() }
            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun snapshotsBindEveryActorAndTheSharedFrozenContinuation() {
        TerritoryValueCollectionFixture(learnedRivals = true).use { fixture ->
            val input = fixture.input
            fixture.store(input)

            assertThat(
                    PurchaseJson.hash(fixture.output.resolve("frozen-card-purchase-bundle.json"))
                )
                .isEqualTo(input.frozen.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("frozen-purchase-policy.json")))
                .isEqualTo(input.frozen.purchase.sha256)
            assertThat(PurchaseJson.hash(fixture.output.resolve("token-policy.json")))
                .isEqualTo(input.bundle.token?.sha256)
            assertThat(
                    PurchaseJson.hash(fixture.output.resolve("opponent-0-expansion-policy.json"))
                )
                .isEqualTo(input.opponents[0].expansion?.sha256)
            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(
                    manifest
                        .required("bundle")
                        .required("token")
                        .required("stateFeatureNames")
                        .size()
                )
                .isEqualTo(input.bundle.tokenDictionary.stateFeatureNames.size)
            assertThat(fixture.evaluation(input.seeds[0]).manifest()["territoryValueVersion"])
                .isEqualTo(TerritoryValueBundle.VERSION)
        }
    }

    private class CanonicalTrace : SimulationTrace {
        override val seat = 0
        lateinit var header: GameHeader
        lateinit var entries: List<String>

        override fun decision(
            number: Int,
            snapshotEntryCount: Int,
            commandEntryIndex: Int,
            view: GameView,
            observation: BotObservation,
            chosen: BotCandidate,
            model: BotModel,
        ) = Unit

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }
}
