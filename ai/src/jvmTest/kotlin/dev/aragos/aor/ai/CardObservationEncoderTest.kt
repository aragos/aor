package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.CardVoids
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardObservationEncoderTest {
    private val actor = PlayerId("p1")
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }

    @Test
    fun transientProjectionBindsAnyOfferedRecommendationWithoutChangingFacts() {
        val state = fixture()
        val offered = BotObservations.from(state, actor)
        val projection = CardObservationEncoder.project(state.viewForPlayer(actor), actor, offered)
        val encoded = projection.bindBaseline("play held")

        assertThat(encoded.stateFeatures).isEqualTo(projection.stateFeatures)
        assertThat(encoded.actions).isEqualTo(projection.actions)
        assertThat(encoded.actions[encoded.baselineIndex].command).isEqualTo("play held")
    }

    @Test
    fun transientProjectionRejectsAnUnavailableRecommendation() {
        val state = fixture()
        val projection =
            CardObservationEncoder.project(
                state.viewForPlayer(actor),
                actor,
                BotObservations.from(state, actor),
            )

        assertFailsWith<IllegalArgumentException> { projection.bindBaseline("play absent") }
    }

    @Test
    fun everyOfferedCommandSurvivesWithoutAuthoredScoresOrInventedPass() {
        val state = fixture()
        val view = state.viewForPlayer(actor)
        val offered = BotObservations.from(state, actor)
        val withoutPass =
            offered.copy(
                candidates = offered.candidates.filter { it.command != "pass" },
                leaderPlanning = null,
            )
        val changed =
            withoutPass.copy(
                candidates =
                    withoutPass.candidates.map {
                        it.copy(features = mapOf("invented-utility" to 100000.0))
                    }
            )
        val recommendation = withoutPass.candidates.first().command

        val encoded = CardObservationEncoder.from(view, actor, changed, recommendation)

        assertThat(encoded.actions.map(CardAction::command))
            .containsExactlyElementsIn(withoutPass.candidates.map(BotCandidate::command).sorted())
            .inOrder()
        assertThat(encoded.actions.map(CardAction::command)).doesNotContain("pass")
        assertThat(encoded)
            .isEqualTo(CardObservationEncoder.from(view, actor, withoutPass, recommendation))
    }

    @Test
    fun finalWindowIsExplicitWithoutInventingADiscountUtility() {
        val encoded = encode(fixture().copy(stage = Stage.FINAL_CARD_PLAY, endingTriggered = true))

        assertThat(encoded.stateFeatures["cardWindow.final"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("cardWindow.normal")
        assertThat(encoded.actions.flatMap { it.features.keys }.distinct())
            .doesNotContain("discount")
    }

    @Test
    fun anotherCardPlayRetainsTheLeaderAndRegeneratesTheHand() {
        val state = fixture()
        val after = state.step(TextCodec.parse("p1: play cash-card commodity wool"))

        val encoded = encode(after)

        assertThat(encoded.stateFeatures["hand.leonardo-da-vinci"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("hand.wool-1")
        assertThat(encoded.actions.map(CardAction::command)).contains("play held")
        assertThat(encoded.actions.map(CardAction::command))
            .doesNotContain("play cash-card commodity wool")
    }

    @Test
    fun leaderPlayRepricesTheNextActualOrdinaryObservation() {
        val state = fixture()
        val after = state.step(TextCodec.parse("p1: play held"))

        val encoded = encode(after)

        assertThat(encoded.stateFeatures["leader.leonardo-da-vinci.present"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures["offer.MASTER_ART.leaderCredit"]).isGreaterThan(0.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("hand.leonardo-da-vinci")
        assertThat(encoded.actions.map(CardAction::command)).doesNotContain("play held")
    }

    @Test
    fun protectionUsesTheActualPrintedWordReceiptOnce() {
        val state = fixture(printedWord = true)
        val action = encode(state).actions.single { it.command == "play held protect 20" }
        val after = state.step(TextCodec.parse("p1: play held protect 20"))

        assertThat(action.features["cashAfter"]).isEqualTo(after.playerState(actor).cash / 200.0)
        assertThat(action.features["protection"]).isEqualTo(0.1)
        assertThat(action.features["cashAfter"]).isEqualTo(0.5)
        assertThat(action.features).doesNotContainKey("effect.role.0.cashDelta")
    }

    @Test
    fun plainAndZeroProtectionQuoteTheSameImmediateCash() {
        val encoded = encode(fixture(printedWord = true))
        val plain = encoded.actions.single { it.command == "play held" }
        val protected = encoded.actions.single { it.command == "play held protect 0" }

        assertThat(protected.features["cashAfter"]).isEqualTo(plain.features["cashAfter"])
        assertThat(protected.features["effect.role.0.cashDelta"])
            .isEqualTo(plain.features["effect.role.0.cashDelta"])
    }

    @Test
    fun privateOpponentCardIdentitiesNeverEnterTheEncoding() {
        val original = fixture()
        fun opponent(card: String): GameState =
            original.copy(
                players =
                    original.players.mapValues { (id, value) ->
                        if (id == PlayerId("p2"))
                            value.copy(
                                hand = listOf(HandCard("private", CardData.definition(card)))
                            )
                        else value
                    }
            )

        assertThat(encode(opponent("charlemagne"))).isEqualTo(encode(opponent("marco-polo")))
    }

    @Test
    fun physicalReferencesDoNotBecomeLearnedFeatureIdentities() {
        val original = fixture()
        val renamed =
            original.copy(
                players =
                    original.players.mapValues { (id, value) ->
                        if (id == actor)
                            value.copy(
                                hand =
                                    value.hand.map {
                                        if (it.reference == "held") it.copy(reference = "renamed")
                                        else it
                                    }
                            )
                        else value
                    }
            )
        val first = encode(original)
        val second = encode(renamed)

        assertThat(first.stateFeatures).isEqualTo(second.stateFeatures)
        assertThat(first.actions.single { it.command == "play held" }.features)
            .isEqualTo(second.actions.single { it.command == "play renamed" }.features)
    }

    @Test
    fun fullVocabularyContainsAbsentCardsTargetsAndAllEmittedNames() {
        val state = fixture(printedWord = true)
        val dictionary = CardObservationEncoder.dictionary(state.viewForPlayer(actor))
        val encoded = encode(state)

        assertThat(dictionary.stateFeatureNames)
            .containsAtLeast(
                "hand.marco-polo",
                "role.5.advance.NEW_WORLD",
                "handPlayable.marco-polo",
                "handBurden.armor",
            )
        assertThat(dictionary.actionFeatureNames)
            .containsAtLeast("target.province.venice", "credit.NEW_WORLD.effective")
        assertThat(dictionary.stateFeatureNames)
            .containsAtLeastElementsIn(encoded.stateFeatures.keys)
        assertThat(dictionary.actionFeatureNames)
            .containsAtLeastElementsIn(encoded.actions.flatMap { it.features.keys }.distinct())
        assertThat(dictionary.actionFeatureNames.size).isAtMost(512)
    }

    @Test
    fun anUnquotedParameterizedEffectIsMarkedInsteadOfInvented() {
        val state = fixture()
        val ordinary = state.viewForPlayer(actor)
        val view =
            ordinary.copy(
                cardOffers =
                    ordinary.cardOffers.map {
                        if (it.reference == "cash-card") it.copy(choices = emptyList()) else it
                    }
            )
        val encoded =
            CardObservationEncoder.from(view, actor, BotObservations.from(state, actor), "pass")

        val action = encoded.actions.single { it.command == "play cash-card commodity wool" }
        assertThat(action.features["effect.unquoted"]).isEqualTo(1.0)
        assertThat(action.features).doesNotContainKey("effect.quoted")
        assertThat(action.features).doesNotContainKey("cashAfter")
    }

    @Test
    fun aQuotedZeroReceiptKeepsItsKnownCashEndpoint() {
        val state = fixture()
        val action = encode(state).actions.single { it.command == "play cash-card commodity wool" }
        val after = state.step(TextCodec.parse("p1: play cash-card commodity wool"))

        assertThat(after.playerState(actor).cash).isEqualTo(state.playerState(actor).cash)
        assertThat(action.features["effect.quoted"]).isEqualTo(1.0)
        assertThat(action.features["cashAfter"]).isEqualTo(after.playerState(actor).cash / 200.0)
        assertThat(action.features).doesNotContainKey("effect.role.0.cashDelta")
    }

    @Test
    fun ownPlayableAndBurdenFactsUseAuthoritativeStableCardIdentities() {
        val original = fixture()
        val state =
            original.copy(
                voids = CardVoids(armorAndStirrups = true),
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == actor)
                            player.copy(
                                hand =
                                    player.hand +
                                        HandCard("unusable-armor", CardData.definition("armor"))
                            )
                        else player
                    },
            )
        val ordinary = state.viewForPlayer(actor)
        val encoded = encode(state)

        assertThat(ordinary.burdenReferences).contains("unusable-armor")
        assertThat(ordinary.cardOffers.single { it.reference == "unusable-armor" }.playable)
            .isFalse()
        assertThat(encoded.stateFeatures["hand.armor"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures["handBurden.armor"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("handPlayable.armor")
        assertThat(encoded.stateFeatures["handPlayable.wool-1"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures["handPlayable.leonardo-da-vinci"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("handBurden.wool-1")
        assertThat(encoded.stateFeatures.keys.none { "unusable-armor" in it }).isTrue()
    }

    @Test
    fun blockedOwnCardDoesNotBecomeABurdenWithoutTheAuthoritativeFact() {
        val state = fixture()
        val ordinary = state.viewForPlayer(actor)
        val view =
            ordinary.copy(
                cardOffers =
                    ordinary.cardOffers.map {
                        if (it.reference == "cash-card") it.copy(playable = false) else it
                    }
            )
        val observation = BotObservations.from(state, actor)
        val offered =
            observation.copy(
                candidates =
                    observation.candidates.filter {
                        !it.command.startsWith("play cash-card ")
                    },
                leaderPlanning = null,
            )
        val encoded = CardObservationEncoder.from(view, actor, offered, "pass")

        assertThat(encoded.stateFeatures["hand.wool-1"]).isEqualTo(1.0)
        assertThat(encoded.stateFeatures).doesNotContainKey("handPlayable.wool-1")
        assertThat(encoded.stateFeatures).doesNotContainKey("handBurden.wool-1")
    }

    @Test
    fun nestedDecisionsCannotEnterTheCardActor() {
        val state = fixture()
        val input =
            BotObservations.from(state, actor).copy(decision = "prepare-war", leaderPlanning = null)

        assertFailsWith<IllegalArgumentException> {
            CardObservationEncoder.from(state.viewForPlayer(actor), actor, input, "pass")
        }
    }

    private fun encode(state: GameState): CardObservation {
        val observation = BotObservations.from(state, actor)
        return CardObservationEncoder.from(
            state.viewForPlayer(actor),
            actor,
            observation,
            observation.candidates.first().command,
        )
    }

    private fun fixture(printedWord: Boolean = false): GameState {
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        val owned =
            if (printedWord)
                setOf(AdvanceId.WRITTEN_RECORD, AdvanceId.HUMAN_BODY, AdvanceId.PRINTED_WORD)
            else emptySet()
        return GameState(
            header = GameHeader(players, actor),
            stage = Stage.PLAY_CARDS,
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
                                cash = 100,
                                advances =
                                    if (player.id == actor)
                                        PlayerAdvances(owned.map { AdvanceAcquisition(it, 1) })
                                    else PlayerAdvances(),
                                hand =
                                    if (player.id == actor)
                                        listOf(
                                            HandCard(
                                                "held",
                                                CardData.definition("leonardo-da-vinci"),
                                            ),
                                            HandCard("cash-card", CardData.definition("wool-1")),
                                        )
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            effects =
                CardTurnEffects(
                    leaders =
                        listOf(
                            CardLeaderInPlay(
                                LeaderId.ST_BENEDICT,
                                PlayerId("p2"),
                                CardLeaderRole.DISPLAY,
                            )
                        )
                ),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
