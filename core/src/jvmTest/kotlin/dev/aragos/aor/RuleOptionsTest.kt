package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RuleOptionsTest {
    @Test
    fun baselineAndAlternateCommerceDefinitionsCoexist() {
        val alternate = RuleOptions(commerce = CommerceRule.SECOND_EDITION)

        assertThat(alternate.definition(AdvanceId.IMPROVED_AGRICULTURE).cost).isEqualTo(40)
        assertThat(alternate.definition(AdvanceId.IMPROVED_AGRICULTURE).prerequisites)
            .containsExactly(AdvanceId.CARAVAN)
        assertThat(alternate.definition(AdvanceId.WIND_WATERMILL).cost).isEqualTo(50)
        assertThat(alternate.definition(AdvanceId.WIND_WATERMILL).prerequisites)
            .containsExactly(AdvanceId.IMPROVED_AGRICULTURE)
        assertThat(alternate.definition(AdvanceId.INTEREST_PROFIT).prerequisites)
            .containsExactly(AdvanceId.WIND_WATERMILL)
        assertThat(alternate.definition(AdvanceId.MIDDLE_CLASS).prerequisites)
            .containsExactly(AdvanceId.WIND_WATERMILL)
        assertThat(AdvanceData.definition(AdvanceId.WIND_WATERMILL).cost).isEqualTo(40)
        assertThat(AdvanceData.definition(AdvanceId.IMPROVED_AGRICULTURE).cost).isEqualTo(50)
    }

    @Test
    fun alternateLettersResolveToTheSameStableIdentity() {
        val alternate = RuleOptions(commerce = CommerceRule.SECOND_EDITION)

        assertThat(alternate.advance('J')).isEqualTo(AdvanceId.IMPROVED_AGRICULTURE)
        assertThat(alternate.advance('k')).isEqualTo(AdvanceId.WIND_WATERMILL)
        assertThat(alternate.letter(AdvanceId.IMPROVED_AGRICULTURE)).isEqualTo('J')
        assertThat(alternate.definition(AdvanceId.WIND_WATERMILL).letter).isEqualTo('K')
        assertThat(alternate.definitions.map(AdvanceDefinition::letter).distinct()).hasSize(26)
    }

    @Test
    fun secondEditionCreditTablePreservesBaseline() {
        val alternate = RuleOptions(credits = CreditRule.SECOND_EDITION)

        assertThat(alternate.definition(AdvanceId.WRITTEN_RECORD).credit).isEqualTo(30)
        assertThat(alternate.definition(AdvanceId.PRINTED_WORD).credit).isEqualTo(20)
        assertThat(alternate.definition(AdvanceId.MASTER_ART).credit).isEqualTo(10)
        assertThat(alternate.definition(AdvanceId.RENAISSANCE).credit).isEqualTo(0)
        assertThat(alternate.definition(AdvanceId.PROSELYTISM).credit).isEqualTo(30)
        assertThat(alternate.definition(AdvanceId.MIDDLE_CLASS).credit).isEqualTo(60)
        assertThat(AdvanceData.definition(AdvanceId.WRITTEN_RECORD).credit).isEqualTo(10)
    }

    @Test
    fun resolvedOptionCommandsReconstructTheEntirePreset() {
        val commands = RuleOptions.SECOND_EDITION.configurationCommands()
        val reconstructed =
            commands.fold(RuleOptions()) { rules, command -> command.resolve(rules) }

        assertThat(reconstructed).isEqualTo(RuleOptions.SECOND_EDITION)
        assertThat(commands).hasSize(11)
        assertThat(commands.map(RuleOptionCommand::key)).doesNotContain("epochs")
        assertThat(commands.map(RuleOptionCommand::toText))
            .doesNotContain("set-option preset second-edition")
    }

    @Test
    fun presetsExpandToFunctionalRulesBeforeIndividualOverrides() {
        val preset = RuleOptionCommand("preset", "second-edition").resolve(RuleOptions())
        val overridden = RuleOptionCommand("st-gall-support", "enabled").resolve(preset)

        assertThat(overridden).isEqualTo(RuleOptions.SECOND_EDITION.copy(stGallSupport = true))
    }

    @Test
    fun unknownOptionValuesAreTypedDiagnostics() {
        val failure = assertFailsWith<RuleViolation> { RuleOptionCommand("setup", "unknown") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
    }

    @Test
    fun gameLengthIsNotAFunctionalOption() {
        val failure = assertFailsWith<RuleViolation> { RuleOptionCommand("epochs", "3") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNSUPPORTED_OPTION)
    }

    @Test
    fun stGallSupportIsSelectedWithoutMutatingPrintedArrows() {
        val baseline = CatalogConfiguration(6)
        val alternate = CatalogConfiguration(6, RuleOptions(stGallSupport = true))
        val extra = SatelliteSupport(ProvinceId("st-gall"), ProvinceId("nuremberg"))

        assertThat(alternate.satelliteSupports).contains(extra)
        assertThat(baseline.satelliteSupports).doesNotContain(extra)
        assertThat(BoardConnectionData.satelliteSupports).doesNotContain(extra)
    }

    @Test
    fun onlyCreatorMayChangeFunctionalOptions() {
        val failure =
            assertFailsWith<RuleViolation> {
                RuleOptionCommand("setup", "improved").applyTo(GameState(header()), PlayerId("p2"))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun functionalOptionsFreezeWhenPlayStarts() {
        val failure =
            assertFailsWith<RuleViolation> {
                RuleOptionCommand("setup", "improved")
                    .applyTo(
                        GameState(header(), stage = Stage.SHUFFLE),
                        PlayerId("p1"),
                    )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    private fun header(): GameHeader =
        GameHeader(
            listOf(
                Player(PlayerId("p1"), "One"),
                Player(PlayerId("p2"), "Two"),
                Player(PlayerId("p3"), "Three"),
            ),
            creatorId = PlayerId("p1"),
        )
}
