package dev.aragos.aor

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID

/** Tests run against PostgreSQL 18, never H2 or a mock; each fixture owns an isolated schema. */
internal class PostgresFixture : AutoCloseable {
    val schema = "test_${UUID.randomUUID().toString().replace("-", "")}"
    val environment: Map<String, String> =
        TestPostgres.environment(schema) + ("AOR_STORAGE" to "postgres")
    val settings = TestPostgres.settings(schema)
    val database = PostgresDatabase(settings)
    val store = PostgresGameStore(database)
    val accounts = PostgresAccountStore(database)
    val audit = PostgresAdministrationAudit(database)
    val administration = GameAdministration(store, audit, transactions = database)
    private val connections = mutableListOf(database)

    fun service(random: SetupRandom = FixedRandom()): GameService =
        GameService(store, GameDriver(random))

    /**
     * A fresh pool/store verifies restart and cross-instance coordination without shared caches.
     */
    fun reopened(random: SetupRandom = FixedRandom()): GameService {
        val reopened = PostgresDatabase(settings)
        connections.add(reopened)
        return GameService(PostgresGameStore(reopened), GameDriver(random))
    }

    fun hosted(service: GameService = service()): HostedServices =
        HostedServices(
            games = service,
            accounts = accounts,
            verifier =
                IdentityVerifier { token ->
                    if (token == "invalid") throw ServiceFailure(401, "UNAUTHORIZED")
                    AccountIdentity(AccountId(token), token != "unverified")
                },
            administrators = setOf(AccountId("admin")),
            creationPolicy = GameCreationPolicy.APPROVED,
            publicConfig = PublicApplicationConfig("firebase", emptyMap(), 5000),
            administration = administration,
            transactions = database,
        )

    override fun close() {
        connections.forEach(PostgresDatabase::close)
        TestPostgres.drop(schema)
    }
}

internal object TestPostgres {
    private val embedded: EmbeddedPostgres? by lazy {
        if (System.getenv("AOR_TEST_DATABASE_URL") == null) {
            EmbeddedPostgres.builder()
                .setPort(0)
                .setServerConfig("max_connections", "50")
                .start()
                .also { postgres ->
                    Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
                }
        } else null
    }

    private val origin: String by lazy {
        val external = System.getenv("AOR_TEST_DATABASE_URL")
        if (external != null) {
            external
        } else {
            val port =
                checkNotNull(embedded) {
                        "Embedded PostgreSQL is required without a test URL."
                    }
                    .port
            "postgresql://postgres:postgres@127.0.0.1:$port/postgres?sslmode=disable"
        }
    }

    fun environment(schema: String): Map<String, String> =
        mapOf("AOR_DATABASE_URL" to origin, "AOR_DATABASE_SCHEMA" to schema)

    fun settings(schema: String): PostgresSettings =
        PostgresSettings.fromEnvironment(environment(schema))

    fun drop(schema: String) {
        val config = settings(schema)
        java.sql.DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use {
            connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") }
        }
    }
}
