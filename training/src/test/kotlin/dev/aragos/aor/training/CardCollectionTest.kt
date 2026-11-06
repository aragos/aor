package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.CardPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardCollectionTest {
    @Test
    fun baselineCardsPreserveTheRepeatedPurchaseActorGameExactly() {
        CardCollectionFixture().use { fixture ->
            val input = fixture.input
            val purchase =
                fixture.purchase.input.copy(opponentPurchasePolicy = input.frozenPurchasePolicy)
            val reference = PurchaseCollector(purchase, "purchase", Long.MAX_VALUE).collect(0)

            val actual = CardCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal).isEqualTo(reference.terminal)
            assertThat(actual.decisions).isNotEmpty()
            assertThat(
                    actual.decisions.all {
                        it.decision == "play-cards" &&
                            it.selectedIndex == it.observation.baselineIndex &&
                            it.behaviorLogProbability == null &&
                            it.behaviorValue == null
                    }
                )
                .isTrue()
            CardTrajectoryValidation(input, "fixture")
                .validate(PurchaseJson.mapper.valueToTree(actual), 0)
        }
    }

    @Test
    fun completeCardActorGameUsesSharedSamplingAndCompressedCompletion() {
        CardCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            val actual = CardCollector(input, "fixture", Long.MAX_VALUE).collect(0)
            store.save(actual)
            store.finish()

            assertThat(actual.decisions).isNotEmpty()
            actual.decisions.forEach { row ->
                val choice =
                    CardPolicy.choose(
                        row.observation,
                        requireNotNull(input.policy) {
                            "The fixture requires its card actor."
                        },
                        row.actorSeed,
                    )
                assertThat(row.selectedIndex).isEqualTo(choice.index)
                assertThat(row.behaviorLogProbability).isEqualTo(choice.logProbability)
                assertThat(row.behaviorValue).isEqualTo(choice.value)
            }
            assertThat(store.completedIndices()).containsExactly(0)
            assertThat(
                    PurchaseJson.read(fixture.output.resolve("finished.json"))["gameSha256"][0]
                        .asText()
                )
                .isEqualTo(PurchaseJson.hash(fixture.output.resolve("game-0.json.gz")))
        }
    }

    @Test
    fun learnedRivalsComposeFrozenPurchasesButOnlyControlledCardsAreRecorded() {
        CardCollectionFixture(onPolicy = true).use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("opponentCardPolicy", "card-policy.json")
            PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)
            val input = fixture.input
            val records =
                List(input.configuration.playerCount) { mutableListOf<CardDecisionRecord>() }
            val competitors =
                input.opponentIndices(0).mapIndexed { seat, rival ->
                    Competitor(
                        if (seat == 0) input.baseline.model else input.opponents[rival].model,
                        BotDifficulty.HARD,
                    )
                }
            val reference =
                Simulation()
                    .play(
                        input.configuration,
                        input.seeds[0],
                        competitors,
                        verifyReplay = true,
                        controllers =
                            competitors.indices.associateWith { seat ->
                                CardActorController(
                                    competitors[seat].model,
                                    input.policy,
                                    input.frozenPurchasePolicy.model,
                                ) {
                                    records[seat].add(it)
                                }
                            },
                    )

            val actual = CardCollector(input, "fixture", Long.MAX_VALUE).collect(0)

            assertThat(actual.terminal.scores)
                .isEqualTo(reference.scores.map(PilotReplayScore::from))
            assertThat(actual.terminal.gameDecisions).isEqualTo(reference.steps)
            assertThat(actual.decisions).isEqualTo(records[0])
            assertThat(records.drop(1).all { it.isNotEmpty() }).isTrue()
        }
    }

    @Test
    fun cardSamplingUsesADistinctStreamAndLeavesSealedAndNestedDecisionsUntouched() {
        CardCollectionFixture().use { fixture ->
            val input = fixture.input
            val controller =
                CardActorController(input.baseline.model, null, input.frozenPurchasePolicy.model)

            assertThat(controller.controls("play-cards")).isTrue()
            assertThat(controller.controls("purchase-shipping")).isTrue()
            assertThat(controller.controls("purchase-advances")).isTrue()
            for (decision in listOf("buy-tokens", "discard-card", "war-target", "stabilization")) {
                assertThat(controller.controls(decision)).isFalse()
            }
            assertThat(CardPolicy.actorSeed(71))
                .isNotEqualTo(dev.aragos.aor.ai.PurchasePolicy.actorSeed(71))
        }
    }

    @Test
    fun inputRequiresAnExplicitFrozenPurchaseContinuation() {
        CardCollectionFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.remove("frozenPurchasePolicy")
            PurchaseJson.mapper.writeValue(fixture.source.toFile(), source)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun commandLineRejectsUnboundedAndRepeatedOptions() {
        CardCollectionFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                CardCollectionOptions.parse(fixture.arguments() + listOf("--workers", "1"))
            }
            assertFailsWith<IllegalArgumentException> {
                CardCollectionOptions.parse(
                    fixture.arguments().dropLast(2) + listOf("--hours", "NaN")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                CardCollectionOptions.parse(fixture.arguments() + listOf("--hidden-world", "true"))
            }
        }
    }
}
