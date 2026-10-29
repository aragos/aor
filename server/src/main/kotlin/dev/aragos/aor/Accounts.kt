package dev.aragos.aor

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stable managed-authentication UID; never used as a filesystem path. */
data class AccountId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) {
            "An account identifier requires one through 128 printable characters."
        }
    }
}

/** Unique public identity used throughout hosted play. */
data class PlayerHandle(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9-]{2,23}"))) {
            "Handles require 3–24 lowercase letters, digits or hyphens, starting with a letter."
        }
    }

    companion object {
        fun parse(value: String): PlayerHandle = PlayerHandle(value.trim().lowercase())
    }
}

data class RegisteredAccount(
    val id: AccountId,
    val handle: PlayerHandle,
    val mayCreateGames: Boolean = false,
) {
    companion object {
        /** Canonicalizes registration input; intrinsic handle checks remain in its type. */
        fun create(id: AccountId, handle: String): RegisteredAccount =
            try {
                RegisteredAccount(id, PlayerHandle.parse(handle))
            } catch (_: IllegalArgumentException) {
                throw ServiceFailure(400, "INVALID_ACCOUNT")
            }
    }
}

/** Registered identities and creator permissions contain no passwords or email addresses. */
interface AccountStore {
    fun all(): List<RegisteredAccount>

    fun find(id: AccountId): RegisteredAccount?

    fun register(id: AccountId, handle: String): RegisteredAccount

    fun allowCreation(id: AccountId, allowed: Boolean): RegisteredAccount

    fun search(query: String, limit: Int = 20): List<RegisteredAccount> {
        val normalized = query.lowercase()
        return all()
            .filter { normalized in it.handle.value || query == it.id.value }
            .sortedBy { it.handle.value }
            .take(limit)
    }
}

/** Account directory contains no passwords, refresh tokens or email addresses. */
class FileAccountStore(private val path: Path, private val writer: GameWriter = AtomicGameWriter) :
    AccountStore {
    private val lock = ReentrantLock()

    override fun all(): List<RegisteredAccount> = lock.withLock { load() }

    override fun find(id: AccountId): RegisteredAccount? = all().singleOrNull { it.id == id }

    override fun register(id: AccountId, handle: String): RegisteredAccount = lock.withLock {
        val accounts = load()
        if (accounts.any { it.id == id }) {
            throw ServiceFailure(409, "ACCOUNT_ALREADY_REGISTERED")
        }
        val account = RegisteredAccount.create(id, handle)
        if (accounts.any { it.handle == account.handle }) {
            throw ServiceFailure(409, "HANDLE_UNAVAILABLE")
        }
        save(accounts + account)
        account
    }

    override fun allowCreation(id: AccountId, allowed: Boolean): RegisteredAccount = lock.withLock {
        val accounts = load()
        val account =
            accounts.singleOrNull { it.id == id } ?: throw ServiceFailure(404, "ACCOUNT_NOT_FOUND")
        val updated = account.copy(mayCreateGames = allowed)
        save(accounts.map { if (it.id == id) updated else it })
        updated
    }

    private fun load(): List<RegisteredAccount> {
        if (!Files.exists(path)) return emptyList()
        try {
            val root = json.readTree(Files.readString(path))
            require(root.field("fileVersion").integer() == 2) {
                "Only account file version 2 is supported."
            }
            val accounts =
                root.field("accounts").array().map { node ->
                    RegisteredAccount(
                        AccountId(node.field("id").string()),
                        PlayerHandle(node.field("handle").string()),
                        node.field("mayCreateGames").boolean(),
                    )
                }
            require(accounts.map(RegisteredAccount::id).distinct().size == accounts.size) {
                "Account IDs must be unique."
            }
            require(accounts.map(RegisteredAccount::handle).distinct().size == accounts.size) {
                "Account handles must be unique."
            }
            return accounts
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    private fun save(accounts: List<RegisteredAccount>) {
        val contents =
            mapOf(
                "fileVersion" to 2,
                "accounts" to
                    accounts.map { account ->
                        mapOf(
                            "id" to account.id.value,
                            "handle" to account.handle.value,
                            "mayCreateGames" to account.mayCreateGames,
                        )
                    },
            )
        try {
            writer.write(path, json.writeValueAsString(contents))
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }
}

/** Live hosted labels use handles; recorded history retains its entry-time identities. */
internal fun AccountStore.playerName(accountId: String?, fallback: String): String =
    accountId?.let(::AccountId)?.let(::find)?.let { "@${it.handle.value}" } ?: fallback
