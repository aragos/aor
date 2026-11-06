package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotShippingReplacementTest {
    private val buyer = PlayerId("p1")
    private val ids = listOf(buyer, PlayerId("p2"), PlayerId("p3"))
    private val key = BotPurchaseFeature.NAVIGATION_REPLACEMENT.key
    private val navigation =
        setOf(AdvanceId.THE_HEAVENS, AdvanceId.OVERLAND_EAST, AdvanceId.SEAWORTHY_VESSELS)

    @Test
    fun anAffordableOceanAdvanceCanSupersedeTheWholeShippingPurchase() {
        val state = fixture()
        val observation = BotObservations.from(state, buyer)

        val upgraded =
            state
                .step(TextCodec.parse("p1: upgrade-ships"))
                .step(TextCodec.parse("p1: buy-advance T"))
        val skipped =
            state.step(TextCodec.parse("p1: pass")).step(TextCodec.parse("p1: buy-advance T"))

        assertThat(upgrade(observation).features[key]).isEqualTo(-10.0)
        assertThat(upgraded.playerState(buyer).ships).isEqualTo(ShippingCapability.OCEAN_1)
        assertThat(skipped.playerState(buyer).ships).isEqualTo(upgraded.playerState(buyer).ships)
        assertThat(skipped.playerState(buyer).cash - upgraded.playerState(buyer).cash).isEqualTo(10)
    }

    @Test
    fun aSeaworthyAdvanceCanAlsoReplaceThePaidGalleyLevel() {
        val state = fixture(ships = ShippingCapability.GALLEY_4, advances = emptySet())

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features[key]).isEqualTo(-10.0)
    }

    @Test
    fun shippingQuotesDoNotAuthorizeAdvanceCommandsEarly() {
        val state = fixture()

        val view = state.viewForDecision(buyer)
        val observation = BotObservations.from(state, buyer)

        assertThat(view.advanceOffers).isEqualTo(state.viewForPlayer(buyer).advanceOffers)
        assertThat(observation.candidates.map(BotCandidate::command))
            .containsExactly("pass", "upgrade-ships")
        assertThat(state.legalChoices(buyer).commands).containsExactly("pass", "upgrade-ships")
    }

    @Test
    fun aMissingNavigationPrerequisiteKeepsTheSignalAbsent() {
        val state = fixture(cash = 200, advances = navigation - AdvanceId.THE_HEAVENS)

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features).doesNotContainKey(key)
    }

    @Test
    fun aForbiddenExplorationCategoryKeepsTheSignalAbsent() {
        val state =
            fixture(cash = 200)
                .copy(effects = CardTurnEffects(papalCategory = AdvanceCategory.EXPLORATION))

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features).doesNotContainKey(key)
    }

    @Test
    fun navigationMustRemainAffordableAfterTheShippingCost() {
        val state = fixture(cash = 89)

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features).doesNotContainKey(key)
    }

    @Test
    fun anOwnedNavigationAdvanceCannotBeBoughtAgainToReplaceOceanShipping() {
        val state =
            fixture(
                cash = 200,
                ships = ShippingCapability.OCEAN_1,
                advances = navigation + AdvanceId.OCEAN_NAVIGATION,
            )

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features).doesNotContainKey(key)
    }

    @Test
    fun maximumShippingDoesNotInventAnUpgradeCandidate() {
        val state = fixture(ships = ShippingCapability.GALLEY_8, advances = emptySet())

        val observation = BotObservations.from(state, buyer)

        assertThat(observation.candidates.map(BotCandidate::command)).containsExactly("pass")
        assertThat(observation.candidates.single().features).doesNotContainKey(key)
    }

    @Test
    fun existingResearchCreditsCanMakeTheReplacementAffordable() {
        val state = fixture(cash = 80, advances = navigation + AdvanceId.INSTITUTIONAL_RESEARCH)

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features[key]).isEqualTo(-10.0)
    }

    @Test
    fun anAlreadyAuthorizedPatronageCreditCanMakeTheReplacementAffordable() {
        val state = leaderFixture(claimed = true)

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features[key]).isEqualTo(-10.0)
    }

    @Test
    fun anUnclaimedLeaderDiscountDoesNotPredictAFuturePatronagePurchase() {
        val state = leaderFixture(claimed = false)

        val candidate = upgrade(BotObservations.from(state, buyer))

        assertThat(candidate.features).doesNotContainKey(key)
    }

    @Test
    fun aWeightedRiskCanPreferSavingTheRedundantShippingCost() {
        val observation = BotObservations.from(fixture(), buyer)
        val model = BotModel(weights = mapOf("cash" to 1.0, "reach" to 6.0, key to 4.0))

        val command = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        assertThat(command).isEqualTo("pass")
    }

    @Test
    fun missingAndZeroWeightsPreserveScoresAndEveryDifficultyChoice() {
        val observation = BotObservations.from(fixture(), buyer)
        val original =
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(features = candidate.features - key)
                    }
            )
        val weights = mapOf("cash" to 1.0, "reach" to 6.0)
        val models = listOf(BotModel(weights = weights), BotModel(weights = weights + (key to 0.0)))

        for (model in models) {
            assertThat(observation.candidates.map { BotPolicy.score(it, model) })
                .containsExactlyElementsIn(original.candidates.map { BotPolicy.score(it, model) })
                .inOrder()
            for (difficulty in BotDifficulty.entries) {
                assertThat(BotPolicy.choose(observation, difficulty, 17, model))
                    .isEqualTo(BotPolicy.choose(original, difficulty, 17, model))
            }
        }
    }

    private fun upgrade(observation: BotObservation): BotCandidate =
        observation.candidates.single { it.command == "upgrade-ships" }

    private fun leaderFixture(claimed: Boolean): GameState =
        fixture(cash = 70, advances = navigation + AdvanceId.PATRONAGE)
            .copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.ST_BENEDICT,
                                    ids[1],
                                    CardLeaderRole.DISPLAY,
                                ),
                                CardLeaderInPlay(
                                    LeaderId.PRINCE_HENRY,
                                    ids[1],
                                    CardLeaderRole.EXPOSED,
                                    patronageClaimants = if (claimed) setOf(buyer) else emptySet(),
                                ),
                            )
                    )
            )

    private fun fixture(
        cash: Int = 90,
        ships: ShippingCapability = ShippingCapability.SEAWORTHY_10,
        advances: Set<AdvanceId> = navigation,
    ): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, buyer),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = cash,
                                ships = if (id == buyer) ships else ShippingCapability.NONE,
                                advances =
                                    if (id == buyer) {
                                        PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) })
                                    } else PlayerAdvances(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = buyer,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.SHIPPING,
                ),
        )
    }
}
