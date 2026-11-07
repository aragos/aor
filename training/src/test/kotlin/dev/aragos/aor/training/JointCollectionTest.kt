package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointCollectionTest {
    @Test
    fun bothTypedPhasesPreserveTheExistingFullGameBehavior() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val reference =
                CardCollector(fixture.legacyInput(), "legacy", Long.MAX_VALUE).collect(0)

            val actual = JointCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal).isEqualTo(reference.terminal)
            assertThat(actual.cardDecisions).isEqualTo(reference.decisions)
            assertThat(
                    actual.purchaseDecisions.map {
                        it.number to it.observation.actions[it.selectedIndex].command
                    }
                )
                .isEqualTo(reference.purchaseAudit.map { it.number to it.command })
            assertThat(actual.cardDecisions).isNotEmpty()
            assertThat(actual.purchaseDecisions).isNotEmpty()
            JointTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

    @Test
    fun learnedRivalsDoNotAddTheirPrivateRowsToTheControlledTrajectory() {
        JointCollectionFixture(learnedRivals = true).use { fixture ->
            val input = fixture.input
            val reference =
                CardCollector(fixture.legacyInput(), "legacy", Long.MAX_VALUE).collect(0)

            val actual = JointCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal).isEqualTo(reference.terminal)
            assertThat(actual.cardDecisions).isEqualTo(reference.decisions)
            assertThat(actual.purchaseDecisions.map { it.number })
                .isEqualTo(reference.purchaseAudit.map { it.number })
            val card =
                requireNotNull(input.bundle.card) { "The joint actor requires a card model." }
            actual.cardDecisions.forEach { row ->
                assertThat(CardPolicy.choose(row.observation, card.model, row.actorSeed).index)
                    .isEqualTo(row.selectedIndex)
            }
            actual.purchaseDecisions.forEach { row ->
                assertThat(
                        PurchasePolicy.choose(
                                row.observation,
                                input.bundle.purchase.model,
                                row.actorSeed,
                            )
                            .index
                    )
                    .isEqualTo(row.selectedIndex)
            }
        }
    }

    @Test
    fun emptyPerGamePhaseRemainsACompleteSharedOrigin() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.syntheticGame(input).copy(cardDecisions = emptyList())
            val store = fixture.store(input)

            store.save(game)
            store.finish()

            val saved =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(fixture.output.resolve("game-0.json.gz"))
                ) {
                    "The complete origin is retained."
                }
            assertThat(saved.required("cardDecisions").size()).isEqualTo(0)
            assertThat(saved.required("purchaseDecisions").size()).isEqualTo(1)
            assertThat(store.completedIndices()).containsExactly(0)
        }
    }

    @Test
    fun phasesCannotReuseTheSameGlobalDecisionNumber() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.syntheticGame(input)
            val changed =
                game.copy(
                    purchaseDecisions = listOf(game.purchaseDecisions.single().copy(number = 0))
                )

            assertFailsWith<IllegalArgumentException> { fixture.store(input).save(changed) }
        }
    }

    @Test
    fun changedBehaviorCannotEnterEitherTypedView() {
        JointCollectionFixture().use { fixture ->
            val input = fixture.input
            val game = fixture.syntheticGame(input)
            val changed =
                game.copy(
                    purchaseDecisions =
                        listOf(game.purchaseDecisions.single().copy(behaviorValue = .9))
                )

            assertFailsWith<IllegalArgumentException> { fixture.store(input).save(changed) }
        }
    }

    @Test
    fun controlledBundleCannotSilentlyBecomeBaselineCardImitation() {
        JointCollectionFixture().use { fixture ->
            fixture.writeBundle("bundle.json", cardName = null)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun componentSubstitutionCannotHideBehindUnchangedBundleBytes() {
        JointCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("purchase-policy.json")
            val changed = PurchaseJson.read(path) as ObjectNode
            changed.put("valueBias", .2)
            PurchaseJson.mapper.writeValue(path.toFile(), changed)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun bundleMustMatchItsExactPriorAndConfiguration() {
        JointCollectionFixture().use { fixture ->
            val path = fixture.artifacts.directory.resolve("bundle.json")
            val changed = PurchaseJson.read(path) as ObjectNode
            changed.put("baselineSha256", "0".repeat(64))
            PurchaseJson.mapper.writeValue(path.toFile(), changed)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun commandLineRemainsExplicitAndBounded() {
        JointCollectionFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                JointCollectionOptions.parse(fixture.arguments() + listOf("--workers", "1"))
            }
            assertFailsWith<IllegalArgumentException> {
                JointCollectionOptions.parse(
                    fixture.arguments().dropLast(2) + listOf("--hours", "NaN")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                JointCollectionOptions.parse(fixture.arguments() + listOf("--hidden-world", "true"))
            }
        }
    }
}
