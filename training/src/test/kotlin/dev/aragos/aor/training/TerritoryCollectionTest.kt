package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TerritoryCollectionTest {
    @Test
    fun learnedRivalsAndSealedFourthSeatMatchRepeatedEvaluationWithoutPrivateRows() {
        TerritoryCollectionFixture(games = 4, learnedRivals = true).use { fixture ->
            val input = fixture.input
            val collected = TerritoryCollector(input, "fixture", Long.MAX_VALUE).collect(3)
            val evaluation = fixture.evaluation(input.seeds[3], seat = 3)

            val evaluated = CardAdvantageEvaluator(evaluation, Long.MAX_VALUE).evaluate(0)

            assertThat(collected.terminal).isEqualTo(evaluated.terminal)
            assertThat(collected.tokenDecisions).isNotEmpty()
            assertThat(collected.expansionDecisions).isNotEmpty()
            assertThat(collected.tokenDecisions.any { it.number > it.batchStartNumber }).isTrue()
            collected.tokenDecisions.forEach { row ->
                val expected = input.seeds[3] xor (row.batchStartNumber * 65537 + 3 * 8191)
                assertThat(row.botSeed).isEqualTo(expected)
                assertThat(row.actorSeed)
                    .isEqualTo(TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, expected))
            }
            TerritoryTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(collected), 3)
        }
    }

    @Test
    fun disabledTerritoryHeadsPreserveExistingJointControllerChoicesAndFullGame() {
        TerritoryCollectionFixture().use { fixture ->
            val input = fixture.input
            val disabled = input.opponents.single()
            val oldTrace = mutableListOf<Pair<Int, String>>()
            val newTrace = mutableListOf<Pair<Int, String>>()
            fun play(territory: Boolean, trace: MutableList<Pair<Int, String>>) =
                Simulation()
                    .play(
                        configuration = input.configuration,
                        seed = input.seeds[0],
                        competitors =
                            List(4) { Competitor(input.baseline.model, BotDifficulty.HARD) },
                        controllers =
                            (0..3).associateWith {
                                val delegate =
                                    if (territory) disabled.controller(input.baseline)
                                    else input.frozen.controller(input.baseline)
                                object : SimulationController {
                                    override fun controls(decision: String) =
                                        delegate.controls(decision)

                                    override fun choose(decision: SimulationDecision): String? =
                                        delegate.choose(decision).also { command ->
                                            if (command != null)
                                                trace.add(decision.number to command)
                                        }
                                }
                            },
                    )

            val legacy = play(false, oldTrace)
            val actual = play(true, newTrace)

            assertThat(actual.scores).isEqualTo(legacy.scores)
            assertThat(actual.steps).isEqualTo(legacy.steps)
            assertThat(newTrace).isEqualTo(oldTrace)
            assertThat(disabled.controller(input.baseline).controls("buy-tokens")).isFalse()
            assertThat(disabled.controller(input.baseline).controls("expand")).isFalse()
            assertThat(disabled.controller(input.baseline).controls("prepare-war")).isFalse()
        }
    }

    @Test
    fun sealedFourthSeatProvenanceRejectsNumberDerivedSeeds() {
        TerritoryCollectionFixture(games = 4).use { fixture ->
            val input = fixture.input
            val original = fixture.syntheticGame(input, 3)
            TerritoryTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(original), 3)
            val token = original.tokenDecisions.single()
            val wrongSeed = input.seeds[3] xor (token.number * 65537 + 3 * 8191)
            val changed =
                original.copy(
                    tokenDecisions =
                        listOf(
                            token.copy(
                                botSeed = wrongSeed,
                                actorSeed =
                                    TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, wrongSeed),
                            )
                        )
                )

            assertFailsWith<IllegalArgumentException> {
                TerritoryTrajectoryValidation(input, "fixture")
                    .validate(PurchaseJson.mapper.valueToTree(changed), 3)
            }
        }
    }

    @Test
    fun emptyPhaseRetainsItsOriginAndExactResumeDoesNotRewriteIt() {
        TerritoryCollectionFixture().use { fixture ->
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
        TerritoryCollectionFixture().use { fixture ->
            fixture.writeBundle("territory.json", token = false)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun bundleRequiresItsExactFrozenContinuation() {
        TerritoryCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("territory.json")
            val raw = PurchaseJson.read(path) as ObjectNode
            raw.put("frozenCardPurchaseBundleSha256", "0".repeat(64))
            fixture.artifacts.write("territory.json", raw)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun cancellationCannotWriteAPartiallyObservedGame() {
        TerritoryCollectionFixture().use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            assertFailsWith<CancellationException> {
                store.save(TerritoryCollector(input, "fixture", Long.MIN_VALUE).collect(0))
            }

            assertThat(store.completedIndices()).isEmpty()
            assertThat(Files.exists(fixture.output.resolve("game-0.json.gz"))).isFalse()
        }
    }

    @Test
    fun changedComponentBytesCannotHideBehindTheSameBundle() {
        TerritoryCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("expansion-policy.json")
            val model = PurchaseJson.read(path) as ObjectNode
            model.put("valueBias", .1)
            fixture.artifacts.write("expansion-policy.json", model)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun phaseArraysCannotReuseGlobalNumbers() {
        TerritoryCollectionFixture().use { fixture ->
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
        TerritoryCollectionFixture(games = 2).use { fixture ->
            val store = fixture.store()
            store.save(fixture.syntheticGame())

            assertFailsWith<IllegalArgumentException> { store.finish() }
            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun snapshotsBindEveryActorAndTheSharedFrozenContinuation() {
        TerritoryCollectionFixture(learnedRivals = true).use { fixture ->
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
            assertThat(fixture.evaluation(input.seeds[0]).manifest()["territoryActorVersion"])
                .isEqualTo(TerritoryActorBundle.VERSION)
        }
    }
}
