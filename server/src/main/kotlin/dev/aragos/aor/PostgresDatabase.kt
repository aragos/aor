package dev.aragos.aor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import org.flywaydb.core.Flyway

/** TLS policy for a managed endpoint; DISABLE is restricted to loopback development. */
enum class DatabaseTls(val wire: String) {
    VERIFY_FULL("verify-full"),
    REQUIRE("require"),
    DISABLE("disable"),
}

/** Connection settings deliberately keep credentials out of JDBC URLs and generated diagnostics. */
class PostgresSettings(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val schema: String = "aor",
    val poolSize: Int = 4,
) {
    init {
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}"))) {
            "Database schema requires a lowercase SQL identifier of at most 63 characters."
        }
        require(poolSize in 1..20) { "The database pool must have one through twenty connections." }
        require(user.isNotBlank()) { "The database user must not be blank." }
    }

    companion object {
        /** Parses Render/standard postgres URLs; query parameters are an explicit allowlist. */
        fun fromEnvironment(environment: Map<String, String>): PostgresSettings {
            val value =
                environment["AOR_DATABASE_URL"]
                    ?: environment["DATABASE_URL"]
                    ?: error("PostgreSQL storage requires AOR_DATABASE_URL or DATABASE_URL.")
            val uri =
                try {
                    URI(value)
                } catch (_: Exception) {
                    error("The database URL is malformed.")
                }
            require(
                uri.scheme in setOf("postgres", "postgresql") &&
                    uri.host != null &&
                    uri.fragment == null
            ) {
                "Use a postgres:// or postgresql:// database URL with a hostname."
            }
            val credentials = uri.rawUserInfo?.split(':', limit = 2)
            require(credentials?.size == 2) { "The database URL must include a user and password." }
            val query =
                uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { parameter
                    ->
                    val parts = parameter.split('=', limit = 2)
                    require(parts.size == 2) { "Database URL parameters require a name and value." }
                    decode(parts[0]) to decode(parts[1])
                }
            require(query.keys.all { it in setOf("sslmode", "sslrootcert") }) {
                "Only sslmode and sslrootcert are supported database URL parameters."
            }
            val mode = environment["AOR_DATABASE_TLS"] ?: query["sslmode"] ?: "verify-full"
            val tls =
                DatabaseTls.entries.singleOrNull { it.wire == mode }
                    ?: error("AOR_DATABASE_TLS must be verify-full, require or disable.")
            require(
                tls != DatabaseTls.DISABLE ||
                    uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            ) {
                "Unencrypted database connections are only allowed on loopback."
            }
            require(uri.port == -1 || uri.port in 1..65535) {
                "The database port must be between one and 65535."
            }
            val database = uri.rawPath.orEmpty().removePrefix("/")
            require(database.isNotEmpty() && '/' !in database) {
                "The database URL requires one database name."
            }
            val parameters = buildMap {
                put("sslmode", tls.wire)
                if (tls == DatabaseTls.VERIFY_FULL && query["sslrootcert"] == null) {
                    put("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")
                }
                query["sslrootcert"]?.let { put("sslrootcert", it) }
                put("connectTimeout", "10")
                put("socketTimeout", "60")
                put("tcpKeepAlive", "true")
                put("ApplicationName", "aor")
            }
                .entries
                .joinToString("&") { (key, parameter) -> "${encode(key)}=${encode(parameter)}" }
            val host = uri.host.let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
            return PostgresSettings(
                jdbcUrl =
                    "jdbc:postgresql://$host:${if (uri.port == -1) 5432 else uri.port}/$database?$parameters",
                user = decode(credentials[0]),
                password = decode(credentials[1]),
                schema = environment["AOR_DATABASE_SCHEMA"] ?: "aor",
                poolSize =
                    environment["AOR_DATABASE_POOL_SIZE"]?.let {
                        it.toIntOrNull() ?: error("The database pool size must be a whole number.")
                    } ?: 4,
            )
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

/** A bounded pool and synchronous transaction boundary shared by the application's repositories. */
class PostgresDatabase(val settings: PostgresSettings) : StorageTransactions, AutoCloseable {
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.poolSize
                minimumIdle = 0
                connectionTimeout = 10000
                validationTimeout = 3000
                idleTimeout = 60000
                maxLifetime = 1800000
                schema = settings.schema
                poolName = "aor-postgres"
            }
        )
    private val active = ThreadLocal<Connection>()

    init {
        try {
            Flyway.configure()
                .dataSource(pool)
                .schemas(settings.schema)
                .defaultSchema(settings.schema)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        } catch (_: Exception) {
            pool.close()
            throw ServiceFailure(503, "DATABASE_INITIALIZATION_FAILED")
        }
    }

    override fun <T> transaction(block: () -> T): T = connection(readOnly = false) { block() }

    /** Reads use a repeatable snapshot so metadata, private submissions and revisions agree. */
    internal fun <T> read(block: (Connection) -> T): T = connection(readOnly = true, block)

    internal fun <T> write(block: (Connection) -> T): T = connection(readOnly = false, block)

    private fun <T> connection(readOnly: Boolean, block: (Connection) -> T): T {
        active.get()?.let {
            return block(it)
        }
        try {
            return pool.connection.use { connection ->
                connection.autoCommit = false
                connection.isReadOnly = readOnly
                connection.transactionIsolation =
                    if (readOnly) {
                        Connection.TRANSACTION_REPEATABLE_READ
                    } else {
                        Connection.TRANSACTION_READ_COMMITTED
                    }
                active.set(connection)
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (failure: Throwable) {
                    runCatching { connection.rollback() }
                    throw failure
                } finally {
                    active.remove()
                }
            }
        } catch (_: SQLException) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    override fun close() {
        pool.close()
    }
}
