package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GameAbandonmentTest(private val postgres: Boolean) {
    private val sql = mutableListOf<PostgresFixture>()
    private val directories = mutableListOf<Path>()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}")
        fun backends(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @AfterTest
    fun clean() {
        sql.forEach(PostgresFixture::close)
        directories.forEach { it.toFile().deleteRecursively() }
    }

    private fun fixture(seats: Int = 3, policy: UndoPolicy = UndoPolicy.REGULAR_TURN): Fixture {
        val directory = Files.createTempDirectory("aor-abandonment-").also(directories::add)
        val store =
            if (postgres) PostgresFixture().also(sql::add).store else FileGameStore(directory)
        return Fixture(store, seats, policy)
    }

    @Test
    fun unanimousConsentEndsTheGameWithoutChangingEngineHistory() {
        val fixture = fixture(6)
        val source = fixture.store.read(fixture.game.gameId)
        fixture.vote(0, AbandonmentOperation.PROPOSE)
        (1..4).forEach { fixture.vote(it, AbandonmentOperation.AGREE) }
        assertThat(fixture.snapshot(5).status).isEqualTo(GameStatus.ACTIVE)

        val ended = fixture.vote(5, AbandonmentOperation.AGREE)

        assertThat(ended.status).isEqualTo(GameStatus.ABANDONED)
        assertThat(ended.game.stage).isNotEqualTo(Stage.FINISHED)
        assertThat(ended.prompt).isNull()
        assertThat(ended.legalChoices.commands).isEmpty()
        assertThat(ended.abandonment).isNull()
        val stored = fixture.store.read(source.id)
        assertThat(stored.effectiveEntries()).isEqualTo(source.effectiveEntries())
        assertThat(stored.state()).isEqualTo(source.state())
        assertThat(stored.revisions.last().author).isEqualTo(fixture.game.links[5].id)
        assertThat(GameDocuments.read(stored.id, fixture.store.document(stored.id)))
            .isEqualTo(stored)
        assertThat(
                assertFailsWith<ServiceFailure> {
                        fixture.service.submit(
                            source.id,
                            fixture.token(0),
                            ended.revision,
                            "bid-capital 1",
                        )
                    }
                    .code
            )
            .isEqualTo("GAME_INACTIVE")
    }

    @Test
    fun nonfinalConsentPreservesPrivateSubmissionAndPlay() {
        val fixture = fixture()
        fixture.service.submit(
            fixture.game.gameId,
            fixture.token(0),
            fixture.snapshot(0).revision,
            "bid-capital 3",
        )
        val source = fixture.store.read(fixture.game.gameId)

        fixture.vote(1, AbandonmentOperation.PROPOSE)

        val current = fixture.store.read(source.id)
        assertThat(current.pendingBids).isEqualTo(source.pendingBids)
        assertThat(current.submissionVersions).isEqualTo(source.submissionVersions)
        assertThat(current.effectiveEntries()).isEqualTo(source.effectiveEntries())
        fixture.service.submit(source.id, fixture.token(2), current.revision, "bid-capital 1")
        assertThat(fixture.snapshot(0).abandonment?.agreed)
            .containsExactly(fixture.game.links[1].id)
    }

    @Test
    fun finalConsentClearsUnrevealedAmountsAndRotatesEverySubmissionSlot() {
        val fixture = fixture()
        fixture.service.submit(
            fixture.game.gameId,
            fixture.token(0),
            fixture.snapshot(0).revision,
            "bid-capital 3",
        )
        fixture.vote(0, AbandonmentOperation.PROPOSE)
        fixture.vote(1, AbandonmentOperation.AGREE)
        val source = fixture.store.read(fixture.game.gameId)

        fixture.vote(2, AbandonmentOperation.AGREE)

        val ended = fixture.store.read(source.id)
        assertThat(ended.pendingBids).isEmpty()
        assertThat(ended.submissionVersions.keys)
            .containsExactlyElementsIn(source.header.players.map(Player::id))
        assertThat(ended.submissionVersions[source.header.creatorId])
            .isNotEqualTo(source.submissionVersions[source.header.creatorId])
    }

    @Test
    fun declineClosesTheRequestAndNewProposalRequiresFreshConsent() {
        val fixture = fixture()
        fixture.vote(0, AbandonmentOperation.PROPOSE)
        fixture.vote(1, AbandonmentOperation.AGREE)
        fixture.vote(2, AbandonmentOperation.DECLINE)

        val fresh = fixture.vote(2, AbandonmentOperation.PROPOSE)

        assertThat(fresh.abandonment?.agreed).containsExactly(fixture.game.links[2].id)
        assertThat(fresh.status).isEqualTo(GameStatus.ACTIVE)
    }

    @Test
    fun withdrawalClosesTheEntireRequest() {
        val fixture = fixture()
        fixture.vote(0, AbandonmentOperation.PROPOSE)
        fixture.vote(1, AbandonmentOperation.AGREE)

        val closed = fixture.vote(1, AbandonmentOperation.WITHDRAW)

        assertThat(closed.abandonment).isNull()
        assertThat(closed.status).isEqualTo(GameStatus.ACTIVE)
    }

    @Test
    fun retryReconcilesAnExactReceiptWithoutAnotherRevision() {
        val fixture = fixture()
        val revision = fixture.snapshot(0).revision
        val operation = UUID.randomUUID().toString()
        val first =
            fixture.service.abandonment(
                fixture.game.gameId,
                GameAccess.SeatToken(fixture.token(0)),
                revision,
                operation,
                AbandonmentOperation.PROPOSE,
                null,
            )

        val retried =
            fixture.service.abandonment(
                fixture.game.gameId,
                GameAccess.SeatToken(fixture.token(0)),
                revision,
                operation,
                AbandonmentOperation.PROPOSE,
                null,
            )

        assertThat(retried).isEqualTo(first)
        assertThat(
                assertFailsWith<ServiceFailure> {
                        fixture.service.abandonment(
                            fixture.game.gameId,
                            GameAccess.SeatToken(fixture.token(1)),
                            revision,
                            operation,
                            AbandonmentOperation.PROPOSE,
                            null,
                        )
                    }
                    .code
            )
            .isEqualTo("OPERATION_ID_REUSED")
    }

    @Test
    fun oldRequestCannotSupplyConsentForANewProposal() {
        val fixture = fixture()
        val old = fixture.vote(0, AbandonmentOperation.PROPOSE).abandonment!!.requestId
        fixture.vote(1, AbandonmentOperation.DECLINE)
        fixture.vote(0, AbandonmentOperation.PROPOSE)

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.abandonment(
                    fixture.game.gameId,
                    GameAccess.SeatToken(fixture.token(1)),
                    fixture.snapshot(1).revision,
                    UUID.randomUUID().toString(),
                    AbandonmentOperation.AGREE,
                    old.toString(),
                )
            }

        assertThat(failure.code).isEqualTo("ABANDONMENT_REQUEST_CLOSED")
    }

    @Test
    fun historicalViewsAndRestartRetainOnlyTheConsentThenRecorded() {
        val fixture = fixture()
        val proposed = fixture.vote(0, AbandonmentOperation.PROPOSE)
        fixture.vote(1, AbandonmentOperation.AGREE)

        assertThat(
                fixture.service
                    .getRevision(fixture.game.gameId, fixture.token(0), proposed.revision)
                    .abandonment
            )
            .isEqualTo(proposed.abandonment)
        val reloaded =
            GameDocuments.read(fixture.game.gameId, fixture.store.document(fixture.game.gameId))
        assertThat(GameAbandonment.open(reloaded)?.agreed).hasSize(2)
    }

    @Test
    fun administrativeCheckpointClosesConsentAndRetainsItsRetryReceiptInTheArchive() {
        val fixture = fixture()
        val revision = fixture.snapshot(0).revision
        val operationId = UUID.randomUUID().toString()
        fixture.service.abandonment(
            fixture.game.gameId,
            GameAccess.SeatToken(fixture.token(0)),
            revision,
            operationId,
            AbandonmentOperation.PROPOSE,
            null,
        )
        val admin =
            GameAdministration(
                fixture.store,
                FileAdministrationAudit(
                    Files.createTempDirectory("aor-abandonment-audit-").also(directories::add)
                ),
            )
        val log = admin.log(fixture.game.gameId)

        admin.replaceLog(fixture.game.gameId, AccountId("admin"), log.revision, log.entries)

        val current = fixture.snapshot(0)
        assertThat(current.abandonment).isNull()
        assertThat(fixture.store.read(fixture.game.gameId).revisions.last().abandonmentCheckpoint)
            .isNotNull()
        val retried =
            fixture.service.abandonment(
                fixture.game.gameId,
                GameAccess.SeatToken(fixture.token(0)),
                revision,
                operationId,
                AbandonmentOperation.PROPOSE,
                null,
            )
        assertThat(retried.revision).isEqualTo(current.revision)
        assertThat(retried.abandonment).isNull()
    }

    @Test
    fun finalAgreementAndWithdrawalRacePublishesOnlyOneTransition() {
        val fixture = fixture()
        fixture.vote(0, AbandonmentOperation.PROPOSE)
        val before = fixture.vote(1, AbandonmentOperation.AGREE)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val attempts =
                listOf(2 to AbandonmentOperation.AGREE, 1 to AbandonmentOperation.WITHDRAW).map {
                    (seat, operation) ->
                    executor.submit<Boolean> {
                        start.await()
                        runCatching {
                            fixture.service.abandonment(
                                fixture.game.gameId,
                                GameAccess.SeatToken(fixture.token(seat)),
                                before.revision,
                                UUID.randomUUID().toString(),
                                operation,
                                before.abandonment!!.requestId.toString(),
                            )
                        }
                            .isSuccess
                    }
                }
            start.countDown()

            val results = attempts.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }

            assertThat(results.count { it }).isEqualTo(1)
            assertThat(fixture.snapshot(0).revision).isEqualTo(before.revision + 1)
            assertThat(fixture.snapshot(0).abandonment).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun friendlyUpgradePreservesUnrevealedAmountsAndPrivateReceipts() {
        val fixture = fixture(policy = UndoPolicy.FRIENDLY_GAME)
        fixture.service.submit(
            fixture.game.gameId,
            GameAccess.SeatToken(fixture.token(0)),
            fixture.snapshot(0).revision,
            "bid-capital 7",
            operationId = UUID.randomUUID().toString(),
        )
        val source = fixture.store.read(fixture.game.gameId)

        LinearGameMigration.run(fixture.store)

        val upgraded = fixture.store.read(source.id)
        assertThat(upgraded.copy(revisions = source.revisions)).isEqualTo(source)
        assertThat(upgraded.undoPolicy).isEqualTo(UndoPolicy.LINEAR_GAME)
        assertThat(fixture.snapshot(0).pendingBid).isEqualTo(7)
        assertThat(
                fixture.service
                    .getRevision(source.id, fixture.token(0), source.revision)
                    .undo
                    .policy
            )
            .isEqualTo(UndoPolicy.FRIENDLY_GAME)
    }

    @Test
    fun friendlyUpgradeKeepsClosedGamesClosed() {
        val fixture = fixture(policy = UndoPolicy.FRIENDLY_GAME)
        val audit =
            FileAdministrationAudit(
                Files.createTempDirectory("aor-migration-audit-").also(directories::add)
            )
        GameAdministration(fixture.store, audit)
            .changeStatus(
                fixture.game.gameId,
                AccountId("admin"),
                fixture.snapshot(0).revision,
                AdministrativeAction.CANCEL,
            )
        val source = fixture.store.read(fixture.game.gameId)

        LinearGameMigration.run(fixture.store)

        val upgraded = fixture.store.read(source.id)
        assertThat(upgraded.copy(revisions = source.revisions)).isEqualTo(source)
        assertThat(upgraded.status).isEqualTo(GameStatus.CANCELLED)
        assertThat(fixture.snapshot(0).undo.capability).isEqualTo(HistoryCapability.NONE)
    }

    @Test
    fun consentAttentionDoesNotBecomeAnEngineTurnInTheDirectory() {
        val fixture = fixture()
        val accounts =
            if (fixture.store is PostgresGameStore) PostgresAccountStore(fixture.store.database)
            else
                FileAccountStore(
                    Files.createTempDirectory("aor-consent-accounts-")
                        .also(directories::add)
                        .resolve("accounts.json")
                )
        val players = listOf("alice", "bobby", "carol").map { accounts.register(AccountId(it), it) }
        val game =
            fixture.service.createRegistered(
                players,
                players[0].id,
                RuleOptions(),
                UndoPolicy.REGULAR_TURN,
            )
        val creator = GameAccess.Account(players[0].id)
        val source = fixture.service.get(game.gameId, creator)
        fixture.service.submit(game.gameId, creator, source.revision, "bid-capital 3")
        fixture.service.abandonment(
            game.gameId,
            GameAccess.Account(players[1].id),
            source.revision,
            UUID.randomUUID().toString(),
            AbandonmentOperation.PROPOSE,
            null,
        )

        val summary =
            fixture.store
                .listGames(players[0].id, GameListScope.OWN, GameListStatus.MY_MOVE, null, 20)
                .games
                .single()

        assertThat(summary.myMove).isTrue()
        assertThat(summary.consentResponse).isTrue()
        assertThat(summary.turnRequired).isFalse()
        assertThat(summary.actionPlayers).doesNotContain(source.viewerId)
        assertThat(summary.consentResponders).contains(source.viewerId)
    }

    @Test
    fun repeatedAgreementReturnsCurrentStateWithoutAcceptingAnotherOperation() {
        val fixture = fixture()
        val proposed = fixture.vote(0, AbandonmentOperation.PROPOSE)
        val operationId = UUID.randomUUID().toString()

        val repeated =
            fixture.service.abandonment(
                fixture.game.gameId,
                GameAccess.SeatToken(fixture.token(0)),
                proposed.revision,
                operationId,
                AbandonmentOperation.AGREE,
                proposed.abandonment!!.requestId.toString(),
            )

        assertThat(repeated.revision).isEqualTo(proposed.revision)
        assertThat(repeated.abandonment).isEqualTo(proposed.abandonment)
        assertThat(repeated.abandonmentResult).isEqualTo(AbandonmentWriteResult.ALREADY_AGREED)
        assertThat(repeated.abandonmentReceipt).isNull()
        assertThat(repeated.abandonmentReceipts).doesNotContain(operationId)
    }

    private class Fixture(val store: ManagedGameStore, seats: Int, policy: UndoPolicy) {
        val service = GameService(store, GameDriver(FixedRandom()))
        val game = service.create((1..seats).map { "Player $it" }, undoPolicy = policy)

        fun token(seat: Int) = game.links[seat].path.substringAfter("#token=")

        fun snapshot(seat: Int) = service.get(game.gameId, token(seat))

        fun vote(seat: Int, operation: AbandonmentOperation): GameSnapshot {
            val before = snapshot(seat)
            return service.abandonment(
                game.gameId,
                GameAccess.SeatToken(token(seat)),
                before.revision,
                UUID.randomUUID().toString(),
                operation,
                if (operation == AbandonmentOperation.PROPOSE) null
                else before.abandonment!!.requestId.toString(),
            )
        }
    }
}
