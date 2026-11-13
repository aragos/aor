package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import kotlin.test.Test

class ExpansionPolicyScopeTest {
    @Test
    fun currentHardExpansionSupportsProjection() {
        assertThat(ExpansionPolicyScope.supports(observation(), BotDifficulty.HARD)).isTrue()
    }

    @Test
    fun otherDecisionsDoNotRequestExpansionFacts() {
        val decisions = listOf("play-cards", "purchase-advances", "buy-tokens", "stabilize")

        val supported = decisions.map {
            ExpansionPolicyScope.supports(observation().copy(decision = it), BotDifficulty.HARD)
        }

        assertThat(supported).doesNotContain(true)
    }

    @Test
    fun everyOtherSetupRetainsItsExistingController() {
        val setup = ExpansionPolicyScope.configuration
        val alternatives =
            listOf(
                setup.copy(playerCount = 3),
                setup.copy(map = MapRule.EUROPEAN),
                setup.copy(initialCard = InitialCardRule.ANY),
                setup.copy(deckModification = false),
                setup.copy(immediateMasterArtDiscard = false),
                setup.copy(drawThreeKeepTwo = true),
                null,
            )

        val supported = alternatives.map {
            ExpansionPolicyScope.supports(
                observation().copy(configuration = it),
                BotDifficulty.HARD,
            )
        }

        assertThat(supported).doesNotContain(true)
    }

    @Test
    fun oldRulesAndCatalogsRemainOutsideTheExpansionScope() {
        val alternatives =
            listOf(
                observation().copy(rulesVersion = PurchasePolicyScope.RULES_VERSION),
                observation().copy(dataVersion = "ah1996-catalog-15"),
            )

        assertThat(alternatives.map { ExpansionPolicyScope.supports(it, BotDifficulty.HARD) })
            .doesNotContain(true)
    }

    @Test
    fun easierDifficultiesRemainOutsideTheExpansionScope() {
        val supported = listOf(BotDifficulty.EASY, BotDifficulty.NORMAL).map {
            ExpansionPolicyScope.supports(observation(), it)
        }

        assertThat(supported).doesNotContain(true)
    }

    private fun observation() =
        BotObservation(
            playerId = "p1",
            decision = "expand",
            context = emptyMap(),
            availableCash = 40,
            availableTokens = 12,
            candidates = listOf(BotCandidate("pass", emptyMap())),
            rulesVersion = ExpansionPolicyScope.RULES_VERSION,
            dataVersion = ExpansionPolicyScope.DATA_VERSION,
            configuration = ExpansionPolicyScope.configuration,
        )
}
