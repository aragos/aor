package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import org.flywaydb.core.Flyway

class PostgresMigrationTest {
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
