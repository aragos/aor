package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.CatalogConfiguration
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GeographicalArea
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvincePieces
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TokenHolding
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf

class BotAreaEffectsTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val p4 = PlayerId("p4")
    private val ids = listOf(p1, p2, p3, p4)
    private val areaKeys =
        setOf(BotEconomicFeature.AREA_OWN_LOSS.key, BotEconomicFeature.AREA_OPPONENT_LOSS.key)

    @Test
    fun learnedAreaValuesPreferAnEnemyOnlyTargetToSelfDevastation() {
        val before = fixture()
        val observation = BotObservations.from(before, p1)
        val model =
            BotModels.default.copy(
                weights =
                    BotModels.default.weights +
                        mapOf(
                            BotEconomicFeature.AREA_OWN_LOSS.key to 10.0,
                            BotEconomicFeature.AREA_OPPONENT_LOSS.key to 5.0,
                        )
            )

        val command = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)
        val after = before.step(TextCodec.parse("p1: $command"))

        assertThat(command).isEqualTo("play event area vii")
        assertThat(after.board.markerCount(p1)).isEqualTo(before.board.markerCount(p1))
        assertThat(after.board.markerCount(p2)).isEqualTo(0)
        assertThat(after.board.markerCount(p3)).isEqualTo(0)
        assertThat(after.playerState(p1).misery).isEqualTo(before.playerState(p1).misery)
    }

    @Test
    fun removedPartialHoldingsCountAsFractionsOfTheirMarketCapacity() {
        val board =
            BoardState(
                persistentMapOf(
                    ProvinceId("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(p1 to TokenHolding(coloured = 1, white = 1))
                        ),
                    ProvinceId("rome") to
                        ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))),
                )
            )
        val observation = BotObservations.from(fixture(board), p1)
        val candidate = observation.candidates.single { it.command == "play event area vii" }

        assertThat(candidate.features[BotEconomicFeature.AREA_OWN_LOSS.key]).isEqualTo(-0.5)
        assertThat(candidate.features[BotEconomicFeature.AREA_OPPONENT_LOSS.key]).isEqualTo(0.25)
        assertThat(candidate.features["misery"]).isNull()
    }

    @Test
    fun aRemovedSatelliteTokenCountsAsItsWholeOccupation() {
        val satellite =
            CatalogConfiguration(4).provinces.first {
                it.isSatellite && it.area == GeographicalArea.VII
            }
        val board = BoardState().replaceWithToken(satellite.id, p1).board
        val observation = BotObservations.from(fixture(board), p1)
        val candidate = observation.candidates.single { it.command == "play event area vii" }

        assertThat(candidate.features[BotEconomicFeature.AREA_OWN_LOSS.key]).isEqualTo(-1.0)
    }

    @Test
    fun oldModelsRetainTheSameScoresAndChoicesWithoutAreaWeights() {
        val observation = BotObservations.from(fixture(), p1)
        val original =
            observation.copy(
                candidates =
                    observation.candidates.map { candidate ->
                        candidate.copy(features = candidate.features.filterKeys { it !in areaKeys })
                    }
            )

        assertThat(observation.candidates.map { BotPolicy.score(it) })
            .containsExactlyElementsIn(original.candidates.map { BotPolicy.score(it) })
            .inOrder()
        for (difficulty in BotDifficulty.entries) {
            assertThat(BotPolicy.choose(observation, difficulty, 17))
                .isEqualTo(BotPolicy.choose(original, difficulty, 17))
        }
    }

    @Test
    fun anOpponentsHiddenCardCannotChangeAreaFeatures() {
        val state = fixture()
        fun withSecret(card: String): GameState =
            state.copy(
                players =
                    state.players +
                        (p2 to
                            state
                                .playerState(p2)
                                .copy(hand = listOf(HandCard("secret", CardData.definition(card)))))
            )

        val first = BotObservations.from(withSecret("armor"), p1)
        val second = BotObservations.from(withSecret("charlemagne"), p1)

        assertThat(first).isEqualTo(second)
    }

    private fun fixture(board: BoardState = occupiedBoard()): GameState {
        val capitals = listOf("paris", "venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            currentEpoch = Epoch.TWO,
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 100,
                                stockTokens = 30,
                                poolTokens = 4,
                                hand =
                                    if (id == p1) {
                                        listOf(
                                            HandCard("event", CardData.definition("black-death"))
                                        )
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }

    private fun occupiedBoard(): BoardState =
        listOf(
                "paris" to p1,
                "nuremberg" to p1,
                "basel" to p1,
                "venice" to p2,
                "genoa" to p3,
                "barcelona" to p4,
            )
            .fold(BoardState()) { board, (province, owner) ->
                board.placeMarker(ProvinceId(province), owner).board
            }
}
