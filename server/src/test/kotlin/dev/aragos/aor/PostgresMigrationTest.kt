package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import org.flywaydb.core.Flyway

class PostgresMigrationTest {
    @Test
    fun automatedSeatUpgradePreservesExistingMembershipsAndCredentials() {
        val schema = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val settings = TestPostgres.settings(schema)
        val gameId = UUID.randomUUID().toString()
        try {
            Flyway.configure()
                .dataSource(settings.jdbcUrl, settings.user, settings.password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate()
            DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password).use {
                connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO $schema.accounts (id, handle) VALUES ('existing', 'existing')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO $schema.games (id, document, revision, listing_status, stage) VALUES ('$gameId', '{}', 1, 'ONGOING', 'BIDDING')"
                    )
                    statement.executeUpdate(
                        "INSERT INTO $schema.game_players (game_id, seat, account_id, credential_hash, needs_move) VALUES ('$gameId', 'p1', 'existing', null, true), ('$gameId', 'p2', null, repeat('a',64), false)"
                    )
                }
            }

            PostgresDatabase(settings).use { database ->
                database.write { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "INSERT INTO game_players (game_id, seat, automated, needs_move) VALUES ('$gameId', 'p3', true, false)"
                        )
                        statement
                            .executeQuery(
                                "SELECT seat, account_id, credential_hash, automated FROM game_players ORDER BY seat"
                            )
                            .use { rows ->
                                assertThat(rows.next()).isTrue()
                                assertThat(rows.getString("account_id")).isEqualTo("existing")
                                assertThat(rows.getBoolean("automated")).isFalse()
                                assertThat(rows.next()).isTrue()
                                assertThat(rows.getString("credential_hash"))
                                    .isEqualTo("a".repeat(64))
                                assertThat(rows.getBoolean("automated")).isFalse()
                                assertThat(rows.next()).isTrue()
                                assertThat(rows.getBoolean("automated")).isTrue()
                                assertThat(rows.getString("account_id")).isNull()
                                assertThat(rows.getString("credential_hash")).isNull()
                            }
                    }
                }
            }
        } finally {
            TestPostgres.drop(schema)
        }
    }

    @Test
    fun handlesOnlyUpgradePreservesExistingAccountIdentityAndPermissions() {
        val schema = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val settings = TestPostgres.settings(schema)
        try {
            Flyway.configure()
                .dataSource(settings.jdbcUrl, settings.user, settings.password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate()
            DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password).use {
                connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO $schema.accounts (id, handle, display_name, may_create_games) " +
                            "VALUES ('existing-uid', 'alice', 'Former Name', true)"
                    )
                }
            }

            val account =
                PostgresDatabase(settings).use { database ->
                    PostgresAccountStore(database).find(AccountId("existing-uid"))
                }

            assertThat(account)
                .isEqualTo(
                    RegisteredAccount(
                        AccountId("existing-uid"),
                        PlayerHandle("alice"),
                        mayCreateGames = true,
                    )
                )
        } finally {
            TestPostgres.drop(schema)
        }
    }
}
