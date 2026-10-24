package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Implementations must make [update]'s read, transformation, and durable write atomic per game. */
interface GameStore {
    fun create(game: StoredGame)

    fun read(id: String): StoredGame

    /** A failed transformation or write must leave the previous value available. */
    fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame
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

/** Version-three local storage. Revisions remain immutable deltas in one append-only chain. */
class FileGameStore(
    private val directory: Path,
    private val writer: GameWriter = AtomicGameWriter,
) : GameStore {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

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

    private fun lock(id: String): ReentrantLock {
        if (!id.matches(GAME_ID_PATTERN)) throw ServiceFailure(404, "GAME_NOT_FOUND")
        return locks.computeIfAbsent(id) { ReentrantLock() }
    }

    private fun path(id: String): Path = directory.resolve("$id.json")

    private fun persist(game: StoredGame) {
        try {
            validate(game)
            writer.write(path(game.id), json.writeValueAsString(game))
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    private fun load(id: String): StoredGame {
        val path = path(id)
        if (!Files.exists(path)) throw ServiceFailure(404, "GAME_NOT_FOUND")
        try {
            val root = json.readTree(Files.readString(path))
            require(root.field("id").string() == id) {
                "Stored game identifier must match its file."
            }
            val stored = decode(root)
            require(root.field("revision").integer() == stored.revision) {
                "Active revision must match the final committed revision."
            }
            validate(stored)
            return stored
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    private fun decode(root: JsonNode): StoredGame {
        val node = root.field("header")
        val registeredPlayers =
            node.field("players").array().map { player ->
                Player(
                    id = PlayerId(player.field("id").string()),
                    name = player.field("name").string(),
                )
            }
        val header =
            GameHeader(
                players = registeredPlayers,
                creatorId = PlayerId(node.field("creatorId").string()),
                rulesVersion = node.field("rulesVersion").string(),
                dataVersion = node.field("dataVersion").string(),
            )
        val credentialNode = root.field("credentials")
        require(credentialNode.isObject) {
            "Credentials must be an object keyed by player identifier."
        }
        val returnNode = root.field("pendingReturns")
        require(returnNode.isObject) { "Pending returns must be keyed by player identifier." }
        val pendingNode = root.field("pendingBids")
        require(pendingNode.isObject) {
            "Pending bids must be an object keyed by player identifier."
        }
        return StoredGame(
            fileVersion = root.field("fileVersion").integer(),
            id = root.field("id").string(),
            header = header,
            credentials =
                credentialNode.properties().associate { (player, credential) ->
                    PlayerId(player) to credential.string()
                },
            revisions =
                root.field("revisions").array().map { revision ->
                    Revision(
                        id = revision.field("id").integer(),
                        parent =
                            revision.field("parent").let { parent ->
                                if (parent.isNull) null else parent.integer()
                            },
                        author = PlayerId(revision.field("author").string()),
                        command = revision.field("command").string(),
                        entries = revision.field("entries").array().map(JsonNode::string),
                    )
                },
            pendingReturns =
                returnNode.properties().associate { (player, reference) ->
                    PlayerId(player) to reference.string()
                },
            pendingBids =
                pendingNode.properties().associate { (player, bid) ->
                    PlayerId(player) to bid.integer()
                },
        )
    }

    private fun validate(game: StoredGame) {
        require(game.fileVersion == 3) { "Only game file version 3 is supported." }
        require(game.credentials.keys == game.header.players.map(Player::id).toSet()) {
            "Every registered player must have exactly one credential."
        }
        require(game.credentials.values.all(CREDENTIAL_PATTERN::matches)) {
            "Credentials must be SHA-256 hashes encoded as 64 lowercase hexadecimal characters."
        }
        require(game.credentials.values.distinct().size == game.credentials.size) {
            "Player credentials must be unique."
        }
        require(game.revisions.isNotEmpty()) {
            "A game must have an initial configuration revision."
        }
        val state = validateRevisions(game.header, game.revisions)
        if (game.pendingBids.isNotEmpty()) {
            val participants =
                (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
            require(
                participants.containsAll(game.pendingBids.keys) &&
                    game.pendingBids.size < participants.size
            ) {
                "Pending sealed decisions must form an incomplete batch of the current participants."
            }
        }
        if (game.pendingReturns.isNotEmpty()) {
            val participants =
                (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
            require(
                state.stage == Stage.SETUP_RETURN &&
                    participants.containsAll(game.pendingReturns.keys) &&
                    game.pendingReturns.size < participants.size &&
                    game.pendingBids.isEmpty()
            ) {
                "Pending setup returns must be an incomplete batch in the setup-return phase."
            }
            game.pendingReturns.forEach { (player, reference) ->
                state.step(Entry.Action(player, SetupCommand.ReturnCard(reference)))
            }
        }
        game.pendingBids.forEach { (player, amount) ->
            val command =
                when (state.stage) {
                    Stage.BIDDING -> Command.BidCapital(amount)
                    Stage.TOKEN_PURCHASE -> Command.BuyTokens(amount)
                    else ->
                        error("Pending sealed decisions require a bidding or token-purchase phase.")
                }
            state.step(Entry.Action(actor = player, command = command))
        }
    }

    private fun validateRevisions(header: GameHeader, revisions: List<Revision>): GameState =
        revisions.withIndex().fold(GameState(header)) { state, (index, revision) ->
            validateRevision(state, index, revision)
        }

    private fun validateRevision(state: GameState, index: Int, revision: Revision): GameState {
        require(revision.id == index + 1 && revision.parent == if (index == 0) null else index) {
            "Revisions must form a consecutive chain starting with revision 1."
        }
        require(revision.author in state.playerIds) {
            "Revision authors must be registered players."
        }
        val entries = revision.entries.map(TextCodec::parse)
        val actions = entries.filterIsInstance<Entry.Action>()
        require(entries.isNotEmpty() && entries.take(actions.size) == actions) {
            "Each revision must contain player actions followed by system outcomes."
        }
        validateActions(state, revision, actions, entries, index)
        require(revision.entries == entries.map(TextCodec::print)) {
            "Stored entries must use canonical text."
        }
        val next = entries.fold(state) { current, entry -> current.step(entry) }
        validateCompletedRevision(next)
        return next
    }

    private fun validateCompletedRevision(state: GameState) {
        require(state.prompts().none { prompt -> prompt is Prompt.System }) {
            "A revision cannot stop before required system outcomes have completed."
        }
        require(
            state.stage != Stage.BIDDING ||
                state.players.values.all { player -> player.capitalBid == null }
        ) {
            "Sealed bids may enter history only as a complete, revealed batch."
        }
        require(state.stage != Stage.SETUP_RETURN || state.deckHistory.setupReturns.isEmpty()) {
            "Setup returns may enter history only as a complete private batch."
        }
        require(
            state.stage != Stage.TOKEN_PURCHASE ||
                state.players.values
                    .filter { player -> player.misery != MiseryStep.CHAOS }
                    .all { player -> player.tokenPurchase == null }
        ) {
            "Sealed token purchases may enter history only as a complete, revealed batch."
        }
    }

    private fun validateActions(
        state: GameState,
        revision: Revision,
        actions: List<Entry.Action>,
        entries: List<Entry>,
        index: Int,
    ) {
        when (revision.command) {
            "reveal-capital-bids" -> {
                require(state.stage == Stage.BIDDING) {
                    "Capital bids can only reveal during bidding."
                }
                validateSealedActions(state, actions) { command -> command is Command.BidCapital }
            }
            "reveal-token-purchases" -> {
                require(state.stage == Stage.TOKEN_PURCHASE) {
                    "Token purchases can only reveal during the token-purchase phase."
                }
                validateSealedActions(state, actions) { command -> command is Command.BuyTokens }
            }
            "reveal-setup-returns" -> {
                require(state.stage == Stage.SETUP_RETURN) {
                    "Setup returns can only reveal during improved setup."
                }
                validateSealedActions(state, actions) { command ->
                    command is SetupCommand.ReturnCard
                }
            }
            else -> validateCommandActions(state, revision, actions, entries, index)
        }
    }

    private fun validateCommandActions(
        state: GameState,
        revision: Revision,
        actions: List<Entry.Action>,
        entries: List<Entry>,
        index: Int,
    ) {
        val command = TextCodec.parseCommand(revision.command)
        require(
            command !is Command.BidCapital &&
                command !is Command.BuyTokens &&
                command !is SetupCommand.ReturnCard
        ) {
            "Sealed decisions must be committed together under their reveal marker."
        }
        require(revision.command == command.toText()) {
            "Revision commands must use canonical text."
        }
        val action = Entry.Action(actor = revision.author, command = command)
        if (index != 0) {
            val expected =
                if (command is RuleOptionCommand && command.key == "preset") {
                    command.resolve(state.rules).configurationCommands().map { option ->
                        Entry.Action(revision.author, option)
                    }
                } else listOf(action)
            require(actions == expected) {
                "A command revision must contain exactly the author's command."
            }
            return
        }
        require(
            revision.author == state.header.creatorId &&
                action == initialConfiguration(revision.author).first() &&
                entries == initialConfiguration(revision.author)
        ) {
            "The initial revision must explicitly configure every resolved default."
        }
    }

    private fun validateSealedActions(
        state: GameState,
        actions: List<Entry.Action>,
        accepts: (Command) -> Boolean,
    ) {
        val participants =
            (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
        require(
            actions.map(Entry.Action::actor) == state.playerIds.filter(participants::contains) &&
                actions.all { action -> accepts(action.command) }
        ) {
            "A revealed batch must include the current sealed decision for every player in registration order."
        }
    }

    private companion object {
        val GAME_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val CREDENTIAL_PATTERN = Regex("[0-9a-f]{64}")
    }
}
