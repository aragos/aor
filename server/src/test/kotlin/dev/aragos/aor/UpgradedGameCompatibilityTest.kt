package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class UpgradedGameCompatibilityTest {
    private val directories = mutableListOf<Path>()
    private val id = "3dd8402c-8a4c-4837-b5cd-25e674a0a8e1"
    private val owner = PlayerId("p1")
    private val archive = "2-9ec97e68026d5f7fa07bbe518eae0622560e5b354b8e546638dfaf041d311d23.json"

    @AfterTest
    fun clean() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun alreadyUpgradedFileReplaysAndContinuesAfterRestart() {
        val source = fileFixture()
        val before = source.read(id)

        continueBidding(source)
        val reopened = FileGameStore(source.directory)

        assertContinuation(before, reopened.read(id))
        assertThat(reopened.archivedDocument(id, archive))
            .isEqualTo(resource("upgraded-format-seven-source.json"))
    }

    @Test
    fun alreadyUpgradedPostgresGameReplaysAndContinuesAfterRestart() =
        PostgresFixture().use { database ->
            val source = fileFixture()
            importFileStorage(source.directory, database.database)
            val before = database.store.read(id)

            continueBidding(database.store)
            val reopened = database.reopened().store as ManagedGameStore

            assertContinuation(before, reopened.read(id))
            assertThat(reopened.archivedDocument(id, archive))
                .isEqualTo(resource("upgraded-format-seven-source.json"))
        }

    @Test
    fun administrativeCheckpointPreservesTheHistoricalUpgradeArchive() =
        PostgresFixture().use { database ->
            val source = fileFixture()
            importFileStorage(source.directory, database.database)
            val before = database.store.read(id)
            val document = database.store.document(id)

            database.administration.replaceLog(
                id,
                AccountId("admin"),
                before.revision,
                before.effectiveEntries().map(RecordedEntry::text),
            )
            val reopened = database.reopened().store as ManagedGameStore
            val after = reopened.read(id)

            assertThat(after.state()).isEqualTo(before.state())
            assertThat(after.revision).isEqualTo(before.revision + 1)
            assertThat(reopened.archives(id)).contains(archive)
            assertThat(reopened.archivedDocument(id, archive))
                .isEqualTo(resource("upgraded-format-seven-source.json"))
            val preceding =
                requireNotNull(after.previousArchive) {
                    "A checkpoint requires its preceding archive."
                }
            assertThat(reopened.archivedDocument(id, preceding)).isEqualTo(document)
        }

    private fun continueBidding(store: GameStore) {
        val driver = GameDriver(FixedRandom())
        store.update(id) { before ->
            listOf(PlayerId("p2") to 2, PlayerId("p3") to 1).fold(before) { game, (player, amount)
                ->
                driver.submit(game, player, "bid-capital $amount")
            }
        }
    }

    private fun assertContinuation(before: StoredGame, after: StoredGame) {
        assertThat(before.fileVersion).isEqualTo(7)
        assertThat(before.pendingBids[owner]).isEqualTo(3)
        assertThat(before.revisions.last().storageArchive).isEqualTo(archive)
        assertThat(after.revisions.take(before.revisions.size)).isEqualTo(before.revisions)
        assertThat(after.effectiveEntries().take(before.effectiveEntries().size))
            .isEqualTo(before.effectiveEntries())
        assertThat(after.state().stage).isEqualTo(Stage.CAPITAL_CHOICE)
        assertThat(after.state().publicView().revealedBids[owner]).isEqualTo(3)
        assertThat(after.header).isEqualTo(before.header)
        assertThat(after.credentials).isEqualTo(before.credentials)
        assertThat(after.name).isEqualTo(before.name)
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        assertThat(after.undoPolicy).isEqualTo(before.undoPolicy)
        assertThat(HistoryProjection.forPlayer(after, owner).lines)
            .contains("# revision 3 by p1: upgrade-planning")
    }

    /**
     * Captured with the released upgrader before its removal; archives remain opaque source bytes.
     */
    private fun fileFixture(): FileGameStore {
        val directory = Files.createTempDirectory("aor-upgraded-save-").also(directories::add)
        Files.writeString(directory.resolve("$id.json"), resource("upgraded-format-seven.json"))
        val archives = Files.createDirectories(directory.resolve("archives").resolve(id))
        Files.writeString(archives.resolve(archive), resource("upgraded-format-seven-source.json"))
        return FileGameStore(directory)
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/games/$name")) {
                "The pre-cleanup game fixture $name must exist."
            }
            .bufferedReader()
            .use { it.readText() }
}
