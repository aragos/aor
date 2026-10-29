package dev.aragos.aor

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID

/** Unique indexes enforce identity/handle registration even across independent server instances. */
class PostgresAccountStore(private val database: PostgresDatabase) : AccountStore {
    override fun all(): List<RegisteredAccount> = database.read { connection ->
        connection.prepareStatement("SELECT * FROM accounts ORDER BY handle").use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(account(rows))
                    }
                }
            }
        }
    }

    override fun find(id: AccountId): RegisteredAccount? = database.read { connection ->
        find(connection, id)
    }

    override fun register(id: AccountId, handle: String): RegisteredAccount {
        val account = RegisteredAccount.create(id, handle)
        return database.write { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO accounts (id, handle) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setString(1, id.value)
                    statement.setString(2, account.handle.value)
                    if (statement.executeUpdate() == 0) {
                        if (find(connection, id) != null)
                            throw ServiceFailure(409, "ACCOUNT_ALREADY_REGISTERED")
                        throw ServiceFailure(409, "HANDLE_UNAVAILABLE")
                    }
                }
            account
        }
    }

    override fun allowCreation(id: AccountId, allowed: Boolean): RegisteredAccount =
        database.write { connection ->
            connection
                .prepareStatement(
                    "UPDATE accounts SET may_create_games = ? WHERE id = ? RETURNING *"
                )
                .use { statement ->
                    statement.setBoolean(1, allowed)
                    statement.setString(2, id.value)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw ServiceFailure(404, "ACCOUNT_NOT_FOUND")
                        account(rows)
                    }
                }
        }

    override fun search(query: String, limit: Int): List<RegisteredAccount> =
        database.read { connection ->
            require(limit in 1..100) {
                "An account search requires a limit of one through one hundred."
            }
            val normalized = query.lowercase()
            connection
                .prepareStatement(
                    """
                    SELECT * FROM accounts WHERE strpos(handle, ?) > 0
                        OR id = ?
                    ORDER BY handle LIMIT ?
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setString(1, normalized)
                    statement.setString(2, query)
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(account(rows))
                            }
                        }
                    }
                }
        }

    private fun find(connection: Connection, id: AccountId): RegisteredAccount? =
        connection.prepareStatement("SELECT * FROM accounts WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) account(rows) else null }
        }

    private fun account(rows: ResultSet): RegisteredAccount =
        RegisteredAccount(
            AccountId(rows.getString("id")),
            PlayerHandle(rows.getString("handle")),
            rows.getBoolean("may_create_games"),
        )
}

/** SQL audit records survive purges and roll back with a rejected or failed operation. */
class PostgresAdministrationAudit(
    private val database: PostgresDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : AdministrationAudit {
    override fun record(
        actor: AccountId,
        action: AdministrativeAction,
        target: String,
        revision: Int,
    ) {
        database.write { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO administration_audit (id, at, actor, action, target, revision)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setLong(2, clock.millis())
                    statement.setString(3, actor.value)
                    statement.setString(4, action.name)
                    statement.setString(5, target)
                    statement.setInt(6, revision)
                    statement.executeUpdate()
                }
        }
    }
}
