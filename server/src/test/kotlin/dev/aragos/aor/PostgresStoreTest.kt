package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PostgresStoreTest {
    @Test
    fun cachedReadersObservePrivateReplacementsAtAnUnchangedPublicRevision() =
        PostgresFixture().use { fixture ->
            val writer = fixture.service()
            val game = writer.create(listOf("Alice", "Bob", "Carol"))
            writer.submit(game.gameId, token(game, 0), 2, "bid-capital 12")
            val reader = fixture.reopened(NoPostgresRandom)
            assertThat(reader.get(game.gameId, token(game, 0)).pendingBid).isEqualTo(12)

            writer.submit(game.gameId, token(game, 0), 2, "bid-capital 17")
            val updated = reader.get(game.gameId, token(game, 0))

            assertThat(updated.pendingBid).isEqualTo(17)
            assertThat(updated.revision).isEqualTo(2)
            assertThat(reader.get(game.gameId, token(game, 1)).pendingBid).isNull()
        }

    @Test
    fun aDatabaseFailureDuringCheckpointRollsBackArchivesAndRevisions() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            val started = service.get(game.gameId, token(game, 0))
            val before = fixture.store.document(game.gameId)
            val entries = fixture.administration.log(game.gameId).entries
            fixture.database.write { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "ALTER TABLE games ADD CONSTRAINT injected_failure CHECK (revision < 3)"
                    )
                }
            }

            val failure =
                assertFailsWith<ServiceFailure> {
                    fixture.administration.replaceLog(game.gameId, AccountId("admin"), 2, entries)
                }

            assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
            assertThat(fixture.store.document(game.gameId)).isEqualTo(before)
            assertThat(fixture.store.archives(game.gameId)).isEmpty()
            assertThat(fixture.reopened(NoPostgresRandom).get(game.gameId, token(game, 0)))
                .isEqualTo(started)
        }

    @Test
    fun gameAndPrivateSubmissionSurviveReopeningWithoutNewRandomness() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            val token = game.links[0].path.substringAfter("token=")
            val submitted = service.submit(game.gameId, token, 2, "bid-capital 12")

            val recovered = fixture.reopened(NoPostgresRandom).get(game.gameId, token)

            assertThat(recovered).isEqualTo(submitted)
            assertThat(recovered.pendingBid).isEqualTo(12)
        }

    @Test
    fun independentWritersCommitExactlyOneSimultaneousReveal(): Unit =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            val writers = listOf(service, fixture.reopened(), fixture.reopened())
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val results = writers.mapIndexed { seat, writer ->
                    executor.submit(
                        Callable {
                            start.await()
                            writer.submit(
                                game.gameId,
                                token(game, seat),
                                2,
                                "bid-capital ${3 - seat}",
                            )
                        }
                    )
                }
                start.countDown()
                results.forEach { it.get() }
            } finally {
                executor.shutdownNow()
            }

            val stored = fixture.store.read(game.gameId)
            assertThat(stored.revision).isEqualTo(3)
            assertThat(stored.pendingBids).isEmpty()
            assertThat(stored.revisions.count { it.command == "reveal-capital-bids" }).isEqualTo(1)
            assertThat(service.get(game.gameId, token(game, 0)).game.revealedBids.values)
                .containsExactly(3, 2, 1)
        }

    @Test
    fun simultaneousCommittedActionsRejectTheStaleWriter() =
        PostgresFixture().use { fixture ->
            val first = fixture.service()
            val game = first.create(listOf("Alice", "Bob", "Carol"))
            first.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
            first.submit(game.gameId, token(game, 1), 2, "bid-capital 2")
            first.submit(game.gameId, token(game, 2), 2, "bid-capital 1")
            val writers = listOf(first, fixture.reopened())
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val results =
                try {
                    val futures = writers.map { writer ->
                        executor.submit(
                            Callable {
                                start.await()
                                runCatching {
                                    writer.submit(
                                        game.gameId,
                                        token(game, 0),
                                        3,
                                        "choose-capital venice",
                                    )
                                }
                            }
                        )
                    }
                    start.countDown()
                    futures.map { it.get() }
                } finally {
                    executor.shutdownNow()
                }

            assertThat(results.count(Result<GameSnapshot>::isSuccess)).isEqualTo(1)
            assertThat((results.single { it.isFailure }.exceptionOrNull() as ServiceFailure).code)
                .isEqualTo("STALE_REVISION")
            assertThat(fixture.store.read(game.gameId).revision).isEqualTo(4)
        }

    @Test
    fun failedWriteRollsBackPrivateSubmissionChanges() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            val before = fixture.store.document(game.gameId)

            assertFailsWith<IllegalStateException> {
                fixture.database.transaction {
                    service.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
                    error("Injected failure after the private submission update")
                }
            }

            assertThat(fixture.store.document(game.gameId)).isEqualTo(before)
            assertThat(fixture.reopened(NoPostgresRandom).get(game.gameId, token(game, 0)).revision)
                .isEqualTo(2)
        }

    @Test
    fun rolledBackReadsCannotPoisonACacheVersionReusedByTheNextWrite() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            assertFailsWith<IllegalStateException> {
                fixture.database.transaction {
                    service.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
                    service.get(game.gameId, token(game, 0))
                    error("Injected failure after reading the uncommitted game")
                }
            }

            service.submit(game.gameId, token(game, 0), 2, "bid-capital 9")
            val recovered = service.get(game.gameId, token(game, 0))

            assertThat(recovered.game.stage).isEqualTo(Stage.BIDDING)
            assertThat(recovered.pendingBid).isEqualTo(9)
        }

    @Test
    fun uniqueHandlesAreEnforcedAcrossIndependentPools() =
        PostgresFixture().use { fixture ->
            fixture.accounts.register(AccountId("alice"), "alice", "Alice")
            val other = fixture.reopened().store as PostgresGameStore

            val failure =
                assertFailsWith<ServiceFailure> {
                    PostgresAccountStore(other.database)
                        .register(AccountId("other"), "Alice", "Other")
                }

            assertThat(failure.code).isEqualTo("HANDLE_UNAVAILABLE")
            assertThat(fixture.accounts.all()).hasSize(1)
        }

    @Test
    fun failedPurgeRollsBackTheGameArchivesAndAuditTogether() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))
            fixture.administration.replaceLog(
                game.gameId,
                AccountId("admin"),
                2,
                fixture.administration.log(game.gameId).entries,
            )
            val before = fixture.store.document(game.gameId)

            assertFailsWith<IllegalStateException> {
                fixture.database.transaction {
                    fixture.administration.purge(game.gameId, AccountId("admin"), 3)
                    error("Injected failure after purge")
                }
            }

            assertThat(fixture.store.document(game.gameId)).isEqualTo(before)
            assertThat(fixture.store.archives(game.gameId)).hasSize(1)
            assertThat(auditCount(fixture)).isEqualTo(0)
        }

    @Test
    fun failedPermissionChangeRollsBackApprovalAndAuditTogether() =
        PostgresFixture().use { fixture ->
            fixture.accounts.register(AccountId("admin"), "admin", "Admin")
            fixture.accounts.register(AccountId("alice"), "alice", "Alice")

            assertFailsWith<IllegalStateException> {
                fixture.database.transaction {
                    fixture
                        .hosted()
                        .allowCreation(
                            AccountIdentity(AccountId("admin"), true),
                            AccountId("alice"),
                            true,
                        )
                    error("Injected failure after approval")
                }
            }

            assertThat(fixture.accounts.find(AccountId("alice"))?.mayCreateGames).isFalse()
            assertThat(auditCount(fixture)).isEqualTo(0)
        }

    @Test
    fun revisionsRemainSeparateFromTheMutableGameDocument() =
        PostgresFixture().use { fixture ->
            val service = fixture.service()
            val game = service.create(listOf("Alice", "Bob", "Carol"))

            val metadata =
                fixture.database.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT document FROM games").use { rows ->
                            rows.next()
                            json.readTree(rows.getString(1))
                        }
                    }
                }

            assertThat(metadata.has("revisions")).isFalse()
            assertThat(fixture.store.read(game.gameId).revisions).hasSize(2)
        }

    private fun auditCount(fixture: PostgresFixture): Int =
        fixture.database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM administration_audit").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun token(game: CreatedGame, seat: Int) = game.links[seat].path.substringAfter("token=")
}

internal object NoPostgresRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> =
        error("Recovery must not shuffle new outcomes.")

    override fun roll(count: Int): List<DieRoll> = error("Recovery must not roll new outcomes.")
}
