package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HistoryEditsTest(private val postgres: Boolean) {
    private val sql = mutableListOf<PostgresFixture>()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}")
        fun backends(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    private val directories = mutableListOf<Path>()

    @AfterTest
    fun clean() {
        sql.forEach(PostgresFixture::close)
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
        val recovered = fixture.reopened().get(fixture.game.gameId, fixture.token(0))

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

    @Test
    fun linearRewindRemovesDrawWithoutNewRandomnessAndResumesFromRestoredDeck() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val before = fixture.stored()
        val drawn = fixture.send(0, "buy-expansion-card")
        val card = drawn.game.hand.last()

        val undone = fixture.send(0, "undo")

        assertThat(fixture.stored().effectiveEntries()).isEqualTo(before.effectiveEntries())
        assertThat(fixture.stored().state()).isEqualTo(before.state())
        assertThat(fixture.reopened().get(fixture.game.gameId, fixture.token(0))).isEqualTo(undone)
        assertThat(fixture.send(0, "buy-expansion-card").game.hand.last().definition)
            .isEqualTo(card.definition)
    }

    @Test
    fun linearRepeatedUndoWalksTheEffectivePrefixAcrossAuditRevisions() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val before = fixture.stored()
        fixture.placeOne()
        fixture.send(0, "buy-expansion-card")
        fixture.send(0, "undo")

        fixture.send(0, "undo")

        assertThat(fixture.stored().effectiveEntries()).isEqualTo(before.effectiveEntries())
    }

    @Test
    fun linearCreatorCanRewindOtherActivitiesButOtherPlayersCannot() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val before = fixture.stored()
        fixture.send(0, "pass")
        val actor = fixture.currentActor()
        fixture.placeOne(actor)

        assertThat(
                assertFailsWith<ServiceFailure> {
                        fixture.send(actor, "undo ${before.effectiveEntries().size}")
                    }
                    .code
            )
            .isEqualTo("HISTORY_EDIT_BOUNDARY")
        fixture.send(0, "undo ${before.effectiveEntries().size}")

        assertThat(fixture.stored().state()).isEqualTo(before.state())
    }

    @Test
    fun linearRejectsSuffixEndpointAndTamperedStoredReplacement() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val from = fixture.stored().effectiveEntries().size
        fixture.placeOne()
        val source = fixture.stored()

        assertThat(assertFailsWith<ServiceFailure> { fixture.edit(0, from, emptyList()) }.code)
            .isEqualTo("HISTORY_SUFFIX_UNSUPPORTED")
        val undone = HistoryEdits.undo(source, source.header.creatorId, from)
        val tampered =
            undone.copy(
                revisions =
                    undone.revisions.dropLast(1) +
                        undone.revisions.last().copy(command = "edit-history $from")
            )
        assertFailsWith<IllegalArgumentException> { GameDocuments.validate(tampered) }
    }

    @Test
    fun linearTargetsExcludeSetupSystemOutcomesAndPartialBatches() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val source = fixture.stored()
        val targets = LinearHistory.targets(source, source.header.creatorId)
        val partial =
            source.effectiveEntries().indexOfFirst { it.text.contains(": buy-tokens ") } + 1

        assertThat(targets.map(RewindTarget::entryCount)).doesNotContain(partial)
        assertThat(assertFailsWith<ServiceFailure> { fixture.send(0, "undo $partial") }.code)
            .isEqualTo("HISTORY_EDIT_BOUNDARY")
        assertThat(assertFailsWith<ServiceFailure> { fixture.send(0, "undo 0") }.code)
            .isEqualTo("HISTORY_EDIT_BOUNDARY")
    }

    @Test
    fun linearValidationAcceptsExactlyTheAdvertisedTargetsForEachPlayer() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        fixture.placeOne()
        fixture.send(0, "buy-expansion-card")
        val source = fixture.stored()
        val entryCount = source.effectiveEntries().size

        source.header.players.forEach { player ->
            val targets = LinearHistory.targets(source, player.id).map(RewindTarget::entryCount)
            val accepted =
                (-1..entryCount + 1).filter { from ->
                    runCatching { LinearHistory.validate(source, player.id, from) }.isSuccess
                }

            assertThat(accepted).containsExactlyElementsIn(targets)
        }
    }

    @Test
    fun linearStoredRewindRejectsPartialSimultaneousBatches() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val source = fixture.stored()
        val partial =
            source.effectiveEntries().indexOfFirst { it.text.contains(": buy-tokens ") } + 1
        val tampered =
            source.copy(
                revisions =
                    source.revisions +
                        Revision(
                            id = source.revision + 1,
                            parent = source.revision,
                            author = source.header.creatorId,
                            command = "undo $partial",
                            entries = emptyList(),
                            replaceFrom = partial,
                        )
            )

        val failure = assertFailsWith<ServiceFailure> { GameDocuments.validate(tampered) }

        assertThat(failure.code).isEqualTo("HISTORY_EDIT_BOUNDARY")
    }

    @Test
    fun repeatedStoredRewindsReplayAndContinueAfterReopening() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val initial = fixture.stored()
        repeat(8) {
            fixture.placeOne()
            fixture.send(0, "undo")
        }
        val source = fixture.stored()
        val reopened = fixture.reopened()
        val before = reopened.get(source.id, fixture.token(0))

        val continued = reopened.submit(source.id, fixture.token(0), source.revision, "pass")

        assertThat(before.game)
            .isEqualTo(
                fixture.service.getRevision(source.id, fixture.token(0), initial.revision).game
            )
        val after = fixture.stored()
        assertThat(after.revisions.dropLast(1)).isEqualTo(source.revisions)
        assertThat(after.revision).isEqualTo(source.revision + 1)
        assertThat(fixture.reopened().get(source.id, fixture.token(0))).isEqualTo(continued)
    }

    @Test
    fun friendlyMigrationPreservesEarlierSuffixEditsPrivateStateAndExactArchive() {
        val fixture = fixture(UndoPolicy.FRIENDLY_GAME)
        fixture.placeOne()
        fixture.send(0, "undo")
        val source = fixture.stored()
        val contents = fixture.store.document(source.id)

        assertThat(LinearGameMigration.run(fixture.store)).containsExactly(source.id)
        val upgraded = fixture.stored()

        assertThat(upgraded.undoPolicy).isEqualTo(UndoPolicy.LINEAR_GAME)
        assertThat(upgraded.revisions.dropLast(1)).isEqualTo(source.revisions)
        assertThat(upgraded.effectiveEntries()).isEqualTo(source.effectiveEntries())
        assertThat(upgraded.state()).isEqualTo(source.state())
        assertThat(upgraded.copy(revisions = source.revisions)).isEqualTo(source)
        assertThat(
                json.readTree(
                    fixture.store.archivedDocument(
                        source.id,
                        upgraded.revisions.last().storageArchive!!,
                    )
                )
            )
            .isEqualTo(json.readTree(contents))
        assertThat(LinearGameMigration.run(fixture.store)).isEmpty()
        fixture.send(0, "buy-expansion-card")
        fixture.send(0, "undo")
        assertThat(fixture.reopened().get(source.id, fixture.token(0)).game)
            .isEqualTo(fixture.view(0).game)
    }

    @Test
    fun acceptedRewindClearsConsentAndEverySubmissionSlotAtomically() {
        val fixture = fixture(UndoPolicy.LINEAR_GAME)
        val source = fixture.stored()
        fixture.service.abandonment(
            source.id,
            GameAccess.SeatToken(fixture.token(1)),
            source.revision,
            java.util.UUID.randomUUID().toString(),
            AbandonmentOperation.PROPOSE,
            null,
        )
        fixture.placeOne()

        fixture.send(0, "undo")

        val rewound = fixture.stored()
        assertThat(GameAbandonment.open(rewound)).isNull()
        assertThat(rewound.revisions.last().abandonment?.closure)
            .isEqualTo(AbandonmentClosure.HISTORY_CHANGED)
        assertThat(rewound.submissionVersions.keys)
            .containsExactlyElementsIn(source.header.players.map(Player::id))
        assertThat(rewound.effectiveEntries()).isEqualTo(source.effectiveEntries())
        GameDocuments.validate(rewound)
    }

    private fun fixture(policy: UndoPolicy = UndoPolicy.REGULAR_TURN): Fixture {
        val directory = Files.createTempDirectory("aor-history-edit-")
        directories.add(directory)
        val database = if (postgres) PostgresFixture().also(sql::add) else null
        return Fixture(directory, policy, database)
    }

    private class Fixture(
        val directory: Path,
        policy: UndoPolicy,
        private val database: PostgresFixture?,
    ) {
        val store: ManagedGameStore = database?.store ?: FileGameStore(directory)
        val service = GameService(store, GameDriver(FixedRandom()))

        fun reopened(): GameService =
            database?.reopened(NoNewHistoryRandomness)
                ?: createGameService(directory, NoNewHistoryRandomness)

        val game = service.create(listOf("Alice", "Bob", "Carol"), undoPolicy = policy)

        init {
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

        fun stored(): StoredGame = store.read(game.gameId)

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
