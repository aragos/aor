package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StorageImportTest {
    @Test
    fun importsAccountsCheckpointsAndPrivateSubmissionsWithTheirExactArchives() =
        PostgresFixture().use { fixture ->
            val directory = Files.createTempDirectory("aor-import-test-")
            try {
                val accounts = FileAccountStore(directory.resolve("accounts.json"))
                val players =
                    listOf("alice", "bobby", "carol").map { id ->
                        accounts.register(AccountId(id), id)
                    }
                accounts.allowCreation(players[0].id, true)
                val source = FileGameStore(directory)
                val service = GameService(source, GameDriver(FixedRandom()))
                val game =
                    service.createRegistered(
                        players,
                        players[0].id,
                        RuleOptions(),
                        UndoPolicy.FRIENDLY_GAME,
                    )
                val access = GameAccess.Account(players[0].id)
                val admin =
                    GameAdministration(
                        source,
                        FileAdministrationAudit(directory.resolve("admin-audit")),
                    )
                admin.replaceLog(game.gameId, AccountId("admin"), 2, admin.log(game.gameId).entries)
                service.submit(game.gameId, access, 3, "bid-capital 7")
                admin.recordCreationPermission(AccountId("admin"), players[0].id, true)
                val before = source.document(game.gameId)

                val result = importFileStorage(directory, fixture.database)

                assertThat(result).isEqualTo(StorageImportResult(3, 1, 1, 1))
                assertThat(json.readTree(fixture.store.document(game.gameId)))
                    .isEqualTo(json.readTree(before))
                assertThat(fixture.accounts.find(players[0].id)?.mayCreateGames).isTrue()
                val archive = source.archives(game.gameId).single()
                assertThat(fixture.store.archivedDocument(game.gameId, archive))
                    .isEqualTo(source.archivedDocument(game.gameId, archive))
                assertThat(source.document(game.gameId)).isEqualTo(before)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun importsLinearPolicyHistoryAndItsExactFriendlySourceArchive() =
        PostgresFixture().use { fixture ->
            val directory = Files.createTempDirectory("aor-import-linear-")
            try {
                val source = FileGameStore(directory)
                val service = GameService(source, GameDriver(FixedRandom()))
                val game =
                    service.create(
                        listOf("Alice", "Bob", "Carol"),
                        undoPolicy = UndoPolicy.FRIENDLY_GAME,
                    )
                LinearGameMigration.run(source)
                val contents = source.document(game.gameId)

                val result = importFileStorage(directory, fixture.database)

                assertThat(result.archives).isEqualTo(1)
                assertThat(json.readTree(fixture.store.document(game.gameId)))
                    .isEqualTo(json.readTree(contents))
                val archive = source.archives(game.gameId).single()
                assertThat(fixture.store.archivedDocument(game.gameId, archive))
                    .isEqualTo(source.archivedDocument(game.gameId, archive))
                assertThat(fixture.store.read(game.gameId).undoPolicy)
                    .isEqualTo(UndoPolicy.LINEAR_GAME)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun missingPolicySourceArchiveRejectsImportWithoutPublishingTheGame() =
        PostgresFixture().use { fixture ->
            val directory = Files.createTempDirectory("aor-import-missing-policy-archive-")
            try {
                val source = FileGameStore(directory)
                val game =
                    GameService(source, GameDriver(FixedRandom()))
                        .create(
                            listOf("Alice", "Bob", "Carol"),
                            undoPolicy = UndoPolicy.FRIENDLY_GAME,
                        )
                LinearGameMigration.run(source)
                Files.delete(
                    directory
                        .resolve("archives")
                        .resolve(game.gameId)
                        .resolve(source.archives(game.gameId).single())
                )

                assertFailsWith<IllegalArgumentException> {
                    importFileStorage(directory, fixture.database)
                }

                assertThat(fixture.store.ids()).isEmpty()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun failedImportRollsBackAccountsAndEarlierGames() =
        PostgresFixture().use { fixture ->
            val directory = Files.createTempDirectory("aor-import-invalid-")
            try {
                FileAccountStore(directory.resolve("accounts.json"))
                    .register(AccountId("alice"), "alice")
                val game = createGameService(directory).create(listOf("Alice", "Bob", "Carol"))
                val path = directory.resolve("${game.gameId}.json")
                Files.writeString(
                    path,
                    Files.readString(path).replace("\"fileVersion\":7", "\"fileVersion\":99"),
                )

                assertFailsWith<ServiceFailure> { importFileStorage(directory, fixture.database) }

                assertThat(fixture.accounts.all()).isEmpty()
                assertThat(fixture.store.ids()).isEmpty()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun importNeverOverwritesAnExistingApplicationSchema() =
        PostgresFixture().use { fixture ->
            val directory = Files.createTempDirectory("aor-import-nonempty-")
            try {
                fixture.accounts.register(AccountId("alice"), "alice")

                assertFailsWith<IllegalArgumentException> {
                    importFileStorage(directory, fixture.database)
                }

                assertThat(fixture.accounts.all()).hasSize(1)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
