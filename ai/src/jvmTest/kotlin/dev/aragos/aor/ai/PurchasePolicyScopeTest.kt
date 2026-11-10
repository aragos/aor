package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test

class PurchasePolicyScopeTest {
    @Test
    fun bothQualifiedPurchaseDecisionsSupportHardInputProjection() {
        val decisions = listOf("purchase-shipping", "purchase-advances")

        val supported = decisions.map {
            PurchasePolicyScope.supports(observation(it), BotDifficulty.HARD)
        }

        assertThat(supported).containsExactly(true, true)
    }

    @Test
    fun easierDifficultiesRetainTheirOrdinaryPolicy() {
        val supported =
            listOf(BotDifficulty.EASY, BotDifficulty.NORMAL).map {
                PurchasePolicyScope.supports(observation(), it)
            }

        assertThat(supported).containsExactly(false, false)
    }

    @Test
    fun otherDecisionsNeverRequestPurchaseFacts() {
        val decisions = listOf("buy-tokens", "play-cards", "expand", "stabilize")

        val supported = decisions.map {
            PurchasePolicyScope.supports(observation(it), BotDifficulty.HARD)
        }

        assertThat(supported).containsExactly(false, false, false, false)
    }

    @Test
    fun everyUnqualifiedSetupFieldPreventsActivation() {
        val expected = PurchasePolicyScope.configuration
        val alternatives =
            listOf(
                expected.copy(playerCount = 3),
                expected.copy(playerCount = 5),
                expected.copy(map = MapRule.EUROPEAN),
                expected.copy(initialCard = InitialCardRule.ANY),
                expected.copy(deckModification = false),
                expected.copy(immediateMasterArtDiscard = false),
                expected.copy(drawThreeKeepTwo = true),
                null,
            )

        val supported = alternatives.map {
            PurchasePolicyScope.supports(
                observation().copy(configuration = it),
                BotDifficulty.HARD,
            )
        }

        assertThat(supported).doesNotContain(true)
    }

    @Test
    fun theNewRulesVersionRemainsOutsideTheQualifiedActorScope() {
        val supported =
            PurchasePolicyScope.supports(
                observation().copy(rulesVersion = RULES_VERSION),
                BotDifficulty.HARD,
            )

        assertThat(supported).isFalse()
    }

    @Test
    fun existingCatalogsRemainOutsideTheNewActorScope() {
        val catalogs = listOf("ah1996-catalog-13", "ah1996-catalog-14", "ah1996-catalog-15")

        val supported = catalogs.map {
            PurchasePolicyScope.supports(
                observation().copy(dataVersion = it),
                BotDifficulty.HARD,
            )
        }

        assertThat(supported).containsExactly(false, false, false)
    }

    private fun observation(decision: String = "purchase-advances"): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = decision,
            context = emptyMap(),
            availableCash = 40,
            availableTokens = 0,
            candidates = listOf(BotCandidate("pass", emptyMap())),
            rulesVersion = PurchasePolicyScope.RULES_VERSION,
            dataVersion = PurchasePolicyScope.DATA_VERSION,
            configuration = PurchasePolicyScope.configuration,
        )
}
