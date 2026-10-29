package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class BoardStateTest {
    private val venice = PlayerId("p1")
    private val genoa = PlayerId("p2")

    @Test
    fun aProvinceCannotContainMoreTokensThanItsPrintedNumber() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardState(
                    persistentMapOf(
                        ProvinceId("florence") to
                            ProvincePieces.Tokens(
                                persistentMapOf(venice to TokenHolding(white = 5))
                            )
                    )
                )
            }

        assertThat(failure).hasMessageThat().contains("market number")
    }

    @Test
    fun satellitesCannotContainMarkers() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BoardState().placeMarker(ProvinceId("bari"), venice)
            }

        assertThat(failure).hasMessageThat().contains("satellite")
    }

    @Test
    fun placingACardMarkerReturnsAllSquareTokens() {
        val board =
            BoardState(
                persistentMapOf(
                    ProvinceId("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                venice to TokenHolding(coloured = 1, white = 1),
                                genoa to TokenHolding(white = 2),
                            )
                        )
                )
            )

        val change = board.placeMarker(ProvinceId("florence"), venice)

        assertThat(change.tokensReturned).containsExactly(venice, 2, genoa, 2)
        assertThat(change.markersRequired).containsExactly(venice, 1)
        assertThat(change.board.pieces[ProvinceId("florence")])
            .isEqualTo(ProvincePieces.Marker(venice, PieceSide.COLOURED))
    }

    @Test
    fun reducingAMarkerReturnsItToTheMatAndRequiresOneSquareToken() {
        val board = BoardState().placeMarker(ProvinceId("rome"), venice).board

        val change = board.replaceWithToken(ProvinceId("rome"), venice)

        assertThat(change.markersReturned).containsExactly(venice, 1)
        assertThat(change.tokensRequired).containsExactly(venice, 1)
        assertThat(change.board.pieces[ProvinceId("rome")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(venice to TokenHolding(coloured = 1))))
    }

    @Test
    fun cedingAMarkerReturnsTheLosersMarkerAndUsesTheWinnersMarker() {
        val board = BoardState().placeMarker(ProvinceId("rome"), venice).board

        val change = board.transferMarker(ProvinceId("rome"), genoa)

        assertThat(change.markersReturned).containsExactly(venice, 1)
        assertThat(change.markersRequired).containsExactly(genoa, 1)
        assertThat(change.board.markerCount(genoa)).isEqualTo(1)
    }

    @Test
    fun overseasMarkersCountTowardAllTheirCommodities() {
        val board = BoardState().placeMarker(ProvinceId("south-america"), venice).board

        val counts =
            Commodity.entries
                .associateWith { commodity ->
                    board.commodityCount(venice, commodity)
                }
                .filterValues { count -> count > 0 }

        assertThat(counts)
            .containsExactly(Commodity.METAL, 1, Commodity.SPICE, 1, Commodity.GOLD, 1)
    }

    @Test
    fun aPartialColouredPresenceIsABaseButDoesNotControlItsProvince() {
        val board =
            BoardState(
                persistentMapOf(
                    ProvinceId("rome") to
                        ProvincePieces.Tokens(persistentMapOf(venice to TokenHolding(coloured = 1)))
                )
            )

        assertThat(board.colouredPresence(venice)).containsExactly(ProvinceId("rome"))
        assertThat(board.controller(ProvinceId("rome"))).isNull()
    }

    @Test
    fun convertedMarkerKeepsReachWithoutBecomingColouredCaravanControl() {
        val rome = ProvinceId("rome")
        val board = BoardState().replaceWithToken(rome, venice).board

        val dominated = board.placeMarker(rome, venice, PieceSide.WHITE).board

        assertThat(dominated.colouredPresence(venice)).containsExactly(rome)
        assertThat(dominated.controller(rome)).isNull()
    }

    @Test
    fun removingAConvertedMarkerDoesNotRememberItsBaseUponReoccupation() {
        val rome = ProvinceId("rome")
        val board =
            BoardState()
                .replaceWithToken(rome, venice)
                .board
                .placeMarker(rome, venice, PieceSide.WHITE)
                .board

        val reoccupied = board.remove(rome).board.placeMarker(rome, venice, PieceSide.WHITE).board

        assertThat(reoccupied.colouredPresence(venice)).isEmpty()
    }

    @Test
    fun incomeMakesAConvertedMarkerAnOrdinaryColouredBase() {
        val rome = ProvinceId("rome")
        val board =
            BoardState()
                .replaceWithToken(rome, venice)
                .board
                .placeMarker(rome, venice, PieceSide.WHITE)
                .board

        val flipped = board.flipWhitePieces()

        assertThat(flipped.pieces[rome])
            .isEqualTo(ProvincePieces.Marker(venice, PieceSide.COLOURED))
        assertThat(flipped.colouredPresence(venice)).containsExactly(rome)
    }

    @Test
    fun colouredMarkerCannotRetainRedundantConversionProvenance() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProvincePieces.Marker(venice, PieceSide.COLOURED, retainsColouredBase = true)
            }

        assertThat(failure).hasMessageThat().contains("white marker")
    }

    @Test
    fun incomeFlipsAllWhitePiecesWithoutChangingPhysicalPieceCounts() {
        val board =
            BoardState(
                persistentMapOf(
                    ProvinceId("venice") to ProvincePieces.Marker(venice, PieceSide.WHITE),
                    ProvinceId("rome") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                venice to TokenHolding(coloured = 1, white = 2),
                                genoa to TokenHolding(white = 1),
                            )
                        ),
                )
            )

        val flipped = board.flipWhitePieces()

        assertThat(flipped.pieces[ProvinceId("venice")])
            .isEqualTo(ProvincePieces.Marker(venice, PieceSide.COLOURED))
        assertThat(flipped.pieces[ProvinceId("rome")])
            .isEqualTo(
                ProvincePieces.Tokens(
                    persistentMapOf(
                        venice to TokenHolding(coloured = 3),
                        genoa to TokenHolding(coloured = 1),
                    )
                )
            )
        assertThat(board.colouredPresence(genoa)).isEmpty()
    }
}
