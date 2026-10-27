package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HistoryEditsTest {
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun clean() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun regularUndoRestoresStateAndKeepsAnImmutableAudit() {
        val fixture = fixture()
        val before = fixture.stored()
        fixture.placeOne()
        val placed = fixture.stored()

        val undone = fixture.send(0, "undo")

        assertThat(fixture.stored().state()).isEqualTo(before.state())
        assertThat(undone.revision).isEqualTo(placed.revision + 1)
        assertThat(fixture.stored().revisions.take(placed.revision)).isEqualTo(placed.revisions)
        assertThat(undone.history.last()).contains("by p1: undo ")
    }

    @Test
    fun editedGameRecoversWithoutRequestingNewRandomness() {
        val fixture = fixture()
        fixture.placeOne()
        val undone = fixture.send(0, "undo")
        val recovered =
            createGameService(fixture.directory, NoNewHistoryRandomness)
                .get(fixture.game.gameId, fixture.token(0))

        assertThat(recovered).isEqualTo(undone)
    }

    @Test
    fun priorRevisionRemainsReadableWithItsOriginalState() {
        val fixture = fixture()
        val placed = fixture.placeOne()
        fixture.send(0, "undo")

        val historical =
            fixture.service.getRevision(fixture.game.gameId, fixture.token(0), placed.revision)

        assertThat(historical).isEqualTo(placed)
    }

    @Test
    fun staleUndoLeavesHistoryUntouched() {
        val fixture = fixture()
        fixture.placeOne()
        val before = fixture.stored()

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.submit(
                    fixture.game.gameId,
                    fixture.token(0),
                    before.revision - 1,
                    "undo",
                )
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
        assertThat(fixture.stored()).isEqualTo(before)
    }

    @Test
    fun regularUndoClosesWhenTheActivityEnds() {
        val fixture = fixture()
        fixture.placeOne()
        fixture.send(0, "pass")

        assertThat(fixture.view(0).undo.available).isFalse()
        assertFailsWith<ServiceFailure> { fixture.send(0, "undo") }
    }

    @Test
    fun regularUndoCannotCrossARecordedCardDraw() {
        val fixture = fixture()
        fixture.send(0, "buy-expansion-card")

        assertThat(fixture.view(0).undo.available).isFalse()
        assertFailsWith<ServiceFailure> { fixture.send(0, "undo") }
    }

    @Test
    fun competitivePolicyMakesCommittedActionsFinal() {
        val fixture = fixture(UndoPolicy.COMPETITIVE)
        fixture.placeOne()

        assertThat(fixture.view(0).undo.available).isFalse()
        assertThat(assertFailsWith<ServiceFailure> { fixture.send(0, "undo") }.code)
            .isEqualTo("HISTORY_EDIT_FORBIDDEN")
    }

    @Test
    fun friendlyGameCreatorMayUndoAnotherPlayersDeterministicAction() {
        val fixture = fixture(UndoPolicy.FRIENDLY_GAME)
        fixture.send(0, "pass")
        val next = fixture.currentActor()
        val before = fixture.stored().state()
        fixture.placeOne(next)

        fixture.send(0, "undo")

        assertThat(fixture.stored().state()).isEqualTo(before)
    }

    @Test
    fun friendlyUndoCannotEraseARecordedDraw() {
        val fixture = fixture(UndoPolicy.FRIENDLY_TURN)
        fixture.send(0, "buy-expansion-card")
        val before = fixture.stored()

        val failure = assertFailsWith<ServiceFailure> { fixture.send(0, "undo") }

        assertThat(failure.code).isEqualTo("HISTORY_RANDOMNESS_CHANGED")
        assertThat(fixture.stored()).isEqualTo(before)
    }

    @Test
    fun friendlyEditMayChangePlacementWhilePreservingTheSameRecordedDraw() {
        val fixture = fixture(UndoPolicy.FRIENDLY_TURN)
        val from = fixture.stored().effectiveEntries().size
        val first = fixture.oneTokenDestination(0)
        fixture.send(0, "place 1 $first")
        val drawAction = fixture.stored().effectiveEntries().size
        fixture.send(0, "buy-expansion-card")
        val before = fixture.stored()
        val alternate = fixture.oneTokenDestination(0, first)

        val edited = fixture.edit(0, from, listOf("p1: place 1 $alternate", "@$drawAction"))

        assertThat(edited.game.hand)
            .isEqualTo(
                fixture.service
                    .getRevision(fixture.game.gameId, fixture.token(0), before.revision)
                    .game
                    .hand
            )
        assertThat(
                fixture.stored().effectiveEntries().map(RecordedEntry::text).filter {
                    it.startsWith("!draw")
                }
            )
            .isEqualTo(
                before.effectiveEntries().map(RecordedEntry::text).filter { it.startsWith("!draw") }
            )
        assertThat(edited.game.board.any { it.id.value == alternate }).isTrue()
    }

    @Test
    fun friendlyEditCannotAddAnUnrecordedDraw() {
        val fixture = fixture(UndoPolicy.FRIENDLY_TURN)
        val from = fixture.stored().effectiveEntries().size
        fixture.placeOne()

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.edit(0, from, listOf("p1: buy-expansion-card"))
            }

        assertThat(failure.code).isEqualTo("HISTORY_RANDOMNESS_CHANGED")
    }

    @Test
    fun friendlyCreatorCannotInventAnotherPlayersAnswer() {
        val fixture = fixture(UndoPolicy.FRIENDLY_GAME)
        fixture.send(0, "pass")
        val actor = fixture.currentActor()
        val from = fixture.stored().effectiveEntries().size
        val target = fixture.oneTokenDestination(actor)
        fixture.send(actor, "place 1 $target")
        val alternate = fixture.oneTokenDestination(actor, target)

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.edit(0, from, listOf("p${actor + 1}: place 1 $alternate"))
            }

        assertThat(failure.code).isEqualTo("HISTORY_OTHER_PLAYER_ANSWER")
    }

    @Test
    fun friendlyCreatorCanRetainAnotherPlayersOriginalAnswerByReference() {
        val fixture = fixture(UndoPolicy.FRIENDLY_GAME)
        val first = fixture.oneTokenDestination(0)
        val alternate = fixture.oneTokenDestination(0, first)
        val from = fixture.stored().effectiveEntries().size
        fixture.send(0, "place 1 $first")
        val pass = fixture.stored().effectiveEntries().size
        fixture.send(0, "pass")
        val actor = fixture.currentActor()
        val target = fixture.oneTokenDestination(actor, alternate)
        val otherAnswer = fixture.stored().effectiveEntries().size
        fixture.send(actor, "place 1 $target")

        fixture.edit(0, from, listOf("p1: place 1 $alternate", "@$pass", "@$otherAnswer"))

        assertThat(fixture.stored().effectiveEntries().map(RecordedEntry::text))
            .contains("p${actor + 1}: place 1 $target")
    }

    @Test
    fun setupAndPartialSecretBatchesAreProtected() {
        val fixture = fixture(UndoPolicy.FRIENDLY_GAME)
        val entries = fixture.stored().effectiveEntries()
        val firstPurchase = entries.indexOfFirst { it.text.contains(": buy-tokens ") }

        assertThat(assertFailsWith<ServiceFailure> { fixture.edit(0, 0, emptyList()) }.code)
            .isEqualTo("HISTORY_EDIT_BOUNDARY")
        assertThat(
                assertFailsWith<ServiceFailure> { fixture.edit(0, firstPurchase + 1, emptyList()) }
                    .code
            )
            .isEqualTo("HISTORY_EDIT_BOUNDARY")
    }

    @Test
    fun invalidDependentEntryReportsItsReplacementLineWithoutPublishing() {
        val fixture = fixture(UndoPolicy.FRIENDLY_TURN)
        val from = fixture.stored().effectiveEntries().size
        fixture.placeOne()
        val before = fixture.stored()

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.edit(0, from, listOf("p1: place 1000 barcelona"))
            }

        assertThat(failure.code).isEqualTo("HISTORY_INVALID_ENTRY")
        assertThat(failure.entryIndex).isEqualTo(0)
        assertThat(fixture.stored()).isEqualTo(before)
    }

    private fun fixture(policy: UndoPolicy = UndoPolicy.REGULAR_TURN): Fixture {
        val directory = Files.createTempDirectory("aor-history-edit-")
        directories.add(directory)
        return Fixture(directory, policy)
    }

    private class Fixture(val directory: Path, policy: UndoPolicy) {
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), undoPolicy = policy)

        init {
            send(0, "start")
            for (seat in 0..2) send(seat, "bid-capital ${3 - seat}")
            for (step in 0 until 100) {
                if (view(0).game.stage == Stage.EXPANSION) break
                val snapshot = view(0)
                val actor =
                    game.links.indexOfFirst { it.id == snapshot.prompt?.players?.firstOrNull() }
                check(actor >= 0) { "Setup must expose the fixture actor at step $step." }
                val current = view(actor)
                val choices = current.legalChoices.commands
                val command =
                    when {
                        current.game.stage == Stage.TOKEN_PURCHASE -> "buy-tokens ${8 + actor}"
                        "upgrade-ships" in choices -> "upgrade-ships"
                        "stabilize cash" in choices -> "stabilize cash"
                        "pass" in choices -> "pass"
                        else -> choices.first()
                    }
                send(actor, command)
            }
            check(view(0).game.stage == Stage.EXPANSION) { "Fixture must reach expansion." }
        }

        fun token(seat: Int): String = game.links[seat].path.substringAfter("#token=")

        fun view(seat: Int): GameSnapshot = service.get(game.gameId, token(seat))

        fun stored(): StoredGame = FileGameStore(directory).read(game.gameId)

        fun currentActor(): Int = game.links.indexOfFirst { it.id == view(0).game.activity?.actor }

        fun send(seat: Int, command: String): GameSnapshot =
            service.submit(game.gameId, token(seat), view(seat).revision, command)

        fun edit(seat: Int, from: Int, lines: List<String>): GameSnapshot =
            service.editHistory(game.gameId, token(seat), view(seat).revision, from, lines)

        fun placeOne(seat: Int = 0): GameSnapshot =
            send(seat, "place 1 ${oneTokenDestination(seat)}")

        fun oneTokenDestination(seat: Int, excluded: String? = null): String =
            view(seat)
                .game
                .expansionOffers
                .first { offer ->
                    offer.destination.value != excluded &&
                        offer.primaryAction == ExpansionAction.PLACE &&
                        ((offer.requiredTokens == 1 && offer.blockers.isEmpty()) ||
                            offer.partialPlacements.any { it.tokens == 1 })
                }
                .destination
                .value
    }
}

private object NoNewHistoryRandomness : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> =
        error("History edits must never shuffle new outcomes.")

    override fun roll(count: Int): List<DieRoll> =
        error("History edits must never roll new outcomes.")
}
