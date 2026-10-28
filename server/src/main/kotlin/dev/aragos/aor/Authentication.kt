package dev.aragos.aor

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import java.time.Clock
import java.util.UUID

/** Authentication claims are distinct from registered game participants and application roles. */
data class AccountIdentity(val id: AccountId, val emailVerified: Boolean)

fun interface IdentityVerifier {
    fun verify(token: String): AccountIdentity
}

/**
 * The official SDK verifies signatures, issuer, audience, expiry, revocation and disabled users.
 */
class FirebaseIdentityVerifier(project: String, private val clock: Clock = Clock.systemUTC()) :
    IdentityVerifier, AutoCloseable {
    private val app =
        FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setProjectId(project)
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .build(),
            "aor-${UUID.randomUUID()}",
        )
    private val auth = FirebaseAuth.getInstance(app)

    private data class Cached(val identity: AccountIdentity, val until: Long)

    private val cache = LinkedHashMap<String, Cached>(32, .75f, true)

    override fun verify(token: String): AccountIdentity {
        if (token.length !in 100..16384) throw ServiceFailure(401, "UNAUTHORIZED")
        val now = clock.millis()
        synchronized(cache) {
            cache[token]
                ?.takeIf { it.until > now }
                ?.let {
                    return it.identity
                }
        }
        val verified =
            try {
                auth.verifyIdToken(token, true)
            } catch (_: FirebaseAuthException) {
                throw ServiceFailure(401, "UNAUTHORIZED")
            }
        val identity = AccountIdentity(AccountId(verified.uid), verified.isEmailVerified)
        val expiry = (verified.claims["exp"] as? Number)?.toLong()?.times(1000) ?: now
        synchronized(cache) {
            cache[token] = Cached(identity, minOf(now + 30000, expiry))
            if (cache.size > 512) cache.remove(cache.keys.first())
        }
        return identity
    }

    override fun close() {
        app.delete()
    }
}

enum class GameCreationPolicy {
    APPROVED,
    REGISTERED,
}

data class AccountView(
    val id: String,
    val handle: String,
    val displayName: String,
    val mayCreateGames: Boolean,
    val admin: Boolean,
)

data class SessionView(val id: String, val emailVerified: Boolean, val account: AccountView?)

data class PublicApplicationConfig(
    val authMode: String,
    val firebase: Map<String, String>?,
    val pollIntervalMs: Int,
)

/** Application authorization uses verified UIDs; player-facing names never grant access. */
class HostedServices(
    val games: GameService,
    val accounts: AccountStore,
    val verifier: IdentityVerifier,
    private val administrators: Set<AccountId>,
    private val creationPolicy: GameCreationPolicy,
    val publicConfig: PublicApplicationConfig,
    val administration: GameAdministration,
    private val transactions: StorageTransactions = FileTransactions,
) {
    fun identity(token: String): AccountIdentity = verifier.verify(token)

    fun account(identity: AccountIdentity): RegisteredAccount {
        if (!identity.emailVerified) throw ServiceFailure(403, "EMAIL_NOT_VERIFIED")
        return accounts.find(identity.id) ?: throw ServiceFailure(403, "REGISTRATION_REQUIRED")
    }

    fun isAdmin(id: AccountId): Boolean = id in administrators

    fun requireAdmin(identity: AccountIdentity): RegisteredAccount {
        val account = account(identity)
        if (!isAdmin(account.id)) throw ServiceFailure(403, "ADMIN_REQUIRED")
        return account
    }

    fun mayCreate(account: RegisteredAccount): Boolean =
        isAdmin(account.id) ||
            creationPolicy == GameCreationPolicy.REGISTERED ||
            account.mayCreateGames

    fun view(account: RegisteredAccount): AccountView =
        AccountView(
            account.id.value,
            account.handle.value,
            account.displayName,
            mayCreate(account),
            isAdmin(account.id),
        )

    fun session(identity: AccountIdentity): SessionView =
        SessionView(
            identity.id.value,
            identity.emailVerified,
            accounts.find(identity.id)?.let(::view),
        )

    /** Permission changes and their SQL audit receipt form one committed operation. */
    fun allowCreation(identity: AccountIdentity, target: AccountId, allowed: Boolean): AccountView {
        val admin = requireAdmin(identity)
        return transactions.transaction {
            if (accounts.find(target) == null) throw ServiceFailure(404, "ACCOUNT_NOT_FOUND")
            administration.recordCreationPermission(admin.id, target, allowed)
            view(accounts.allowCreation(target, allowed))
        }
    }

    fun create(
        identity: AccountIdentity,
        ids: List<String>,
        rules: RuleOptions,
        policy: UndoPolicy,
    ): CreatedGame {
        val creator = account(identity)
        if (!mayCreate(creator)) throw ServiceFailure(403, "GAME_CREATION_FORBIDDEN")
        if (ids.size !in 3..6 || ids.distinct().size != ids.size) {
            throw ServiceFailure(400, "INVALID_GAME")
        }
        val players = ids.map { id ->
            accounts.find(AccountId(id)) ?: throw ServiceFailure(400, "UNREGISTERED_PLAYER")
        }
        return games.createRegistered(players, creator.id, rules, policy)
    }
}
