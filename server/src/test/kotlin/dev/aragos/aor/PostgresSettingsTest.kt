package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PostgresSettingsTest {
    @Test
    fun externalConnectionsDefaultToVerifiedTlsWithoutCredentialsInTheJdbcUrl() {
        val settings =
            PostgresSettings.fromEnvironment(
                mapOf("DATABASE_URL" to "postgresql://alice:private-password@database.example/game")
            )

        assertThat(settings.jdbcUrl).contains("sslmode=verify-full")
        assertThat(settings.jdbcUrl).contains("DefaultJavaSSLFactory")
        assertThat(settings.jdbcUrl).doesNotContain("alice")
        assertThat(settings.jdbcUrl).doesNotContain("private-password")
    }

    @Test
    fun encodedCredentialCharactersRoundTripWithoutBecomingJdbcParameters() {
        val settings =
            PostgresSettings.fromEnvironment(
                mapOf(
                    "AOR_DATABASE_URL" to
                        "postgres://al%40ice:pass%2Bword%3Fsecret%3D1@database.example:6432/game"
                )
            )

        assertThat(settings.user).isEqualTo("al@ice")
        assertThat(settings.password).isEqualTo("pass+word?secret=1")
        assertThat(settings.jdbcUrl).startsWith("jdbc:postgresql://database.example:6432/game?")
    }

    @Test
    fun unencryptedRemoteConnectionsAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PostgresSettings.fromEnvironment(
                    mapOf(
                        "DATABASE_URL" to
                            "postgres://user:secret@database.example/game?sslmode=disable"
                    )
                )
            }

        assertThat(failure).hasMessageThat().contains("loopback")
    }

    @Test
    fun unexpectedConnectionParametersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PostgresSettings.fromEnvironment(
                mapOf("DATABASE_URL" to "postgres://user:secret@database.example/game?user=other")
            )
        }
    }

    @Test
    fun schemaNamesCannotInjectSqlOrEscapeTheApplicationSchema() {
        assertFailsWith<IllegalArgumentException> {
            PostgresSettings.fromEnvironment(
                mapOf(
                    "DATABASE_URL" to "postgres://user:secret@localhost/game",
                    "AOR_DATABASE_SCHEMA" to "aor;DROP SCHEMA public",
                )
            )
        }
    }
}
