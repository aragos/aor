package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class PurchaseObservationEncoderTest {
    private val buyer = PlayerId("p1")
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }

    @Test
    fun fullVocabularyIncludesAbsentLateGameAndOpponentFacts() {
        val state = fixture()
        val view = state.viewForPlayer(buyer)

        val names = PurchaseObservationEncoder.dictionary(view)

        assertThat(names.stateFeatureNames)
            .containsAtLeast(
                "hand.leonardo-da-vinci",
                "role.5.advance.NEW_WORLD",
                "board.venice.role.5.marker.white",
            )
        assertThat(names.actionFeatureNames)
            .containsAtLeast("advance.NEW_WORLD", "leader.marco-polo")
        assertThat(names.stateFeatureNames)
            .containsAtLeastElementsIn(encode(state).stateFeatures.keys)
        assertThat(names.actionFeatureNames)
            .containsAtLeastElementsIn(encode(state).actions.flatMap { it.features.keys }.toSet())
    }

    @Test
    fun everyActualOfferedPurchaseSurvivesWithoutScoringOrTopKFiltering() {
        val state = fixture()
        val offered = BotObservations.from(state, buyer)

        val encoded = encode(state)

        assertThat(encoded.actions.map(PurchaseAction::command))
            .containsExactlyElementsIn(offered.candidates.map(BotCandidate::command).sorted())
            .inOrder()
        assertThat(encoded.actions[encoded.baselineIndex].command).isEqualTo("pass")
    }

    @Test
    fun anUnboundProjectionRetainsTheOrdinaryViewsPublicCardInventory() {
        val state = fixture()
        val ordinary = state.viewForPlayer(buyer)
        val offered = BotObservations.from(state, buyer)
        val expected =
            ordinary.unplayedCards
                .groupingBy { it.id }
                .eachCount()
                .mapKeys { (id, _) ->
                    "unplayed.$id"
                }
                .mapValues { (_, count) -> count.toDouble() }

        val projected = PurchaseObservationEncoder.project(ordinary, buyer, offered)

        assertThat(expected).isNotEmpty()
        assertThat(state.viewForDecision(buyer).unplayedCards).isEmpty()
        assertThat(projected.stateFeatures.filterKeys { it.startsWith("unplayed.") })
            .isEqualTo(expected)
        assertThat(projected.actions.map(PurchaseAction::command))
            .containsExactlyElementsIn(offered.candidates.map(BotCandidate::command).sorted())
            .inOrder()
    }

    @Test
    fun aRealPrerequisitePurchaseExposesItsNewlyLegalSuccessor() {
        val state = fixture()
        val before = encode(state)
        val after = encode(state.step(TextCodec.parse("p1: buy-advance N")))

        assertThat(before.actions.map(PurchaseAction::command)).doesNotContain("buy-advance O")
        assertThat(after.actions.map(PurchaseAction::command)).contains("buy-advance O")
        assertThat(after.stateFeatures["role.0.advance.WRITTEN_RECORD"]).isEqualTo(1.0)
    }

    @Test
    fun researchRepricesLaterActionsThroughTheAuthoritativeEngine() {
        val state = fixture()
        val before = encode(state).actions.single { it.command == "buy-advance A" }
        val after =
            encode(state.step(TextCodec.parse("p1: buy-advance X"))).actions.single {
                it.command == "buy-advance A"
            }

        assertThat(before.features["cost"]).isEqualTo(0.15)
        assertThat(after.features["cost"]).isEqualTo(0.1)
        assertThat(after.features["researchCredit"]).isEqualTo(0.1)
    }

    @Test
    fun shippingAndPassKeepTheirActualResourceEndpoints() {
        val initial = fixture()
        val state = initial.copy(flow = initial.flow.copy(purchaseStep = PurchaseStep.SHIPPING))

        val actions = encode(state).actions

        assertThat(actions.map(PurchaseAction::command))
            .containsExactly("pass", "upgrade-ships")
            .inOrder()
        assertThat(actions.last().features).containsEntry("ships.GALLEY_2", 1.0)
        assertThat(actions.last().features).containsEntry("cashAfter", 4.95)
        assertThat(actions.first().features).containsEntry("cashAfter", 5.0)
    }

    @Test
    fun patronageRemainsAnOfferedActionWithPublicRecipientAndEscrow() {
        val initial = fixture(owned = setOf(AdvanceId.PATRONAGE))
        val state =
            initial.copy(
                effects =
                    CardTurnEffects(
                        leaders =
                            listOf(
                                CardLeaderInPlay(
                                    LeaderId.ST_BENEDICT,
                                    players[1].id,
                                    CardLeaderRole.DISPLAY,
                                ),
                                CardLeaderInPlay(
                                    LeaderId.RASHID_AD_DIN,
                                    players[2].id,
                                    CardLeaderRole.EXPOSED,
                                    protectionCash = 5,
                                ),
                            )
                    )
            )

        val action = encode(state).actions.single { it.command == "patronize rashid-ad-din" }

        assertThat(action.features).containsEntry("cost", 0.025)
        assertThat(action.features).containsEntry("recipient.role.2", 1.0)
    }

    @Test
    fun changingAnOpponentsPrivateCardIdentityDoesNotChangeTheInput() {
        val state = fixture()
        val other = state.playerState(players[1].id)
        fun withCard(id: String): GameState =
            state.copy(
                players =
                    state.players +
                        (other.player.id to
                            other.copy(hand = listOf(HandCard("private", CardData.definition(id)))))
            )

        assertThat(encode(withCard("wool-1"))).isEqualTo(encode(withCard("stone-1")))
    }

    @Test
    fun namesAndTransientOwnHandReferencesAreNotFeatures() {
        val state = fixture()
        val view = state.viewForPlayer(buyer)
        val offered = BotObservations.from(state, buyer)
        val renamed =
            view.copy(
                players = view.players.map { it.copy(name = "arbitrary private display name") },
                hand = view.hand.map { it.copy(reference = "replacement-reference") },
            )

        val original = PurchaseObservationEncoder.from(view, buyer, offered, "pass")
        val changed = PurchaseObservationEncoder.from(renamed, buyer, offered, "pass")

        assertThat(changed).isEqualTo(original)
        assertThat(original.stateFeatures["hand.charlemagne"]).isEqualTo(1.0)
    }

    @Test
    fun authoredBaselineScoreFeaturesAreNotNeuralInputs() {
        val state = fixture()
        val view = state.viewForPlayer(buyer)
        val original = BotObservations.from(state, buyer)
        val changed =
            original.copy(
                candidates =
                    original.candidates.map {
                        it.copy(features = mapOf("fabricated-score" to 100000.0))
                    }
            )

        assertThat(PurchaseObservationEncoder.from(view, buyer, changed, "pass"))
            .isEqualTo(PurchaseObservationEncoder.from(view, buyer, original, "pass"))
    }

    @Test
    fun dictionaryDoesNotDependOnWhichCardsOrAdvancesArePresent() {
        val first = fixture()
        val later = first.step(TextCodec.parse("p1: buy-advance N"))

        assertThat(PurchaseObservationEncoder.dictionary(first.viewForPlayer(buyer)))
            .isEqualTo(PurchaseObservationEncoder.dictionary(later.viewForPlayer(buyer)))
    }

    @Test
    fun highLegalCashAmountsRemainWithinTheDeclaredFeatureRange() {
        val state = fixture()
        val original = state.viewForPlayer(buyer)
        val view =
            original.copy(
                players =
                    original.players.map {
                        if (it.id == buyer) it.copy(cash = Int.MAX_VALUE) else it
                    }
            )

        val observation =
            PurchaseObservationEncoder.from(view, buyer, BotObservations.from(state, buyer), "pass")

        assertThat(observation.stateFeatures["role.0.cash"]).isEqualTo(16.0)
        assertThat(
                observation.actions.all { action ->
                    action.features.values.all { it in -16.0..16.0 }
                }
            )
            .isTrue()
    }

    private fun encode(state: GameState): PurchaseObservation =
        PurchaseObservationEncoder.from(
            state.viewForPlayer(buyer),
            buyer,
            BotObservations.from(state, buyer),
            "pass",
        )

    private fun fixture(owned: Set<AdvanceId> = emptySet()): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, buyer),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 1000,
                                advances =
                                    if (player.id == buyer)
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                                hand =
                                    if (player.id == buyer)
                                        listOf(HandCard("held", CardData.definition("charlemagne")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = buyer,
                    purchaseStep = PurchaseStep.ADVANCES,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }
}
