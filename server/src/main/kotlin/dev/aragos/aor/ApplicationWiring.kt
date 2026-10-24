package dev.aragos.aor

import java.nio.file.Path

/** Composes the local file store with the game driver at the application's boundary. */
fun createGameService(
    directory: Path = Path.of(System.getenv("AOR_DATA_DIR") ?: ".aor-data").toAbsolutePath(),
    random: SetupRandom = AuthorityRandom(),
    writer: GameWriter = AtomicGameWriter,
): GameService = GameService(store = FileGameStore(directory, writer), driver = GameDriver(random))
