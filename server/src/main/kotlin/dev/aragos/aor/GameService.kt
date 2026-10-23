package dev.aragos.aor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class ServiceFailure(val status: Int, val code: String) : Exception(code)

data class PlayerLink(val id: PlayerId, val name: String, val path: String)

data class CreatedGame(val gameId: String, val links: List<PlayerLink>)

data class PromptView(val messageId: String, val players: List<PlayerId>)

data class GameSnapshot(
    val gameId: String,
    val revision: Int,
    val viewerId: PlayerId,
    val creatorId: PlayerId,
    val game: GameView,
    val prompt: PromptView?,
    val legalChoices: LegalChoices,
    val submitted: List<PlayerId>,
    val pendingBid: Int?,
    val history: List<String>,
)

/** Authenticates players and projects the driver's state for the HTTP API. */
class GameService(
    private val store: GameStore,
    private val driver: GameDriver = GameDriver(),
) {
    private val credentialsRandom = SecureRandom()

    fun create(names: List<String>, epochs: Int): CreatedGame {
        val normalized = names.map(String::trim)
        if (
            normalized.map(String::lowercase).distinct().size != normalized.size || epochs !in 1..3
        ) {
            throw ServiceFailure(400, "INVALID_GAME")
        }
        val header =
            try {
                GameHeader(
                    creatorId = PlayerId("p1"),
                    players =
                        normalized.mapIndexed { index, name ->
                            Player(id = PlayerId("p${index + 1}"), name = name)
                        },
                )
            } catch (_: IllegalArgumentException) {
                throw ServiceFailure(400, "INVALID_GAME")
            }
        val id = UUID.randomUUID().toString()
        val players = header.players
        val tokens = players.associate { player ->
            player.id to
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ByteArray(32).also(credentialsRandom::nextBytes))
        }
        store.create(
            driver.create(
                id = id,
                header = header,
                credentials = tokens.mapValues { (_, token) -> digest(token) },
                epochs = epochs,
            )
        )
        return CreatedGame(
            gameId = id,
            links =
                players.map { player ->
                    PlayerLink(
                        id = player.id,
                        name = player.name,
                        path = "/games/$id#token=${tokens.getValue(player.id)}",
                    )
                },
        )
    }

    fun get(id: String, token: String): GameSnapshot {
        val stored = store.read(id)
        return snapshot(stored = stored, player = authenticate(stored, token))
    }

    fun submit(id: String, token: String, expectedRevision: Int, text: String): GameSnapshot {
        val stored =
            store.update(id) { current ->
                val player = authenticate(current, token)
                if (current.revision != expectedRevision) {
                    throw ServiceFailure(409, "STALE_REVISION")
                }
                driver.submit(
                    stored = current,
                    player = player,
                    command = TextCodec.parseCommand(text),
                )
            }
        return snapshot(stored = stored, player = authenticate(stored, token))
    }

    private fun snapshot(stored: StoredGame, player: PlayerId): GameSnapshot {
        val state = stored.state()
        val prompt =
            when (val current = state.prompts().singleOrNull()) {
                is Prompt.SinglePlayer ->
                    PromptView(
                        messageId = "prompt.${current.choice}",
                        players = listOf(current.player),
                    )
                is Prompt.MultiPlayer ->
                    PromptView(
                        messageId = "prompt.${current.choice}",
                        players = current.players.filterNot(stored.pendingBids::containsKey),
                    )
                else -> null
            }
        val history =
            stored.revisions.flatMap(Revision::entries).map { line ->
                when (val entry = TextCodec.parse(line)) {
                    is Entry.Shuffle -> "!shuffle <hidden>"
                    is Entry.Deal ->
                        if (entry.player == player) {
                            line
                        } else {
                            "!deal ${entry.player} ${entry.reference} <hidden>"
                        }
                    else -> line
                }
            }
        return GameSnapshot(
            gameId = stored.id,
            revision = stored.revision,
            viewerId = player,
            creatorId = stored.header.creatorId,
            game = state.viewForPlayer(player),
            prompt = prompt,
            legalChoices = state.legalChoices(player),
            submitted = state.playerIds.filter(stored.pendingBids::containsKey),
            pendingBid = stored.pendingBids[player],
            history = history,
        )
    }

    private fun authenticate(stored: StoredGame, token: String): PlayerId {
        if (token.length !in 20..100) throw ServiceFailure(401, "UNAUTHORIZED")
        val hash = digest(token)
        return stored.credentials.entries
            .firstOrNull { (_, credential) ->
                MessageDigest.isEqual(credential.toByteArray(), hash.toByteArray())
            }
            ?.key ?: throw ServiceFailure(401, "UNAUTHORIZED")
    }
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte ->
        "%02x".format(byte)
    }
