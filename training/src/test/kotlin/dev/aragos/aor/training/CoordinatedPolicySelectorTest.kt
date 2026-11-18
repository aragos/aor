package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CoordinatedPolicySelector
import dev.aragos.aor.ai.IncumbentResidualPolicy
import dev.aragos.aor.ai.IncumbentResidualSelector
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.KnownOwnChaosSupport
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import dev.aragos.aor.ai.NearOptimalTokenPolicy
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenAnchorDistribution
import dev.aragos.aor.ai.TokenAnchoredPolicy
import dev.aragos.aor.ai.TokenAnchoredResidual
import dev.aragos.aor.ai.TokenNeighborAdmission
import dev.aragos.aor.ai.TokenNeighborQuote
import dev.aragos.aor.ai.TokenObservationEncoder
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed full dictionaries and own/public positions; no game or scientific model is read. */
class CoordinatedPolicySelectorTest {
    @Test
    fun anchoredPolicyAlignsCompleteLexicalOffersAndUsesExactlyOneActorUniform() {
        CoordinatedEsFixture().use { fixture ->
            val sampler = NearOptimalTokenSampler(.35, 3.0, 4)
            val model = fixture.original.models.token
            val residual =
                TokenAnchoredResidual(
                    model.copy(
                        actorWeights = List(64) { 0.0 },
                        valueWeights = List(64) { 0.0 },
                        valueBias = 0.0,
                    ),
                    TokenAnchorDistribution(.2, 1.0),
                )
            val selector = selector(fixture, tokenSampler = sampler, anchored = residual)
            val decision = fixture.decision(CoordinatedEsHead.TOKEN)
            val encoded =
                TokenObservationEncoder.from(
                    decision.view,
                    PlayerId(decision.observation.playerId),
                    decision.observation,
                    "buy-tokens 10",
                    TerritoryEncoder.TOKEN_2,
                )
            assertThat(encoded.actions.map { it.command })
                .isNotEqualTo(decision.observation.candidates.map { it.command })
            val expected =
                TokenAnchoredPolicy.evaluate(
                    encoded,
                    model,
                    model,
                    IncumbentResidualSelector(1.0),
                    sampler,
                    residual,
                )
            var referenceCalls = 0
            for (seed in 0..31) {
                val actual =
                    selector.choose(
                        decision.view,
                        decision.observation,
                        seed,
                        referenceCommand = {
                            referenceCalls++
                            "buy-tokens 10"
                        },
                        tokenQuote =
                            TokenNeighborQuote {
                                error(
                                    "An offered-only anchored policy cannot request another authority quote."
                                )
                            },
                    )
                assertThat(actual)
                    .isEqualTo(TokenAnchoredPolicy.choose(encoded, expected, seed).command)
            }
            assertThat(referenceCalls).isEqualTo(32)
            assertThat(expected.probabilities.all { it > 0.0 }).isTrue()
            assertThat(encoded.actions[encoded.baselineIndex].command).isEqualTo("buy-tokens 10")
        }
    }

    @Test
    fun mixtureAlignsLexicalEncodedProbabilitiesWithTheirExactNumericOfferedCommands() {
        CoordinatedEsFixture().use { fixture ->
            val sampler = NearOptimalTokenSampler(.35, 3.0, 4)
            val mixture = LocalTokenNeighborMixture(.5, 1, TokenNeighborAdmission.OFFERED_ONLY)
            val selector = selector(fixture, tokenSampler = sampler, mixture = mixture)
            val decision = fixture.decision(CoordinatedEsHead.TOKEN)
            val commands = decision.observation.candidates.map { it.command }
            assertThat(commands)
                .containsAtLeast(
                    "buy-tokens -2",
                    "buy-tokens -1",
                    "buy-tokens 0",
                    "buy-tokens 1",
                    "buy-tokens 2",
                    "buy-tokens 10",
                    "buy-tokens 11",
                )
            val encoded =
                TokenObservationEncoder.from(
                    decision.view,
                    PlayerId(decision.observation.playerId),
                    decision.observation,
                    "buy-tokens 10",
                    TerritoryEncoder.TOKEN_2,
                )
            assertThat(encoded.actions.map { it.command }).isNotEqualTo(commands)
            val deterministic =
                IncumbentResidualPolicy.choose(
                    encoded,
                    fixture.original.models.token,
                    fixture.original.models.token,
                    IncumbentResidualSelector(1.0),
                )
            val baseline = NearOptimalTokenPolicy.select(encoded, deterministic, sampler, 0.0)
            val probabilities = commands.map { command ->
                baseline.probabilities[encoded.actions.indexOfFirst { it.command == command }]
            }
            val expected =
                LocalTokenNeighborPolicy.distribution(decision.observation, probabilities, mixture)
            val expectedCommands =
                expected.amounts.mapIndexedNotNull { index, amount ->
                    "buy-tokens $amount".takeIf { expected.probabilities[index] > 0.0 }
                }
            for (seed in 0..31) {
                assertThat(
                        selector.choose(decision.view, decision.observation, seed) {
                            "buy-tokens 10"
                        }
                    )
                    .isIn(expectedCommands)
            }
        }
    }

    @Test
    fun zeroDeparturePreservesTheExactPreviousSamplerWithoutQuoteCapability() {
        CoordinatedEsFixture().use { fixture ->
            val sampler = NearOptimalTokenSampler(.35, 3.0, 4)
            val original = selector(fixture, tokenSampler = sampler)
            val disabled =
                selector(
                    fixture,
                    tokenSampler = sampler,
                    mixture =
                        LocalTokenNeighborMixture(
                            0.0,
                            2,
                            TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS,
                        ),
                )
            for (seed in listOf(0, 1, -1, 17, Int.MIN_VALUE, Int.MAX_VALUE)) {
                val decision = fixture.decision(CoordinatedEsHead.TOKEN).copy(seed = seed)
                val reference = decision.observation.candidates.last().command
                assertThat(disabled.choose(decision.view, decision.observation, seed) { reference })
                    .isEqualTo(
                        original.choose(decision.view, decision.observation, seed) { reference }
                    )
            }
        }
    }

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
        tokenSampler: NearOptimalTokenSampler? = null,
        mixture: LocalTokenNeighborMixture? = null,
        anchored: TokenAnchoredResidual? = null,
    ): CoordinatedPolicySelector =
        CoordinatedPolicySelector(
            fixture.input.arena.candidate,
            fixture.input.purchaseBaseline.model,
            fixture.original.models.common(),
            fixture.original.models.common(),
            IncumbentResidualSelector(1.0),
            IncumbentResidualSelector(1.0),
            support,
            tokenSampler = tokenSampler,
            tokenNeighborMixture = mixture,
            tokenAnchoredResidual = anchored,
        )
}
