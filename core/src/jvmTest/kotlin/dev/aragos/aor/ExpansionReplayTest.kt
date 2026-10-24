package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionReplayTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun bothEventRebuildPoliciesConserveTokensWhileOnlyIncludeDrawsTheBonus() {
        val results =
            RebuiltMarkerBonus.entries.associateWith { policy ->
                val marker = BoardState().placeMarker(id("venice"), p1).board
                val initial = game(marker, pool = 4).copy(rebuiltMarkerBonus = policy)
                val reduced =
                    initial
                        .withBoardChange(marker.replaceWithToken(id("venice"), p1))
                        .copy(flow = initial.flow.copy(eventReduced = setOf(id("venice"))))
                val ended =
                    reduced.play(
                        "p1: place 4 venice",
                        "p1: pass",
                        "p2: pass",
                        "p3: pass",
                    )
                val rewarded =
                    if (policy == RebuiltMarkerBonus.INCLUDE) {
                        ended.play("!draw p1 hand-4 ${ended.deck.first()}")
                    } else ended
                val owner = rewarded.playerState(p1)
                listOf(
                    owner.stockTokens,
                    owner.poolTokens,
                    rewarded.board.tokenCount(p1),
                    rewarded.board.markerCount(p1),
                    owner.hand.size,
                )
            }

        assertThat(results)
            .containsExactly(
                RebuiltMarkerBonus.EXCLUDE,
                listOf(36, 0, 0, 1, 1),
                RebuiltMarkerBonus.INCLUDE,
                listOf(36, 0, 0, 1, 2),
            )
    }

    @Test
    fun voluntaryRebuildWithIncludeDoesNotDrawABonusAndConservesTokens() {
        val state =
            game(BoardState().placeMarker(id("venice"), p1).board, pool = 4)
                .copy(rebuiltMarkerBonus = RebuiltMarkerBonus.INCLUDE)

        val ended =
            state.play(
                "p1: reduce-marker venice",
                "p1: place 4 venice",
                "p1: pass",
                "p2: pass",
                "p3: pass",
            )

        assertThat(ended.playerState(p1).stockTokens).isEqualTo(36)
        assertThat(ended.playerState(p1).poolTokens).isEqualTo(0)
        assertThat(ended.playerState(p1).hand).hasSize(1)
        assertThat(ended.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.SHORTAGE, 3)))
    }

    @Test
    fun aRecordedFailedShipmentPreventsASecondOverCapacityAttack() {
        val initial =
            game(BoardState().placeMarker(id("rome"), p2).board).updatePlayer(p1) { owner ->
                owner.copy(
                    ships = ShippingCapability.SEAWORTHY_10,
                    advances = ownership(AdvanceId.SEAWORTHY_VESSELS),
                )
            }
        val failed = initial.play("p1: attack rome", "!roll competition 6 1 1")

        val failure = assertFailsWith<RuleViolation> { failed.play("p1: attack rome") }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(failed.flow.shipped[p1]).containsExactly(id("rome"), 8)
        assertThat(physicalPieces(failed, p1)).isEqualTo(36)
    }

    @Test
    fun aDefenderCannotInvokeCathedralAgainAgainstTheSameAttackerThisTurn() {
        val state =
            game(BoardState().placeMarker(id("florence"), p2).board).updatePlayer(p2) { owner ->
                owner.copy(advances = ownership(AdvanceId.CATHEDRAL))
            }

        val repeated = state.play("p1: attack florence", "p2: cathedral", "p1: attack florence")

        assertThat(repeated.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.COMPETITION, 3)))
        assertThat(repeated.flow.cathedralUses).containsExactly(p2 to p1)
    }

    @Test
    fun privateWindmillExchangeReplaysWithStableCardReferencesAndConservesPhysicalTokens() {
        val initial =
            game(BoardState().placeMarker(id("florence"), p2).board).updatePlayer(p1) { owner ->
                owner.copy(advances = ownership(AdvanceId.WIND_WATERMILL))
            }
        val log =
            arrayOf(
                "p1: attack florence",
                "!roll competition 1 6 6",
                "p1: windmill",
                "!roll windmill 4",
                "p1: offer-card hand-1",
                "!trade p2 p1 hand-2",
            )

        val exchanged = initial.play(*log)
        val replayed = initial.play(*log)

        assertThat(replayed).isEqualTo(exchanged)
        assertThat(exchanged.playerState(p1).hand.map(HandCard::reference))
            .containsExactly("hand-2")
        assertThat(exchanged.playerState(p2).hand.map(HandCard::reference))
            .containsExactly("hand-1")
        assertThat(exchanged.viewForPlayer(p3).toString()).doesNotContain("hand-1")
        assertThat(exchanged.viewForPlayer(p3).toString()).doesNotContain("hand-2")
        assertThat(exchanged.playerIds.map { player -> physicalPieces(exchanged, player) })
            .containsExactly(36, 36, 36)
    }

    @Test
    fun phaseOpeningIndulgenceSkipsNeutralPlayersAndCleansNewChaosPlayers() {
        val initial =
            game(pool = 0)
                .updatePlayer(p1) { owner ->
                    owner.copy(advances = ownership(AdvanceId.HOLY_INDULGENCE))
                }
                .updatePlayer(p3) { owner ->
                    owner.copy(
                        poolTokens = 1,
                        stockTokens = 35,
                        cash = 0,
                        misery = MiseryStep.THOUSAND,
                    )
                }
        val neutral = initial.replacePlayer(initial.playerState(p2).copy(misery = MiseryStep.CHAOS))

        val opened = neutral.copy(flow = TurnFlow()).normalise()

        assertThat(opened.playerState(p1).poolTokens).isEqualTo(2)
        assertThat(opened.playerState(p2).cash).isEqualTo(40)
        assertThat(opened.playerState(p3).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(opened.playerState(p3).hand).isEmpty()
        assertThat(opened.playerIds.map { player -> physicalPieces(opened, player) })
            .containsExactly(36, 36, 36)
    }

    @Test
    fun neutralDefendersKeepNationalismAndIgnoreCathedralAndProselytism() {
        val initial =
            game(BoardState().placeMarker(id("florence"), p2).board)
                .updatePlayer(p1) { owner ->
                    owner.copy(
                        advances =
                            ownership(
                                AdvanceId.PROSELYTISM,
                                AdvanceId.NATIONALISM,
                            )
                    )
                }
                .updatePlayer(p2) { owner ->
                    owner.copy(
                        advances =
                            ownership(
                                AdvanceId.NATIONALISM,
                                AdvanceId.CATHEDRAL,
                            )
                    )
                }
        val neutral = initial.replacePlayer(initial.playerState(p2).copy(misery = MiseryStep.CHAOS))

        val lost = neutral.play("p1: attack florence", "!roll competition 6 1 2")

        assertThat(lost.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(p2, PieceSide.COLOURED))
        assertThat(lost.playerState(p1).poolTokens).isEqualTo(11)
        assertThat(lost.flow.cathedralUses).isEmpty()
        assertThat(physicalPieces(lost, p1)).isEqualTo(36)
    }

    private fun game(board: BoardState = BoardState(), pool: Int = 20): GameState {
        val ids = listOf(p1, p2, p3)
        val players = ids.map { player -> Player(player, player.value) }
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            deck = CardData.epochOne.drop(3).map(CardDefinition::id),
            deckHistory = DeckHistory(nextDraw = 4),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
            players =
                players
                    .mapIndexed { index, player ->
                        val tokens = if (player.id == p1) pool else 0
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = tokens,
                                stockTokens = 36 - tokens - board.tokenCount(player.id),
                                hand =
                                    listOf(HandCard("hand-${index + 1}", CardData.epochOne[index])),
                            )
                    }
                    .toMap(),
        )
    }

    private fun ownership(vararg advances: AdvanceId): PlayerAdvances =
        PlayerAdvances(advances.map { advance -> AdvanceAcquisition(advance, turnNumber = 1) })

    private fun physicalPieces(state: GameState, player: PlayerId): Int =
        state.playerState(player).let { owner ->
            owner.stockTokens + owner.poolTokens + state.board.tokenCount(player)
        }

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line -> state.step(TextCodec.parse(line)) }

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
