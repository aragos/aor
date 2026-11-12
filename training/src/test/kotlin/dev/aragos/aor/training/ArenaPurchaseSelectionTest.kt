package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaPurchaseSelectionTest {
    @Test
    fun explicitSelectionBindsIdentityAndMetadataWithoutChangingActorBytes() {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.frozen
            selection(fixture, "MAXIMUM_PROBABILITY")

            val changed = fixture.frozen

            assertThat(changed.identity())
                .isEqualTo(original.identity() + ":candidate-selection:MAXIMUM_PROBABILITY")
            val before = PurchaseJson.mapper.valueToTree<JsonNode>(original.metadata())
            val after = PurchaseJson.mapper.valueToTree<JsonNode>(changed.metadata())
            assertThat(after["frozenPurchase"]["candidateSelection"].asText())
                .isEqualTo("MAXIMUM_PROBABILITY")
            for (role in listOf("baseline", "policy")) {
                assertThat(after["frozenPurchase"][role]).isEqualTo(before["frozenPurchase"][role])
            }
        }
    }

    @Test
    fun onlyControlledCandidateUsesMaximumProbabilityEvenWhenSampleDiffers() {
        ArenaPurchaseFixture().use { fixture ->
            selection(fixture, "MAXIMUM_PROBABILITY")
            val policy = PurchasePolicyInput.read(fixture.directory.resolve("policy.json"))
            for (controlledSeat in 0..3) {
                for (side in ArenaSide.entries) {
                    val controllers = fixture.frozen.controllers(4, controlledSeat, side)
                    for (seat in 0..3) {
                        val root = purchaseSelectionDecision(seat)
                        val observation = purchaseSelectionObservation(root)
                        val evaluation = PurchasePolicy.evaluate(observation, policy)
                        val maximum = evaluation.probabilities.max()
                        val mode =
                            observation.actions[evaluation.probabilities.indexOf(maximum)].command
                        val seed =
                            (0..4095).first {
                                PurchasePolicy.select(
                                        observation,
                                        evaluation,
                                        PurchasePolicy.uniform(PurchasePolicy.actorSeed(it)),
                                    )
                                    .command != mode
                            }
                        val decision = root.copy(seed = seed)
                        val sample =
                            PurchaseActorController(BotModels.default, policy).choose(decision)

                        val actual =
                            requireNotNull(controllers[seat]) { "Every seat has a controller." }
                                .choose(decision)

                        assertThat(sample).isNotEqualTo(mode)
                        assertThat(actual)
                            .isEqualTo(
                                if (side == ArenaSide.CANDIDATE && seat == controlledSeat) mode
                                else sample
                            )
                    }
                }
            }
        }
    }

    @Test
    fun explicitSampleRetainsSeededChoicesAndIsRecordedHonestly() {
        ArenaPurchaseFixture().use { fixture ->
            val original = fixture.frozen
            selection(fixture, "SAMPLE")
            val selected = fixture.frozen
            val decision = purchaseSelectionDecision()

            val actual = selected.controllers(4, 0, ArenaSide.CANDIDATE)[0]?.choose(decision)

            assertThat(actual)
                .isEqualTo(original.controllers(4, 0, ArenaSide.CANDIDATE)[0]?.choose(decision))
            assertThat(selected.identity())
                .isEqualTo(original.identity() + ":candidate-selection:SAMPLE")
        }
    }

    @Test
    fun explicitSelectionRequiresAnExplicitArenaSide() {
        ArenaPurchaseFixture().use { fixture ->
            selection(fixture, "MAXIMUM_PROBABILITY")

            val failure =
                assertFailsWith<IllegalArgumentException> { fixture.frozen.controllers(4, 0, null) }

            assertThat(failure).hasMessageThat().contains("explicit candidate or incumbent")
        }
    }

    @Test
    fun ordinaryCandidateCannotDeclareActorSelection() {
        ArenaPurchaseFixture().use { fixture ->
            selection(fixture, "MAXIMUM_PROBABILITY")
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["frozenPurchase"] as ObjectNode)
                .putArray("ordinaryEvaluatedSides")
                .add("CANDIDATE")
            fixture.artifacts.write("arena.json", source)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("ordinary candidate cannot also declare")
        }
    }

    @Test
    fun unknownSelectionIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            selection(fixture, "GREEDY")

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure).hasMessageThat().contains("SAMPLE or MAXIMUM_PROBABILITY")
        }
    }

    @Test
    fun explicitNullSelectionIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["frozenPurchase"] as ObjectNode).putNull("candidateSelection")
            fixture.artifacts.write("arena.json", source)

            val failure = assertFailsWith<IllegalArgumentException> { fixture.specification }

            assertThat(failure)
                .hasMessageThat()
                .contains("candidateSelection must be nonempty text")
        }
    }

    private fun selection(fixture: ArenaPurchaseFixture, value: String) {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        (source["frozenPurchase"] as ObjectNode).put("candidateSelection", value)
        fixture.artifacts.write("arena.json", source)
    }
}
