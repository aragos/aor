package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.CardVoids
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotPurchasePlannerTest {
    private val owner = PlayerId("p1")
    private val identities = (1..4).map { Player(PlayerId("p$it"), "Seat $it") }

    @Test
    fun shippingUpgradeIsSkippedWhenSeaworthyWillReplaceIt() {
        val state = fixture(cash = 100)
        val model = BotModel(weights = mapOf("cash" to 1.0, "reach" to 10.0, "advance.S" to 1000.0))

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("pass")
    }

    @Test
    fun shippingUpgradeIsSkippedWhenOceanNavigationWillReplaceIt() {
        val state =
            fixture(cash = 120, owned = listOf(AdvanceId.THE_HEAVENS, AdvanceId.SEAWORTHY_VESSELS))
                .updateOwner { it.copy(ships = ShippingCapability.SEAWORTHY_10) }
        val model = BotModel(weights = mapOf("cash" to 1.0, "reach" to 10.0, "advance.T" to 1000.0))

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("pass")
    }

    @Test
    fun usefulShippingUpgradeIsRetainedWhenNoReplacementIsChosen() {
        val state = fixture(cash = 40)
        val model = BotModel(weights = mapOf("cash" to 1.0, "reach" to 10.0, "points" to -10.0))

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("upgrade-ships")
    }

    @Test
    fun prerequisitePurchaseUnlocksAnAdvanceAbsentFromTheCurrentOffers() {
        val state =
            fixture(
                cash = 160,
                step = PurchaseStep.ADVANCES,
                owned = listOf(AdvanceId.SEAWORTHY_VESSELS),
            )
        val model = BotModel(weights = mapOf("cash" to 1.0, "advance.T" to 1000.0))
        assertThat(BotObservations.from(state, owner).candidates.map(BotCandidate::command))
            .doesNotContain("buy-advance T")

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("buy-advance A")
    }

    @Test
    fun institutionalResearchRepricesTheRestOfThePurchaseSequence() {
        val state = fixture(cash = 120, step = PurchaseStep.ADVANCES)
        val model =
            BotModel(weights = mapOf("cash" to 1.0, "advance.X" to 150.0, "advance.A" to 100.0))

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("buy-advance X")
    }

    @Test
    fun advanceValueIncludesItsResultingMandatoryStabilization() {
        val state =
            fixture(cash = 80, step = PurchaseStep.ADVANCES).updateOwner {
                it.copy(hand = listOf(card("a", "timber"), card("b", "armor")))
            }
        val model =
            BotModel(weights = mapOf("cash" to 1.0, "misery" to -100.0, "advance.S" to 1000.0))

        val selected = choose(state, model)

        assertThat(selected).isEqualTo("pass")
    }

    @Test
    fun immediateMasterArtDiscardPreservesACommodityAndRemovesItsBurden() {
        val state =
            fixture(
                    cash = 100,
                    step = PurchaseStep.ADVANCES,
                    owned = listOf(AdvanceId.WRITTEN_RECORD, AdvanceId.PRINTED_WORD),
                )
                .copy(voids = CardVoids(armorAndStirrups = true))
                .updateOwner {
                    it.copy(hand = listOf(card("a-valuable", "timber"), card("z-burden", "armor")))
                }
        val pending = state.step(Entry.Action(owner, TextCodec.parseCommand("buy-advance P")))

        val selected = choose(pending, BotModels.default)

        assertThat(selected).isEqualTo("discard-card z-burden")
    }

    @Test
    fun hiddenOpponentCardsAndDeckOrderDoNotChangePurchasePlanning() {
        val opponent = identities[1].id
        val original = fixture(cash = 120)
        val originalOpponent =
            requireNotNull(original.players[opponent]) {
                "The privacy fixture requires its opponent."
            }
        val state =
            original.copy(
                deck = listOf("armor", "timber"),
                players =
                    original.players +
                        (opponent to originalOpponent.copy(hand = listOf(card("secret", "armor")))),
            )
        val other =
            requireNotNull(state.players[opponent]) { "The privacy fixture requires its opponent." }
        val changed =
            state.copy(
                deck = state.deck.reversed(),
                players =
                    state.players +
                        (opponent to other.copy(hand = listOf(card("secret", "stirrups")))),
            )

        val originalObservation = BotObservations.from(state, owner)
        val changedObservation = BotObservations.from(changed, owner)

        assertThat(changedObservation).isEqualTo(originalObservation)
        assertThat(BotPolicy.choose(changedObservation, BotDifficulty.HARD, 7))
            .isEqualTo(BotPolicy.choose(originalObservation, BotDifficulty.HARD, 7))
    }

    private fun choose(state: GameState, model: BotModel): String {
        val observation = BotObservations.from(state, owner)
        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)
        assertThat(state.legalChoices(owner).commands).contains(selected)
        return selected
    }

    private fun fixture(
        cash: Int,
        step: PurchaseStep = PurchaseStep.SHIPPING,
        owned: List<AdvanceId> = emptyList(),
    ): GameState =
        GameState(
            header = GameHeader(identities, owner),
            stage = Stage.PURCHASE,
            rules = RuleOptions(deckModification = true, immediateMasterArtDiscard = true),
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(identities.map(Player::id)),
            players =
                identities
                    .mapIndexed { index, identity ->
                        identity.id to
                            PlayerState(
                                player = identity,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = if (identity.id == owner) cash else 40,
                                advances =
                                    if (identity.id == owner) {
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    } else {
                                        PlayerAdvances()
                                    },
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = owner,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = step,
                ),
        )

    private fun GameState.updateOwner(transform: (PlayerState) -> PlayerState): GameState =
        copy(
            players =
                players +
                    (owner to
                        transform(
                            requireNotNull(players[owner]) {
                                "A purchase fixture requires its owner."
                            }
                        ))
        )

    private fun card(reference: String, card: String): HandCard =
        HandCard(reference, CardData.definition(card))
}
