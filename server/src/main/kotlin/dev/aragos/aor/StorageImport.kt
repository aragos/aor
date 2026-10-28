package dev.aragos.aor

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class StorageImportResult(
    val accounts: Int,
    val games: Int,
    val archives: Int,
    val receipts: Int,
)

/**
 * Explicit one-time copy of current-format files into an empty SQL schema. Stop the source server
 * first. The source is read-only, archives keep their bytes, and the complete import is atomic.
 */
fun importFileStorage(directory: Path, database: PostgresDatabase): StorageImportResult {
    require(Files.isDirectory(directory)) {
        "The storage import source must be an existing directory."
    }
    val source = FileGameStore(directory)
    val accounts = FileAccountStore(directory.resolve("accounts.json")).all()
    val ids = source.ids()
    val receipts =
        directory.resolve("admin-audit").let { audit ->
            if (!Files.isDirectory(audit)) emptyList()
            else
                Files.list(audit).use { paths ->
                    paths
                        .filter {
                            Files.isRegularFile(it) && it.fileName.toString().endsWith(".json")
                        }
                        .sorted()
                        .map(Files::readString)
                        .toList()
                }
        }
    return database.write { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                """
                LOCK TABLE accounts, games, game_revisions, game_players, game_archives,
                    administration_audit IN ACCESS EXCLUSIVE MODE
                """
                    .trimIndent()
            )
            statement
                .executeQuery(
                    """
                    SELECT EXISTS(SELECT FROM accounts) OR EXISTS(SELECT FROM games)
                        OR EXISTS(SELECT FROM administration_audit)
                    """
                        .trimIndent()
                )
                .use { result ->
                    result.next()
                    require(!result.getBoolean(1)) {
                        "Storage import requires an empty application schema; " +
                            "existing data is never overwritten."
                    }
                }
        }
        val targetAccounts = PostgresAccountStore(database)
        accounts.forEach { account ->
            targetAccounts.register(account.id, account.handle.value, account.displayName)
            if (account.mayCreateGames) targetAccounts.allowCreation(account.id, true)
        }
        val target = PostgresGameStore(database)
        val archives = ids.sumOf { id ->
            val game = source.read(id)
            val names = source.archives(id)
            require(game.previousArchive == null || game.previousArchive in names) {
                "Every checkpoint must include its preceding archive in the source."
            }
            target.create(game)
            names.forEach { name ->
                target.retainArchive(id, name, source.archivedDocument(id, name))
            }
            names.size
        }
        receipts.forEach { contents ->
            val receipt = json.readTree(contents)
            val actor = AccountId(receipt.field("actor").string())
            val action = AdministrativeAction.valueOf(receipt.field("action").string())
            val outcome = receipt.field("outcome").string()
            require(outcome in setOf("REQUESTED", "COMMITTED")) {
                "Audit receipts require a known outcome."
            }
            connection
                .prepareStatement(
                    """
                    INSERT INTO administration_audit
                        (id, at, actor, action, target, revision, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setLong(2, receipt.field("at").longInteger())
                    statement.setString(3, actor.value)
                    statement.setString(4, action.name)
                    statement.setString(5, receipt.field("target").string())
                    statement.setInt(6, receipt.field("revision").integer())
                    statement.setString(7, outcome)
                    statement.executeUpdate()
                }
        }
        StorageImportResult(accounts.size, ids.size, archives, receipts.size)
    }
}

/** CLI entry point uses the same secret environment configuration as the application. */
fun main(args: Array<String>) {
    require(args.size == 1 && args[0].isNotBlank()) {
        "Provide the stopped file-storage directory to import."
    }
    PostgresDatabase(PostgresSettings.fromEnvironment(System.getenv())).use { database ->
        val result = importFileStorage(Path.of(args[0]), database)
        println(json.writeValueAsString(result))
    }
}
