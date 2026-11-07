package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.DeckHistory
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Prompt
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionCathedralEncodingTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val own = players[0].id
    private val defender = players[1].id

    @Test
    fun publicUsageSeparatesBothAttackingAndDefendingContextsWithoutChangingLegacyInputs() {
        for (owner in listOf(own, defender)) {
            val opponent = if (owner == own) defender else own
            val unused = fixture(owner)
            val used =
                unused.copy(flow = unused.flow.copy(cathedralUses = setOf(owner to opponent)))
            val before = encoded(unused)
            val after = encoded(used)
            val key =
                if (owner == own) "cathedral.used.role.0.role.1" else "cathedral.used.role.1.role.0"

            assertThat(after.stateFeatures).containsEntry(key, 1.0)
            assertThat(before.stateFeatures).doesNotContainKey(key)
            assertThat(after.stateFeatures - key).isEqualTo(before.stateFeatures)
            assertThat(after.actions).isEqualTo(before.actions)
            assertThat(after.baselineIndex).isEqualTo(before.baselineIndex)
            // Frozen v1 models retain their original inputs; new models explicitly opt into v2.
            assertThat(encoded(unused, TerritoryEncoder.EXPANSION_1))
                .isEqualTo(encoded(used, TerritoryEncoder.EXPANSION_1))
        }
    }

    @Test
    fun authoritativeDeclarationUpdatesPublicUsageAtTheNextOrdinaryDecision() {
        val state = fixture(own)
        val pending = state.step(TextCodec.parse("p1: attack florence"))
        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(own, "cathedral"))
        val next = pending.step(TextCodec.parse("p1: cathedral"))
        assertThat(encoded(next).stateFeatures).containsEntry("cathedral.used.role.0.role.1", 1.0)
        assertThat(
                next
                    .viewForDecision(own)
                    .cathedralPairs
                    .single { it.owner == own && it.opponent == defender }
                    .used
            )
            .isTrue()
    }

    @Test
    fun hiddenCardsAndDeckPermutationCannotChangeVersionTwoInputs() {
        val state = fixture(own)
        val changed =
            state.copy(
                deck = state.deck.reversed(),
                players =
                    state.players +
                        (defender to
                            state
                                .playerState(defender)
                                .copy(hand = listOf(HandCard("private", CardData.epochOne[5])))),
            )
        assertThat(encoded(changed)).isEqualTo(encoded(state))
    }

    @Test
    fun cyclicRegistrationRotationPreservesActorRelativeUsage() {
        val state =
            fixture(defender).let {
                it.copy(flow = it.flow.copy(cathedralUses = setOf(defender to own)))
            }
        val rotated = state.copy(header = GameHeader(players.drop(2) + players.take(2), own))
        assertThat(encoded(rotated)).isEqualTo(encoded(state))
    }

    @Test
    fun completeDictionaryAddsExactlyThirtyDirectedUsageColumns() {
        val catalog =
            requireNotNull(fixture(own).viewForDecision(own).catalog) {
                "Fixture needs the catalog."
            }
        val old = ExpansionObservationEncoder.dictionary(catalog)
        val current = ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2)
        assertThat(current.stateFeatureNames - old.stateFeatureNames.toSet())
            .containsExactlyElementsIn(ExpansionObservationEncoder.cathedralUsageNames)
        assertThat(ExpansionObservationEncoder.cathedralUsageNames).hasSize(30)
        assertThat(current.actionFeatureNames).isEqualTo(old.actionFeatureNames)
        assertFailsWith<IllegalArgumentException> {
            ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.TOKEN_1)
        }
    }

    private fun encoded(
        state: GameState,
        encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_2,
    ): TerritoryObservation {
        val bot = BotObservations.from(state, own)
        return ExpansionObservationEncoder.from(
            state.viewForDecision(own),
            own,
            bot,
            BotPolicy.choose(bot, BotDifficulty.HARD, 103),
            encoder,
        )
    }

    private fun fixture(cathedralOwner: PlayerId): GameState {
        val board = BoardState().placeMarker(ProvinceId("florence"), defender).board
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        return GameState(
            header = GameHeader(players, own),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = own, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            board = board,
            deck = CardData.epochOne.drop(4).map { it.id },
            deckHistory = DeckHistory(nextDraw = 5),
            players =
                players
                    .mapIndexed { index, player ->
                        val pool = if (player.id == own) 20 else 10
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                hand =
                                    listOf(HandCard("hand-${index + 1}", CardData.epochOne[index])),
                                poolTokens = pool,
                                stockTokens = 36 - pool - board.tokenCount(player.id),
                                advances =
                                    PlayerAdvances(
                                        if (player.id == cathedralOwner)
                                            listOf(AdvanceAcquisition(AdvanceId.CATHEDRAL, 1))
                                        else emptyList()
                                    ),
                            )
                    }
                    .toMap(),
        )
    }
}
