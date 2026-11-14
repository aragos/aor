package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CoordinatedPolicySelector
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.KnownOwnChaosSupport
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed full dictionaries and own/public positions; no game or scientific model is read. */
class CoordinatedPolicySelectorTest {
    @Test
    fun nullSupportKeepsTheOriginalCardAndPurchaseSeedsAndCommands() {
        CoordinatedEsFixture().use { fixture ->
            val models = fixture.original.models
            val old =
                CardActorController(
                    fixture.input.arena.candidate,
                    models.card,
                    models.purchase,
                    purchaseBaseline = fixture.input.purchaseBaseline.model,
                )
            val selector = selector(fixture)
            for (head in listOf(CoordinatedEsHead.CARD, CoordinatedEsHead.PURCHASE)) {
                for (seed in listOf(0, 1, -1, 17, Int.MIN_VALUE, Int.MAX_VALUE)) {
                    val decision = fixture.decision(head).copy(seed = seed)
                    val actual =
                        selector.choose(decision.view, decision.observation, seed) {
                            error("C/P must not call the E/T reference.")
                        }
                    assertThat(actual).isEqualTo(old.choose(decision))
                }
            }
        }
    }

    @Test
    fun supportedChoiceUsesCurrentLearnedProbabilitiesWithTheOriginalPhaseUniform() {
        CoordinatedEsFixture().use { fixture ->
            val models = fixture.original.models
            val selector = selector(fixture, JointSupportProfile.KNOWN_OWN_CHAOS_1)
            for (head in listOf(CoordinatedEsHead.CARD, CoordinatedEsHead.PURCHASE)) {
                val decision = fixture.decision(head)
                val card = head == CoordinatedEsHead.CARD
                val reference =
                    BotPolicy.choose(
                        decision.observation,
                        BotDifficulty.HARD,
                        decision.seed,
                        if (card) fixture.input.arena.candidate
                        else fixture.input.purchaseBaseline.model,
                    )
                val player = PlayerId(decision.observation.playerId)
                val expected =
                    if (card) {
                        val observation =
                            CardObservationEncoder.from(
                                decision.view,
                                player,
                                decision.observation,
                                reference,
                            )
                        KnownOwnChaosSupport.card(
                                observation,
                                CardPolicy.evaluate(observation, models.card),
                                CardPolicy.uniform(CardPolicy.actorSeed(decision.seed)),
                            )
                            .command
                    } else {
                        val observation =
                            PurchaseObservationEncoder.from(
                                decision.view,
                                player,
                                decision.observation,
                                reference,
                            )
                        KnownOwnChaosSupport.purchase(
                                observation,
                                PurchasePolicy.evaluate(observation, models.purchase),
                                PurchasePolicy.uniform(PurchasePolicy.actorSeed(decision.seed)),
                            )
                            .command
                    }
                val actual =
                    selector.choose(decision.view, decision.observation, decision.seed) {
                        error("Supported C/P must not call the E/T reference.")
                    }
                assertThat(actual).isEqualTo(expected)
            }
        }
    }

    @Test
    fun zeroTerritoryResidualCallsTheCompleteReferenceExactlyOncePerChoice() {
        CoordinatedEsFixture().use { fixture ->
            val selector = selector(fixture, JointSupportProfile.KNOWN_OWN_CHAOS_1)
            for (head in listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)) {
                val decision =
                    fixture.decision(head, if (head == CoordinatedEsHead.TOKEN) 2 else 0, 71)
                val reference = decision.observation.candidates.last().command
                var calls = 0
                val actual =
                    selector.choose(decision.view, decision.observation, decision.seed) {
                        calls++
                        reference
                    }
                assertThat(calls).isEqualTo(1)
                assertThat(actual).isEqualTo(reference)
            }
        }
    }

    @Test
    fun unofferedReferenceCannotBecomeAChosenCommand() {
        CoordinatedEsFixture().use { fixture ->
            val decision = fixture.decision(CoordinatedEsHead.TOKEN)
            assertFailsWith<IllegalArgumentException> {
                selector(fixture).choose(decision.view, decision.observation, decision.seed) {
                    "unoffered-reference"
                }
            }
        }
    }

    @Test
    fun unsupportedPhaseRemainsCallerOwned() {
        CoordinatedEsFixture().use { fixture ->
            assertThat(selector(fixture).controls("select-capital")).isFalse()
        }
    }

    private fun selector(
        fixture: CoordinatedEsFixture,
        support: JointSupportProfile? = null,
    ): CoordinatedPolicySelector =
        CoordinatedPolicySelector(
            fixture.input.arena.candidate,
            fixture.input.purchaseBaseline.model,
            fixture.original.models.common(),
            fixture.original.models.common(),
            IncumbentResidualSelector(1.0),
            IncumbentResidualSelector(1.0),
            support,
        )
}
