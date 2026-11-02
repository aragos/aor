package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

/**
 * PostgreSQL storage keeps immutable revision rows and private submissions in one transaction. A
 * row lock serializes writers across server instances, including unchanged-revision sealed bids.
 */
class PostgresGameStore(internal val database: PostgresDatabase) : ManagedGameStore, AutoCloseable {
    private data class Cached(val version: Long, val game: StoredGame)

    private data class Row(
        val id: String,
        val revision: Int,
        val version: Long,
        val document: ObjectNode,
    )

    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)

    override fun create(game: StoredGame) {
        val root = encoded(game)
        database.write { connection ->
            val listing = GameDirectory.summary(game, null)
            connection
                .prepareStatement(
                    """
                    INSERT INTO games (id, document, revision, listing_status, stage)
                    VALUES (?, ?::jsonb, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, key(game.id))
                    statement.setString(2, metadata(root).toString())
                    statement.setInt(3, game.revision)
                    statement.setString(4, listing.status.name)
                    statement.setString(5, listing.stage?.name)
                    statement.executeUpdate()
                }
            insertRevisions(connection, game.id, game.revisions)
            writePlayers(connection, game)
        }
    }

    override fun read(id: String): StoredGame = database.read { connection ->
        val row = row(connection, id)
        if (connection.isReadOnly) {
            synchronized(cache) {
                cache[id]
                    ?.takeIf { it.version == row.version }
                    ?.let {
                        return@read it.game
                    }
            }
        }
        val game = decode(connection, row)
        if (connection.isReadOnly) {
            synchronized(cache) {
                cache[id] = Cached(row.version, game)
                if (cache.size > 32) cache.remove(cache.keys.first())
            }
        }
        game
    }

    override fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame =
        database.write { connection ->
            val row = row(connection, id, lock = true)
            val current = decode(connection, row)
            val next = transform(current)
            require(next.id == id) { "An update cannot change the game ID." }
            require(next.revisions.take(current.revisions.size) == current.revisions) {
                "Updates must retain every earlier immutable revision."
            }
            require(
                next.header == current.header &&
                    next.memberships == current.memberships &&
                    next.credentials == current.credentials
            ) {
                "Ordinary writes cannot change game registration or compatibility versions."
            }
            val root = encoded(next)
            insertRevisions(connection, id, next.revisions.drop(current.revisions.size))
            writePlayers(connection, next)
            writeRow(connection, row, next, root)
            next
        }

    override fun ids(after: String?, limit: Int): List<String> = database.read { connection ->
        require(limit > 0) { "A game-ID page requires a positive limit." }
        val cursor = after?.let(::key)
        connection
            .prepareStatement(
                "SELECT id FROM games WHERE (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ?"
            )
            .use { statement ->
                statement.setObject(1, cursor)
                statement.setObject(2, cursor)
                statement.setInt(3, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("id"))
                        }
                    }
                }
            }
    }

    override fun document(id: String): String = database.read { connection ->
        document(connection, row(connection, id)).toString()
    }

    override fun checkpoint(
        id: String,
        expectedRevision: Int,
        transform: (String, String) -> StoredGame,
    ): StoredGame = database.write { connection ->
        val row = row(connection, id, lock = true)
        if (row.revision != expectedRevision) throw ServiceFailure(409, "STALE_REVISION")
        val original = document(connection, row)
        val contents = original.toString()
        val hash =
            MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val reference = "$expectedRevision-$hash.json"
        val replacement = transform(contents, reference)
        require(
            replacement.id == id &&
                replacement.baseRevision == expectedRevision &&
                replacement.revision == expectedRevision + 1 &&
                replacement.previousArchive == reference
        ) {
            "A checkpoint must advance one revision and retain its preceding archive."
        }
        val root = encoded(replacement)
        require(
            root["header"]["players"] == original["header"]["players"] &&
                root["header"]["creatorId"] == original["header"]["creatorId"] &&
                root["memberships"] == original["memberships"] &&
                root["credentials"] == original["credentials"]
        ) {
            "A checkpoint cannot change game registration or membership."
        }
        retainArchive(id, reference, contents)
        connection.prepareStatement("DELETE FROM game_revisions WHERE game_id = ?").use { statement
            ->
            statement.setObject(1, key(id))
            statement.executeUpdate()
        }
        insertRevisions(connection, id, replacement.revisions)
        writePlayers(connection, replacement)
        writeRow(connection, row, replacement, root)
        replacement
    }

    override fun purge(id: String, expectedRevision: Int) {
        database.write { connection ->
            val row = row(connection, id, lock = true)
            if (row.revision != expectedRevision) throw ServiceFailure(409, "STALE_REVISION")
            connection.prepareStatement("DELETE FROM games WHERE id = ?").use { statement ->
                statement.setObject(1, key(id))
                statement.executeUpdate()
            }
        }
        synchronized(cache) { cache.remove(id) }
    }

    override fun archives(id: String): List<String> = database.read { connection ->
        connection
            .prepareStatement("SELECT name FROM game_archives WHERE game_id = ? ORDER BY name")
            .use { statement ->
                statement.setObject(1, key(id))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("name"))
                        }
                    }
                }
            }
    }

    override fun archivedDocument(id: String, name: String): String = database.read {
        archive(it, id, name)
    }

    /** Import retains exact archive bytes and rejects a name that does not match its contents. */
    internal fun retainArchive(id: String, name: String, contents: String) {
        GameDocuments.requireArchive(name)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        require(name.substringAfter('-').removeSuffix(".json") == hash) {
            "Archive contents must match their SHA-256 name."
        }
        database.write { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO game_archives (game_id, name, contents) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, key(id))
                    statement.setString(2, name)
                    statement.setString(3, contents)
                    if (statement.executeUpdate() == 0) {
                        require(archive(connection, id, name) == contents) {
                            "Existing archives are immutable."
                        }
                    }
                }
        }
    }

    /**
     * Filters read only indexed directory/membership projections, without parsing game histories.
     */
    override fun listGames(
        viewer: AccountId,
        scope: GameListScope,
        status: GameListStatus?,
        after: String?,
        limit: Int,
        accounts: AccountStore?,
    ): GamePage = database.read { connection ->
        val cursor = after?.let(::key)
        val sql =
            """
            WITH directory AS (
                SELECT g.id, g.revision, g.document, p.needs_move,
                    (SELECT jsonb_object_agg(m.seat, m.account_id)
                        FROM game_players m WHERE m.game_id = g.id) AS memberships,
                    (SELECT jsonb_agg(m.seat) FROM game_players m
                        WHERE m.game_id = g.id AND m.needs_move) AS action_players,
                    CASE WHEN g.document->>'fileVersion' <> '6'
                        OR g.document->'header'->>'rulesVersion' <> ?
                        OR g.document->'header'->>'dataVersion' <> ? THEN 'UNAVAILABLE'
                        ELSE g.listing_status END AS base_status,
                    g.stage
                FROM games g LEFT JOIN game_players p ON p.game_id = g.id AND p.account_id = ?
                WHERE (? OR (p.account_id IS NOT NULL) = ?)
                    AND (?::uuid IS NULL OR g.id > ?::uuid)
            )
            SELECT *, CASE WHEN base_status = 'ONGOING' AND needs_move
                THEN 'MY_MOVE' ELSE base_status END AS viewer_status
            FROM directory
            WHERE ?::text IS NULL OR base_status = ?
                OR (? = 'MY_MOVE' AND base_status = 'ONGOING' AND needs_move)
            ORDER BY id LIMIT ?
            """
                .trimIndent()
        val games =
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, RULES_VERSION)
                statement.setString(2, DATA_VERSION)
                statement.setString(3, viewer.value)
                statement.setBoolean(4, scope == GameListScope.ALL)
                statement.setBoolean(5, scope == GameListScope.OWN)
                statement.setObject(6, cursor)
                statement.setObject(7, cursor)
                statement.setString(8, status?.name)
                statement.setString(9, status?.name)
                statement.setString(10, status?.name)
                statement.setInt(11, limit + 1)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val root = json.readTree(rows.getString("document"))
                            val memberships = json.readTree(rows.getString("memberships"))
                            val viewStatus = GameListStatus.valueOf(rows.getString("viewer_status"))
                            add(
                                GameSummary(
                                    rows.getString("id"),
                                    root.field("createdAt").longInteger(),
                                    rows.getInt("revision"),
                                    viewStatus,
                                    if (viewStatus == GameListStatus.UNAVAILABLE) {
                                        null
                                    } else {
                                        Stage.valueOf(rows.getString("stage"))
                                    },
                                    root.field("header").field("players").array().map { player ->
                                        Player(
                                            PlayerId(player.field("id").string()),
                                            accounts?.playerName(
                                                memberships[player.field("id").string()]
                                                    ?.textValue(),
                                                player.field("name").string(),
                                            ) ?: player.field("name").string(),
                                        )
                                    },
                                    viewStatus == GameListStatus.MY_MOVE,
                                    GameDirectory.displayName(root),
                                    if (viewStatus == GameListStatus.UNAVAILABLE) {
                                        emptyList()
                                    } else {
                                        rows
                                            .getString("action_players")
                                            ?.let { actionPlayers ->
                                                json.readTree(actionPlayers).map {
                                                    PlayerId(it.asText())
                                                }
                                            }
                                            .orEmpty()
                                    },
                                )
                            )
                        }
                    }
                }
            }
        GamePage(games.take(limit), games.takeIf { it.size > limit }?.get(limit - 1)?.id)
    }

    private fun row(connection: Connection, id: String, lock: Boolean = false): Row {
        val sql =
            "SELECT id, revision, storage_version, document FROM games WHERE id = ?" +
                if (lock) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, key(id))
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw ServiceFailure(404, "GAME_NOT_FOUND")
                Row(
                    id,
                    rows.getInt("revision"),
                    rows.getLong("storage_version"),
                    json.readTree(rows.getString("document")) as ObjectNode,
                )
            }
        }
    }

    private fun decode(connection: Connection, row: Row): StoredGame =
        GameDocuments.read(row.id, document(connection, row))

    private fun document(connection: Connection, row: Row): ObjectNode {
        val root = row.document.deepCopy()
        root.put("id", row.id)
        root.put("revision", row.revision)
        val revisions = root.putArray("revisions")
        connection
            .prepareStatement(
                "SELECT revision, payload FROM game_revisions WHERE game_id = ? ORDER BY revision"
            )
            .use { statement ->
                statement.setObject(1, key(row.id))
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val revision = json.readTree(rows.getString("payload")) as ObjectNode
                        revision.put("id", rows.getInt("revision"))
                        revisions.add(revision)
                    }
                }
            }
        val memberships = root.putObject("memberships")
        val credentials = root.putObject("credentials")
        connection
            .prepareStatement(
                """
                SELECT seat, account_id, credential_hash FROM game_players
                WHERE game_id = ? ORDER BY seat
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, key(row.id))
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val account = rows.getString("account_id")
                        if (account != null) memberships.put(rows.getString("seat"), account)
                        else
                            credentials.put(
                                rows.getString("seat"),
                                rows.getString("credential_hash"),
                            )
                    }
                }
            }
        return root
    }

    private fun insertRevisions(connection: Connection, id: String, revisions: List<Revision>) {
        if (revisions.isEmpty()) return
        connection
            .prepareStatement(
                "INSERT INTO game_revisions (game_id, revision, payload) VALUES (?, ?, ?::jsonb)"
            )
            .use { statement ->
                revisions.forEach { revision ->
                    val payload = json.valueToTree<ObjectNode>(revision).apply { remove("id") }
                    statement.setObject(1, key(id))
                    statement.setInt(2, revision.id)
                    statement.setString(3, payload.toString())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun writePlayers(connection: Connection, game: StoredGame) {
        val actionPlayers = GameDirectory.summary(game, null).actionPlayers
        connection
            .prepareStatement(
                """
                INSERT INTO game_players (game_id, seat, account_id, credential_hash, needs_move)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (game_id, seat) DO UPDATE SET account_id = EXCLUDED.account_id,
                    credential_hash = EXCLUDED.credential_hash, needs_move = EXCLUDED.needs_move
                """
                    .trimIndent()
            )
            .use { statement ->
                game.header.players.forEach { player ->
                    val account = game.memberships[player.id]
                    statement.setObject(1, key(game.id))
                    statement.setString(2, player.id.value)
                    statement.setString(3, account)
                    statement.setString(4, game.credentials[player.id])
                    statement.setBoolean(
                        5,
                        player.id in actionPlayers,
                    )
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun writeRow(
        connection: Connection,
        previous: Row,
        game: StoredGame,
        root: ObjectNode,
    ) {
        val listing = GameDirectory.summary(game, null)
        connection
            .prepareStatement(
                """
                UPDATE games SET document = ?::jsonb, revision = ?,
                    storage_version = storage_version + 1, listing_status = ?, stage = ?
                WHERE id = ? AND storage_version = ?
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setString(1, metadata(root).toString())
                statement.setInt(2, game.revision)
                statement.setString(3, listing.status.name)
                statement.setString(4, listing.stage?.name)
                statement.setObject(5, key(game.id))
                statement.setLong(6, previous.version)
                if (statement.executeUpdate() != 1) throw ServiceFailure(409, "STALE_REVISION")
            }
    }

    private fun archive(connection: Connection, id: String, name: String): String {
        GameDocuments.requireArchive(name)
        return connection
            .prepareStatement("SELECT contents FROM game_archives WHERE game_id = ? AND name = ?")
            .use { statement ->
                statement.setObject(1, key(id))
                statement.setString(2, name)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw ServiceFailure(404, "ARCHIVE_NOT_FOUND")
                    rows.getString("contents")
                }
            }
    }

    private fun encoded(game: StoredGame): ObjectNode =
        json.readTree(GameDocuments.write(game)) as ObjectNode

    private fun metadata(root: ObjectNode): ObjectNode =
        root.deepCopy().apply {
            remove(listOf("id", "revision", "revisions", "memberships", "credentials"))
        }

    private fun key(id: String): UUID {
        GameDocuments.requireId(id)
        return UUID.fromString(id)
    }

    override fun close() {
        database.close()
    }
}
