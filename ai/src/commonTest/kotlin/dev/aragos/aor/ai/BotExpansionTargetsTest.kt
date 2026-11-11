package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.IncomeView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotExpansionTargetsTest {
    private val actor = PlayerId("p1")
    private val rival = PlayerId("p2")
    private val ids = listOf(actor, rival, PlayerId("p3"))
    private val target = ProvinceId("rome")

    @Test
    fun publicSharesIncludeTheActorAndScaleWithTheOfferedDiceProbability() {
        val original = fixture().viewForDecision(actor)
        val incomes = listOf(16, 24, 40)
        val advances =
            listOf(
                emptySet(),
                setOf(AdvanceId.CARAVAN),
                setOf(AdvanceId.CARAVAN, AdvanceId.THE_HEAVENS, AdvanceId.WRITTEN_RECORD),
            )
        val view =
            original.copy(
                players =
                    original.players.mapIndexed { index, player ->
                        player.copy(
                            cash = 100 * (index + 1),
                            income = IncomeView(incomes[index], incomes[index], 0, 0, 0),
                            advances = advances[index],
                        )
                    }
            )
        val offer =
            view.expansionOffers
                .single { it.destination == target }
                .copy(competitionWinProbability = 0.6)

        val features = BotExpansionTargets(view, actor).features(offer, complete = true)

        assertEquals(0.6, features[BotExpansionTargetFeature.MARKET.key])
        assertEquals(0.18, features[BotExpansionTargetFeature.INCOME_SHARE.key])
        assertEquals(0.15, features[BotExpansionTargetFeature.ADVANCE_SHARE.key])
        assertEquals(0.2, features[BotExpansionTargetFeature.CASH_SHARE.key])
    }

    @Test
    fun emptyPublicResourceTotalsHaveZeroShares() {
        val original = fixture().viewForDecision(actor)
        val view =
            original.copy(
                players =
                    original.players.map {
                        it.copy(cash = 0, advances = emptySet(), income = null)
                    }
            )
        val offer = view.expansionOffers.single { it.destination == target }

        val features = BotExpansionTargets(view, actor).features(offer, complete = true)

        assertEquals(0.0, features[BotExpansionTargetFeature.INCOME_SHARE.key])
        assertEquals(0.0, features[BotExpansionTargetFeature.ADVANCE_SHARE.key])
        assertEquals(0.0, features[BotExpansionTargetFeature.CASH_SHARE.key])
    }

    @Test
    fun peacefulPlacementsDoNotAcquireTargetFacts() {
        val view = fixture().viewForDecision(actor)
        val offer = view.expansionOffers.first { it.primaryAction == ExpansionAction.PLACE }

        val features = BotExpansionTargets(view, actor).features(offer, complete = true)

        assertTrue(features.isEmpty())
    }

    @Test
    fun incompleteCommitmentsDoNotPromiseMarketRemoval() {
        val view = fixture().viewForDecision(actor)
        val offer = view.expansionOffers.single { it.destination == target }

        val features = BotExpansionTargets(view, actor).features(offer, complete = false)

        assertTrue(features.isEmpty())
    }

    @Test
    fun attacksOnTokensDoNotInventAnOpposingMarket() {
        val original = fixture()
        val state =
            original.copy(
                board =
                    original.board
                        .replaceWithToken(
                            target,
                            rival,
                            PieceSide.COLOURED,
                        )
                        .board
            )
        val view = state.viewForDecision(actor)
        val offer = view.expansionOffers.single { it.destination == target }

        val features = BotExpansionTargets(view, actor).features(offer, complete = true)

        assertTrue(features.isEmpty())
    }

    @Test
    fun unweightedTargetFactsPreserveScoresAndChoicesForEveryDifficulty() {
        val observation = BotObservations.from(fixture(), actor)
        val stripped =
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(
                            features =
                                candidate.features.filterKeys { key ->
                                    BotExpansionTargetFeature.entries.none {
                                        key == it.key || key.endsWith(".${it.key}")
                                    }
                                }
                        )
                    }
            )

        val before = stripped.candidates.map { BotPolicy.score(it) }
        val after = observation.candidates.map { BotPolicy.score(it) }

        assertFalse(observation == stripped)
        assertEquals(before, after)
        for (difficulty in BotDifficulty.entries) {
            for (seed in 0..20) {
                assertEquals(
                    BotPolicy.choose(stripped, difficulty, seed),
                    BotPolicy.choose(observation, difficulty, seed),
                )
            }
        }
    }

    @Test
    fun privateOpponentCardsAndDeckOrderCannotChangeTargetFacts() {
        val original = fixture()
        val opponent = checkNotNull(original.players[rival]) { "The fixture requires its rival." }
        val changed =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players +
                        (rival to
                            opponent.copy(
                                hand = listOf(HandCard("hidden", CardData.definition("stirrups")))
                            )),
            )

        val before = BotObservations.from(original, actor)
        val after = BotObservations.from(changed, actor)

        assertEquals(before, after)
    }

    private fun fixture(): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals)
                .fold(BoardState()) { current, (id, capital) ->
                    current.placeMarker(ProvinceId(capital), id).board
                }
                .placeMarker(target, rival)
                .board
                .placeMarker(ProvinceId("florence"), rival)
                .board
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, actor),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            deck = listOf("armor", "stirrups"),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 100,
                                poolTokens = 15,
                                stockTokens = 21,
                                hand =
                                    if (id == rival) {
                                        listOf(HandCard("hidden", CardData.definition("armor")))
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
