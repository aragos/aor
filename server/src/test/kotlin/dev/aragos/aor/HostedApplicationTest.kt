package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HostedApplicationTest(private val postgres: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}")
        fun backends(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @Test
    fun signupRequiresVerifiedEmail() = withHosted {
        val response =
            post(
                "/api/players/register",
                "unverified",
                mapOf("handle" to "new-player"),
            )

        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(accounts.find(AccountId("unverified"))).isNull()
    }

    @Test
    fun publicHandlesRemainUniqueAcrossServerRestarts() = withHosted {
        accounts.register(AccountId("new-user"), "New-Player")

        val response =
            post(
                "/api/players/register",
                "another-user",
                mapOf("handle" to "new-player"),
            )

        assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(reopenedAccounts().find(AccountId("new-user"))?.handle)
            .isEqualTo(PlayerHandle("new-player"))
    }

    @Test
    fun registrationRequiresOnlyAUniqueHandle() = withHosted {
        val response = post("/api/players/register", "new-user", mapOf("handle" to "New-Player"))

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val account = json.readTree(response.bodyAsText())
        assertThat(account["handle"].asText()).isEqualTo("new-player")
        assertThat(account.has("displayName")).isFalse()
        assertThat(reopenedAccounts().find(AccountId("new-user")))
            .isEqualTo(RegisteredAccount(AccountId("new-user"), PlayerHandle("new-player")))
    }

    @Test
    fun registeredGamesUseHandlesForPlayerNames() = withHosted {
        val game = game()

        val response = client.get("/api/games/${game.gameId}") { bearerAuth("alice") }

        val players = json.readTree(response.bodyAsText())["game"]["players"]
        assertThat(players.map { it["name"].asText() }).containsExactly("@alice", "@bob", "@carol")
    }

    @Test
    fun administrativeListingsRetainLocalSeatNamesWithoutAccountMemberships() = withHosted {
        accounts.register(AccountId("null"), "literal-null")
        service.create(listOf("Local Alice", "Local Bob", "Local Carol"))

        val response = client.get("/api/admin/games?status=all") { bearerAuth("admin") }

        val players = json.readTree(response.bodyAsText())["games"][0]["players"]
        assertThat(players.map { it["name"].asText() })
            .containsExactly("Local Alice", "Local Bob", "Local Carol")
    }

    @Test
    fun existingGamesShowHandlesWithoutRewritingRecordedHistory() = withHosted {
        val players =
            listOf("alice", "bob", "carol").mapIndexed { index, account ->
                PlayerId("p${index + 1}") to account
            }
        val game =
            GameDriver(FixedRandom())
                .create(
                    UUID.randomUUID().toString(),
                    GameHeader(
                        players = players.map { (id, _) -> Player(id, "Former $id") },
                        creatorId = PlayerId("p1"),
                    ),
                    emptyMap(),
                )
                .copy(memberships = players.toMap())
        store.create(game)
        val storedBefore = store.document(game.id)
        val eventsBefore = service.get(game.id, GameAccess.Account(AccountId("alice"))).events

        val response = client.get("/api/games/${game.id}") { bearerAuth("alice") }
        val listing = client.get("/api/games?status=all") { bearerAuth("alice") }

        val snapshot = json.readTree(response.bodyAsText())
        assertThat(snapshot["game"]["players"].map { it["name"].asText() })
            .containsExactly("@alice", "@bob", "@carol")
        assertThat(
                json.readTree(listing.bodyAsText())["games"][0]["players"].map {
                    it["name"].asText()
                }
            )
            .containsExactly("@alice", "@bob", "@carol")
        assertThat(snapshot["events"]).isEqualTo(json.valueToTree(eventsBefore))
        assertThat(store.document(game.id)).isEqualTo(storedBefore)
    }

    @Test
    fun nonApprovedPlayersCannotCreateGames() = withHosted {
        val response =
            post("/api/games", "bob", mapOf("players" to listOf("bob", "alice", "carol")))

        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(store.ids()).isEmpty()
    }

    @Test
    fun adminApprovalAllowsARegisteredPlayerToCreateGames() = withHosted {
        post("/api/admin/players/bob/creation", "admin", mapOf("allowed" to true))

        val response =
            post("/api/games", "bob", mapOf("players" to listOf("bob", "alice", "carol")))

        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        assertThat(json.readTree(response.bodyAsText())["registered"].asBoolean()).isTrue()
    }

    @Test
    fun newGamesRejectUnregisteredParticipants() = withHosted {
        val response =
            post("/api/games", "alice", mapOf("players" to listOf("alice", "bob", "unknown")))

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(store.ids()).isEmpty()
    }

    @Test
    fun creatorRoleDoesNotDependOnSeatOrder() = withHosted {
        val response =
            post("/api/games", "alice", mapOf("players" to listOf("bob", "alice", "carol")))
        val result = json.readTree(response.bodyAsText())
        val snapshot = get("/api/games/${result["gameId"].asText()}", "alice")
        val view = json.readTree(snapshot.bodyAsText())

        assertThat(view["creatorId"].asText()).isEqualTo("p2")
        assertThat(view["viewerId"].asText()).isEqualTo("p2")
        assertThat(result["links"].toString()).doesNotContain("token=")
    }

    @Test
    fun administratorRoleDoesNotGrantAnotherPlayersPrivateView() = withHosted {
        val game = game()

        val response = get("/api/games/${game.gameId}", "admin")

        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun registeredDirectorySearchIdentifiesPlayersWithoutEmailAddresses() = withHosted {
        val response = get("/api/players?q=ali", "bob")
        val result = json.readTree(response.bodyAsText())

        assertThat(result.map { it["id"].asText() }).containsExactly("alice")
        assertThat(result[0]["handle"].asText()).isEqualTo("alice")
        assertThat(result[0].has("email")).isFalse()
    }

    @Test
    fun gameListUsesMembershipAndReadinessFilters() = withHosted {
        val game = game()
        command(game.gameId, "alice", 2, "bid-capital 5")

        val alice = json.readTree(get("/api/games?status=my-move", "alice").bodyAsText())
        val bob = json.readTree(get("/api/games?status=my-move", "bob").bodyAsText())
        val outsider = json.readTree(get("/api/games?status=all", "outsider").bodyAsText())

        assertThat(alice["games"].size()).isEqualTo(0)
        assertThat(bob["games"][0]["id"].asText()).isEqualTo(game.gameId)
        assertThat(bob["games"][0]["status"].asText()).isEqualTo("MY_MOVE")
        assertThat(outsider["games"].size()).isEqualTo(0)
    }

    @Test
    fun nonAdminsCannotExportAllPrivateLogs() = withHosted {
        game()

        val response = get("/api/admin/logs", "alice")

        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun unchangedPrivateSnapshotsReturnNotModified() = withHosted {
        val game = game()
        val first = get("/api/games/${game.gameId}", "alice")

        val response =
            client.get("/api/games/${game.gameId}") {
                bearerAuth("alice")
                header(HttpHeaders.IfNoneMatch, first.headers[HttpHeaders.ETag])
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.NotModified)
        assertThat(response.bodyAsText()).isEmpty()
    }

    @Test
    fun replacingPrivateBidsDoesNotInvalidateAnotherPlayersSnapshot() = withHosted {
        val game = game()
        command(game.gameId, "alice", 2, "bid-capital 5")
        val before = get("/api/games/${game.gameId}", "bob")

        command(game.gameId, "alice", 2, "bid-capital 9")
        val response =
            client.get("/api/games/${game.gameId}") {
                bearerAuth("bob")
                header(HttpHeaders.IfNoneMatch, before.headers[HttpHeaders.ETag])
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.NotModified)
    }

    @Test
    fun cancelledGamesRetainReadAccessAndRejectCommands() = withHosted {
        val game = game()
        post(
            "/api/admin/games/${game.gameId}/status",
            "admin",
            mapOf("expectedRevision" to 2, "action" to "cancel"),
        )

        val rejected = command(game.gameId, "alice", 2, "start")
        val view = json.readTree(get("/api/games/${game.gameId}", "alice").bodyAsText())

        assertThat(rejected.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
        assertThat(view["status"].asText()).isEqualTo("CANCELLED")
        assertThat(view["prompt"].isNull).isTrue()
        assertThat(view["history"].toString()).contains("admin:admin")
    }

    @Test
    fun staleAdministrativeChangesLeaveTheGameUntouched() = withHosted {
        val game = game()
        val original = store.document(game.gameId)

        val response =
            post(
                "/api/admin/games/${game.gameId}/status",
                "admin",
                mapOf("expectedRevision" to 0, "action" to "abandon"),
            )

        assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(store.document(game.gameId)).isEqualTo(original)
    }

    @Test
    fun gameListPaginationDoesNotLoseGames() = withHosted {
        val ids = List(3) { game().gameId }.sorted()
        val first = json.readTree(get("/api/games?limit=2", "alice").bodyAsText())

        val second =
            json.readTree(
                get("/api/games?limit=2&after=${first["nextCursor"].asText()}", "alice")
                    .bodyAsText()
            )

        assertThat(
                first["games"].map { it["id"].asText() } + second["games"].map { it["id"].asText() }
            )
            .containsExactlyElementsIn(ids)
            .inOrder()
        assertThat(second["nextCursor"].isNull).isTrue()
    }

    @Test
    fun logValidationDoesNotPublishOrArchiveTheCandidate() = withHosted {
        val game = startedGame()
        val original = store.document(game.gameId)
        val log = hosted.administration.log(game.gameId)

        val response =
            post(
                "/api/admin/games/${game.gameId}/log",
                "admin",
                mapOf("expectedRevision" to 2, "entries" to log.entries, "dryRun" to true),
            )

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(store.document(game.gameId)).isEqualTo(original)
        assertThat(store.archives(game.gameId)).isEmpty()
    }

    @Test
    fun upgradesArchiveThePrecedingFileAndAllowFurtherCommands() = withHosted {
        val game = startedGame()
        val original = store.document(game.gameId)
        val entries = hosted.administration.log(game.gameId).entries
        val upgraded = hosted.administration.replaceLog(game.gameId, AccountId("admin"), 2, entries)

        command(game.gameId, "alice", 3, "bid-capital 9")

        assertThat(upgraded.revision).isEqualTo(3)
        assertThat(store.archivedDocument(game.gameId, upgraded.archives.single()))
            .isEqualTo(original)
        assertThat(service.get(game.gameId, GameAccess.Account(AccountId("alice"))).pendingBid)
            .isEqualTo(9)
    }

    @Test
    fun invalidUpgradesLeaveTheActiveFileAndArchivesUntouched() = withHosted {
        val game = startedGame()
        val original = store.document(game.gameId)
        val entries = hosted.administration.log(game.gameId).entries + "p1: choose-capital venice"

        val failure =
            assertFailsWith<ServiceFailure> {
                hosted.administration.replaceLog(game.gameId, AccountId("admin"), 2, entries)
            }

        assertThat(failure.code).isEqualTo("INVALID_GAME_LOG")
        assertThat(store.document(game.gameId)).isEqualTo(original)
        assertThat(store.archives(game.gameId)).isEmpty()
    }

    @Test
    fun upgradesCannotChangeRecordedRandomOutcomes() = withHosted {
        val game = startedGame()
        val entries =
            hosted.administration.log(game.gameId).entries.filterNot { it.startsWith('!') }

        val failure =
            assertFailsWith<ServiceFailure> {
                hosted.administration.replaceLog(game.gameId, AccountId("admin"), 2, entries)
            }

        assertThat(failure.code).isEqualTo("HISTORY_RANDOMNESS_CHANGED")
        assertThat(store.read(game.gameId).revision).isEqualTo(2)
    }

    @Test
    fun incompatibleEngineFilesRemainExportableButCannotRebindOutcomes() = withHosted {
        val game = startedGame()
        val root = json.readTree(store.document(game.gameId)) as ObjectNode
        root.put("fileVersion", 4)
        (root["header"] as ObjectNode).put("rulesVersion", "obsolete-engine")
        replaceDocument(game.gameId, root)
        val old = hosted.administration.log(game.gameId)

        val failure =
            assertFailsWith<ServiceFailure> {
                hosted.administration.replaceLog(
                    game.gameId,
                    AccountId("admin"),
                    old.revision,
                    old.entries,
                )
            }

        assertThat(old.fileVersion).isEqualTo(4)
        assertThat(failure.code).isEqualTo("HISTORY_IDENTITY_UNVERIFIABLE")
        assertThat(hosted.administration.log(game.gameId)).isEqualTo(old)
        assertThat(store.archives(game.gameId)).isEmpty()
    }

    @Test
    fun purgingReclaimsTheGameAndAllArchivesButRetainsAnAuditReceipt() = withHosted {
        val game = startedGame()
        val upgrade =
            hosted.administration.replaceLog(
                game.gameId,
                AccountId("admin"),
                2,
                hosted.administration.log(game.gameId).entries,
            )

        hosted.administration.purge(game.gameId, AccountId("admin"), upgrade.revision)

        assertThat(store.ids()).isEmpty()
        assertThat(store.archives(game.gameId)).isEmpty()
        val receipts = receipts()
        assertThat(receipts).hasSize(1)
        assertThat(receipts.single()).contains("PURGE")
        assertThat(receipts.single()).doesNotContain("!shuffle")
    }

    @Test
    fun failedCheckpointWritePreservesTheActiveFile() = withHosted {
        assumeFalse(postgres)
        val game = startedGame()
        val original = store.document(game.gameId)
        val failingStore =
            FileGameStore(
                directory,
                GameWriter { path, contents ->
                    if (path.parent == directory) throw IOException("Injected storage failure")
                    AtomicGameWriter.write(path, contents)
                },
            )
        val administration =
            GameAdministration(failingStore, FileAdministrationAudit(directory.resolve("audit")))

        assertFailsWith<ServiceFailure> {
            administration.replaceLog(
                game.gameId,
                AccountId("admin"),
                2,
                administration.log(game.gameId).entries,
            )
        }

        assertThat(store.document(game.gameId)).isEqualTo(original)
    }

    @Test
    fun administrativeArchivePathsRejectTraversal() = withHosted {
        val game = game()

        val failure =
            assertFailsWith<ServiceFailure> {
                store.archivedDocument(game.gameId, "../../accounts.json")
            }

        assertThat(failure.code).isEqualTo("ARCHIVE_NOT_FOUND")
    }

    private fun withHosted(block: suspend HostedFixture.() -> Unit) = testApplication {
        val fixture = HostedFixture(postgres)
        try {
            application { module(fixture.service, fixture.hosted) }
            fixture.client = client
            fixture.block()
        } finally {
            fixture.close()
        }
    }
}

private class HostedFixture(postgres: Boolean) : AutoCloseable {
    private val sql = if (postgres) PostgresFixture() else null
    val directory: Path = Files.createTempDirectory("aor-hosted-test-")
    val store: ManagedGameStore = sql?.store ?: FileGameStore(directory)
    val service = GameService(store, GameDriver(FixedRandom()))
    val accounts: AccountStore =
        sql?.accounts ?: FileAccountStore(directory.resolve("accounts.json"))
    val hosted: HostedServices
    lateinit var client: HttpClient

    init {
        listOf("admin", "alice", "bob", "carol", "outsider").forEach { id ->
            accounts.register(AccountId(id), id)
        }
        accounts.allowCreation(AccountId("alice"), true)
        hosted =
            HostedServices(
                service,
                accounts,
                IdentityVerifier { token ->
                    if (token == "invalid") throw ServiceFailure(401, "UNAUTHORIZED")
                    AccountIdentity(AccountId(token), token != "unverified")
                },
                setOf(AccountId("admin")),
                GameCreationPolicy.APPROVED,
                PublicApplicationConfig("firebase", emptyMap(), 5000),
                GameAdministration(
                    store,
                    sql?.audit ?: FileAdministrationAudit(directory.resolve("audit")),
                    transactions = sql?.database ?: FileTransactions,
                ),
                sql?.database ?: FileTransactions,
            )
    }

    fun reopenedAccounts(): AccountStore =
        sql?.let {
            PostgresAccountStore((it.reopened().store as PostgresGameStore).database)
        } ?: FileAccountStore(directory.resolve("accounts.json"))

    fun replaceDocument(id: String, root: ObjectNode) {
        if (sql == null) {
            Files.writeString(directory.resolve("$id.json"), root.toString())
        } else {
            val metadata =
                root.deepCopy().apply {
                    remove(listOf("id", "revision", "revisions", "memberships", "credentials"))
                }
            sql.database.write { connection ->
                connection
                    .prepareStatement(
                        "UPDATE games SET document = ?::jsonb, storage_version = storage_version + 1 WHERE id = ?::uuid"
                    )
                    .use { statement ->
                        statement.setString(1, metadata.toString())
                        statement.setString(2, id)
                        statement.executeUpdate()
                    }
            }
        }
    }

    fun receipts(): List<String> =
        sql?.database?.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT row_to_json(a) FROM administration_audit a").use {
                    rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString(1))
                        }
                    }
                }
            }
        }
            ?: Files.list(directory.resolve("audit")).use { paths ->
                paths.map(Files::readString).toList()
            }

    override fun close() {
        sql?.close()
        directory.toFile().deleteRecursively()
    }

    fun game() =
        hosted.create(
            AccountIdentity(AccountId("alice"), true),
            listOf("alice", "bob", "carol"),
            RuleOptions(),
            UndoPolicy.REGULAR_TURN,
        )

    fun startedGame(): CreatedGame = game()

    suspend fun get(path: String, token: String) = client.get(path) { bearerAuth(token) }

    suspend fun post(path: String, token: String, body: Any) =
        client.post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(json.writeValueAsString(body))
        }

    suspend fun command(id: String, actor: String, revision: Int, text: String) =
        post(
            "/api/games/$id/commands",
            actor,
            mapOf("expectedRevision" to revision, "command" to text),
        )
}
