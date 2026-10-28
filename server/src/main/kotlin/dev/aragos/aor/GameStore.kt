package dev.aragos.aor

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Implementations must make [update]'s read, transformation, and durable write atomic per game. */
interface GameStore {
    fun create(game: StoredGame)

    fun read(id: String): StoredGame

    /** A failed transformation or write must leave the previous value available. */
    fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame
}

/** Operational storage supports validated checkpoints without interpreting game rules. */
interface ManagedGameStore : GameStore {
    fun ids(after: String? = null, limit: Int = Int.MAX_VALUE): List<String>

    /** Lists use rebuildable engine projections; authorization scope is supplied by the service. */
    fun listGames(
        viewer: AccountId,
        admin: Boolean,
        status: GameListStatus?,
        after: String?,
        limit: Int,
    ): GamePage = GameDirectory.list(this, viewer, admin, status, after, limit)

    fun document(id: String): String

    fun checkpoint(
        id: String,
        expectedRevision: Int,
        transform: (String, String) -> StoredGame,
    ): StoredGame

    fun purge(id: String, expectedRevision: Int)

    fun archives(id: String): List<String>

    fun archivedDocument(id: String, name: String): String
}

fun interface GameWriter {
    fun write(path: Path, contents: String)
}

object AtomicGameWriter : GameWriter {
    override fun write(path: Path, contents: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".game-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) {
                    channel.write(bytes)
                }
                channel.force(true)
            }
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/**
 * Current-format local storage. Revisions are immutable deltas with explicit suffix replacements.
 */
class FileGameStore(
    internal val directory: Path,
    private val writer: GameWriter = AtomicGameWriter,
) : ManagedGameStore {
    private val locks = Array(256) { ReentrantLock() }

    private data class Cached(val contents: String, val game: StoredGame)

    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)

    override fun create(game: StoredGame): Unit =
        lock(game.id).withLock {
            if (Files.exists(path(game.id))) throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
            persist(game)
        }

    override fun read(id: String): StoredGame = lock(id).withLock { load(id) }

    override fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame =
        lock(id).withLock {
            val next = transform(load(id))
            require(next.id == id) { "An update cannot change the game's identifier." }
            persist(next)
            next
        }

    override fun ids(after: String?, limit: Int): List<String> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) &&
                        it.fileName
                            .toString()
                            .removeSuffix(".json")
                            .matches(GameDocuments.GAME_ID_PATTERN) &&
                        it.fileName.toString().endsWith(".json")
                }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .filter { after == null || it > after }
                .limit(limit.toLong())
                .toList()
        }
    }

    override fun document(id: String): String = lock(id).withLock { raw(id) }

    override fun checkpoint(
        id: String,
        expectedRevision: Int,
        transform: (String, String) -> StoredGame,
    ): StoredGame =
        lock(id).withLock {
            val original = raw(id)
            checkRevision(original, expectedRevision)
            val hash =
                MessageDigest.getInstance("SHA-256").digest(original.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            val reference = "$expectedRevision-$hash.json"
            val replacement = transform(original, reference)
            require(replacement.id == id) { "A checkpoint cannot change the game ID." }
            GameDocuments.validate(replacement)
            val archive = directory.resolve("archives").resolve(id).resolve(reference)
            if (Files.exists(archive)) {
                require(Files.readString(archive) == original) {
                    "Existing archives are immutable."
                }
            } else {
                writer.write(archive, original)
            }
            persist(replacement)
            replacement
        }

    override fun purge(id: String, expectedRevision: Int): Unit =
        lock(id).withLock {
            checkRevision(raw(id), expectedRevision)
            val archive = directory.resolve("archives").resolve(id)
            if (Files.exists(archive)) {
                Files.walk(archive).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
            Files.delete(path(id))
            synchronized(cache) { cache.remove(id) }
        }

    override fun archives(id: String): List<String> =
        lock(id).withLock {
            val archive = directory.resolve("archives").resolve(id)
            if (!Files.exists(archive)) {
                emptyList()
            } else {
                Files.list(archive).use { paths ->
                    paths
                        .map { it.fileName.toString() }
                        .filter(GameDocuments.ARCHIVE_PATTERN::matches)
                        .sorted()
                        .toList()
                }
            }
        }

    override fun archivedDocument(id: String, name: String): String =
        lock(id).withLock {
            if (!name.matches(GameDocuments.ARCHIVE_PATTERN))
                throw ServiceFailure(404, "ARCHIVE_NOT_FOUND")
            val archive = directory.resolve("archives").resolve(id).resolve(name)
            if (!Files.isRegularFile(archive)) throw ServiceFailure(404, "ARCHIVE_NOT_FOUND")
            Files.readString(archive)
        }

    private fun raw(id: String): String {
        if (!Files.exists(path(id))) throw ServiceFailure(404, "GAME_NOT_FOUND")
        return Files.readString(path(id))
    }

    private fun checkRevision(contents: String, expected: Int) {
        if (json.readTree(contents).field("revision").integer() != expected) {
            throw ServiceFailure(409, "STALE_REVISION")
        }
    }

    private fun remember(id: String, contents: String, game: StoredGame) =
        synchronized(cache) {
            cache[id] = Cached(contents, game)
            if (cache.size > 32) cache.remove(cache.keys.first())
        }

    private fun lock(id: String): ReentrantLock {
        if (!id.matches(GameDocuments.GAME_ID_PATTERN)) throw ServiceFailure(404, "GAME_NOT_FOUND")
        return locks[Math.floorMod(id.hashCode(), locks.size)]
    }

    private fun path(id: String): Path = directory.resolve("$id.json")

    private fun persist(game: StoredGame) {
        try {
            val contents = GameDocuments.write(game)
            writer.write(path(game.id), contents)
            remember(game.id, contents, game)
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    private fun load(id: String): StoredGame {
        val path = path(id)
        if (!Files.exists(path)) throw ServiceFailure(404, "GAME_NOT_FOUND")
        try {
            val contents = Files.readString(path)
            synchronized(cache) {
                cache[id]
                    ?.takeIf { it.contents == contents }
                    ?.let {
                        return it.game
                    }
            }
            val stored = GameDocuments.read(id, contents)
            remember(id, contents, stored)
            return stored
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }
}
