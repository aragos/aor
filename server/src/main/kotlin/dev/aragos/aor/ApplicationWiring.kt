package dev.aragos.aor

import java.nio.file.Path

/** Composes the local file store with the game driver at the application's boundary. */
fun createGameService(
    directory: Path = Path.of(System.getenv("AOR_DATA_DIR") ?: ".aor-data").toAbsolutePath(),
    random: SetupRandom = AuthorityRandom(),
    writer: GameWriter = AtomicGameWriter,
): GameService = GameService(store = FileGameStore(directory, writer), driver = GameDriver(random))

/** Local private-link mode is only for loopback development and deterministic engine checks. */
fun createHostedServices(
    service: GameService,
    environment: Map<String, String> = System.getenv(),
): HostedServices? {
    val mode = environment["AOR_AUTH_MODE"] ?: "local"
    require(mode in setOf("local", "firebase")) { "AOR_AUTH_MODE must be local or firebase." }
    if (mode == "local") return null
    require(environment["FIREBASE_AUTH_EMULATOR_HOST"] == null) {
        "Hosted mode cannot accept authentication-emulator tokens."
    }
    val project =
        checkNotNull(environment["AOR_FIREBASE_PROJECT_ID"]) {
            "Firebase mode requires AOR_FIREBASE_PROJECT_ID."
        }
    require(project.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]"))) {
        "Firebase project IDs must be 6–30 lowercase letters, digits or hyphens."
    }
    val apiKey =
        checkNotNull(environment["AOR_FIREBASE_WEB_API_KEY"]) {
            "Firebase mode requires AOR_FIREBASE_WEB_API_KEY."
        }
    require(apiKey.isNotBlank()) { "The Firebase web API key cannot be blank." }
    val store =
        service.store as? FileGameStore ?: error("Hosted mode requires operational game storage.")
    val administrators =
        environment["AOR_ADMIN_UIDS"]
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::AccountId)
            .toSet()
    val creationPolicy =
        when (environment["AOR_GAME_CREATION_POLICY"] ?: "approved") {
            "approved" -> GameCreationPolicy.APPROVED
            "registered" -> GameCreationPolicy.REGISTERED
            else -> error("AOR_GAME_CREATION_POLICY must be approved or registered.")
        }
    return HostedServices(
        service,
        AccountStore(store.directory.resolve("accounts.json")),
        FirebaseIdentityVerifier(project),
        administrators,
        creationPolicy,
        PublicApplicationConfig(
            "firebase",
            mapOf(
                "projectId" to project,
                "apiKey" to apiKey,
                "authDomain" to "$project.firebaseapp.com",
            ),
            5000,
        ),
        GameAdministration(store, AdministrationAudit(store.directory.resolve("admin-audit"))),
    )
}
