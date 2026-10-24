package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WarRulesTest {
    private val war = WarState(cardPlayerOne, cardPlayerTwo, startedTurn = 1)

    @Test
    fun warPlayRecordsItsTwoParticipants() {
        val result =
            CardRules.resolve(
                cardContext("war"),
                cardPlayerOne,
                cardEvent(EventId.WAR, CardChoice.PlayerChoice(cardPlayerTwo)),
            )

        assertThat(result).isEqualTo(CardResolution.WarBattle(war))
    }

    @Test
    fun modifiedRollDifferenceDeterminesTheRequiredTransfers() {
        val context = cardContext("war").withAdvances(cardPlayerOne, AdvanceId.NATIONALISM)

        val result =
            WarRules.roll(context, war, WarDice(DieRoll(5), DieRoll(2))) as WarResult.Victory

        assertThat(result.winner).isEqualTo(cardPlayerOne)
        assertThat(result.requiredTransfers).isEqualTo(4)
    }

    @Test
    fun militaryAdvantageWinsAModifiedTieWithZeroTransfers() {
        val context =
            cardContext("war")
                .copy(
                    effects =
                        CardTurnEffects(
                            military = listOf(CardMilitaryPlay(cardPlayerOne, EventId.ARMOR))
                        )
                )

        val result =
            WarRules.roll(context, war, WarDice(DieRoll(2), DieRoll(3))) as WarResult.Victory

        assertThat(result.winner).isEqualTo(cardPlayerOne)
        assertThat(result.requiredTransfers).isEqualTo(0)
    }

    @Test
    fun armorPreventsTheOpponentsStirrupsModifier() {
        val context =
            cardContext("war")
                .copy(
                    effects =
                        CardTurnEffects(
                            military =
                                listOf(
                                    CardMilitaryPlay(cardPlayerOne, EventId.ARMOR),
                                    CardMilitaryPlay(cardPlayerTwo, EventId.STIRRUPS),
                                )
                        )
                )

        val result =
            WarRules.roll(context, war, WarDice(DieRoll(3), DieRoll(3))) as WarResult.Victory

        assertThat(result.requiredTransfers).isEqualTo(1)
        assertThat(result.winner).isEqualTo(cardPlayerOne)
    }

    @Test
    fun nationalismDoesNotAutomaticallyBreakAModifiedTie() {
        val context = cardContext("war").withAdvances(cardPlayerOne, AdvanceId.NATIONALISM)

        val result = WarRules.roll(context, war, WarDice(DieRoll(2), DieRoll(3))) as WarResult.Tie

        assertThat(result.war.ties).isEqualTo(1)
    }

    @Test
    fun tiedWarCanContinueInASubsequentTurn() {
        val context = cardContext("war").copy(turn = 3)

        val result =
            WarRules.roll(
                context,
                war.copy(ties = 4),
                WarDice(DieRoll(3), DieRoll(3)),
            ) as WarResult.Tie

        assertThat(result.war.startedTurn).isEqualTo(1)
        assertThat(result.war.ties).isEqualTo(5)
    }

    @Test
    fun aWarParticipantInChaosEndsTheWar() {
        val context =
            cardContext("war").withPlayer(cardPlayerTwo) { player ->
                player.copy(misery = MiseryStep.CHAOS)
            }

        assertThat(WarRules.canContinue(context, war)).isFalse()
    }

    @Test
    fun areaFiveMarkersRequireOverlandEast() {
        val context =
            cardContext("war").withMarkers("kiev" to cardPlayerTwo, "tunis" to cardPlayerTwo)

        assertThat(WarRules.eligibleProvinces(context, cardPlayerOne, cardPlayerTwo))
            .containsExactly(ProvinceId("tunis"))
    }

    @Test
    fun supportableFarEastMarkersIgnoreShippingRange() {
        val context =
            cardContext("war")
                .withMarkers("china" to cardPlayerTwo)
                .withAdvances(cardPlayerOne, AdvanceId.OCEAN_NAVIGATION)
                .withPlayer(cardPlayerOne) { player ->
                    player.copy(ships = ShippingCapability.OCEAN_1)
                }

        assertThat(WarRules.eligibleProvinces(context, cardPlayerOne, cardPlayerTwo))
            .containsExactly(ProvinceId("china"))
    }

    @Test
    fun newWorldMarkersRequireNewWorldAsWellAsOceanNavigation() {
        val context =
            cardContext("war")
                .withMarkers("north-america" to cardPlayerTwo)
                .withAdvances(cardPlayerOne, AdvanceId.OCEAN_NAVIGATION)

        assertThat(WarRules.eligibleProvinces(context, cardPlayerOne, cardPlayerTwo)).isEmpty()
    }

    @Test
    fun uncededSupportableMarkersCostAdditionalMisery() {
        val context =
            cardContext("war").withMarkers("tunis" to cardPlayerTwo, "suez" to cardPlayerTwo)
        val result =
            WarResult.Victory(
                cardPlayerOne,
                cardPlayerTwo,
                requiredTransfers = 5,
                totals = mapOf(cardPlayerOne to 6, cardPlayerTwo to 1),
            )

        val settlement =
            WarRules.surrender(context, result, setOf(ProvinceId("tunis"), ProvinceId("suez")))

        assertThat(settlement.additionalLoserMisery).isEqualTo(3)
    }

    @Test
    fun theLoserMustChooseEnoughAvailableProvinces() {
        val context =
            cardContext("war").withMarkers("tunis" to cardPlayerTwo, "suez" to cardPlayerTwo)
        val result =
            WarResult.Victory(
                cardPlayerOne,
                cardPlayerTwo,
                requiredTransfers = 2,
                totals = mapOf(cardPlayerOne to 4, cardPlayerTwo to 2),
            )

        val failure =
            assertFailsWith<CardRuleViolation> {
                WarRules.surrender(context, result, setOf(ProvinceId("tunis")))
            }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_SURRENDER)
    }

    @Test
    fun overseasMaintenanceCapacityLimitsWarCessions() {
        val context =
            cardContext("war")
                .withMarkers("china" to cardPlayerTwo, "india" to cardPlayerTwo)
                .withAdvances(cardPlayerOne, AdvanceId.OCEAN_NAVIGATION)
                .withPlayer(cardPlayerOne) { player ->
                    player.copy(ships = ShippingCapability.OCEAN_1)
                }
        val result =
            WarResult.Victory(
                cardPlayerOne,
                cardPlayerTwo,
                requiredTransfers = 2,
                totals = mapOf(cardPlayerOne to 5, cardPlayerTwo to 3),
            )

        val settled = WarRules.surrender(context, result, setOf(ProvinceId("china")))

        assertThat(settled.additionalLoserMisery).isEqualTo(1)
    }

    @Test
    fun existingOverseasTokensUseCapacityEvenWithoutADominationMarker() {
        val context =
            cardContext("war")
                .withMarkers("india" to cardPlayerTwo)
                .withAdvances(cardPlayerOne, AdvanceId.OCEAN_NAVIGATION)
                .withPlayer(cardPlayerOne) { player ->
                    player.copy(
                        ships = ShippingCapability.OCEAN_1,
                        overseasPresence = setOf(ProvinceId("china")),
                    )
                }

        assertThat(WarRules.eligibleProvinces(context, cardPlayerOne, cardPlayerTwo)).isEmpty()
    }
}
