package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RuleOptionsTest {
    @Test
    fun resolvedOptionsRoundTripThroughCanonicalCommands() {
        val options =
            RuleOptions(
                deckModification = true,
                initialCard = InitialCardRule.NO_EVENTS,
                immediateMasterArtDiscard = true,
                map = MapRule.EUROPEAN,
            )
        val reconstructed =
            options.configurationCommands().fold(RuleOptions()) { rules, command ->
                command.resolve(rules)
            }

        assertThat(reconstructed).isEqualTo(options)
        assertThat(options.configurationCommands().map(RuleOptionCommand::key))
            .containsExactly(
                "deck-modification",
                "initial-card",
                "immediate-master-art-discard",
                "map",
            )
    }

    @Test
    fun deckModificationExchangesOnePhysicalTimberAndWarBetweenEpochs() {
        val rules = RuleOptions(deckModification = true)

        assertThat(rules.cardsForEpoch(Epoch.ONE)).contains("timber-2-1")
        assertThat(rules.cardsForEpoch(Epoch.ONE)).doesNotContain("war")
        assertThat(rules.cardsForEpoch(Epoch.TWO)).contains("war")
        assertThat(rules.cardsForEpoch(Epoch.TWO)).doesNotContain("timber-2-1")
        assertThat(Epoch.entries.flatMap(rules::cardsForEpoch))
            .containsExactlyElementsIn(CardData.all.map(CardDefinition::id))
    }

    @Test
    fun retiredAndUnspecifiedOptionsAreRejected() {
        for ((key, value) in
            listOf(
                "setup" to "improved",
                "preset" to "second-edition",
                "st-gall-support" to "enabled",
                "rebuilt-marker-bonus" to "include",
                "map" to "unknown",
            )) {
            val failure = assertFailsWith<RuleViolation> { RuleOptionCommand(key, value) }
            assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
        }
    }

    @Test
    fun onlyCreatorMayConfigureVariants() {
        val state = Scenario.create().state
        val failure =
            assertFailsWith<RuleViolation> {
                RuleOptionCommand("initial-card", "no-events").applyTo(state, PlayerId("p2"))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun variantsFreezeWhenPlayStarts() {
        val state = Scenario.create().play("p1: start").state
        val failure =
            assertFailsWith<RuleViolation> {
                RuleOptionCommand("initial-card", "no-events").applyTo(state, PlayerId("p1"))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }
}
