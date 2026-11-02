package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test

class CatalogCompatibilityTest {
    private val owner = PlayerId("p1")

    @Test
    fun postgresListsCatalog13GamesAsPlayableForTheirMembers() =
        PostgresFixture().use { fixture ->
            val original = legacyGame(MapRule.STANDARD)
            val memberships =
                original.header.players.associate { player ->
                    val account = AccountId("legacy-${player.id.value}")
                    fixture.accounts.register(account, "legacy-${player.id.value}")
                    player.id to account.value
                }
            val registered = original.copy(credentials = emptyMap(), memberships = memberships)
            fixture.store.create(registered)
            val account =
                AccountId(requireNotNull(memberships[owner]) { "The owner requires an account." })

            val listing = fixture.store.listGames(account, GameListScope.OWN, null, null, 10)

            assertThat(listing.games.map(GameSummary::id)).containsExactly(registered.id)
            assertThat(listing.games.single().status).isEqualTo(GameListStatus.MY_MOVE)
            assertThat(fixture.store.read(registered.id)).isEqualTo(registered)
        }

    @Test
    fun catalog13SavesReplayTheirWalesLandMoveAndContinueAfterRestart() {
        for (map in MapRule.entries) {
            val directory = Files.createTempDirectory("aor-catalog13-")
            try {
                val before = legacyCornwallGame(map)
                FileGameStore(directory).create(before)
                val reopened = FileGameStore(directory)

                val restored = reopened.read(before.id)
                val continued = GameDriver(FixedRandom()).submit(restored, owner, "pass")
                reopened.update(before.id) { continued }

                assertThat(restored.header).isEqualTo(before.header)
                assertThat(restored.effectiveEntries()).isEqualTo(before.effectiveEntries())
                assertThat(restored.state()).isEqualTo(before.state())
                assertThat(restored.state().board.pieces[ProvinceId("cornwall")]).isNotNull()
                assertThat(continued.effectiveEntries().take(before.effectiveEntries().size))
                    .isEqualTo(before.effectiveEntries())
                assertThat(reopened.read(before.id).header.dataVersion)
                    .isEqualTo("ah1996-catalog-13")
                assertThat(reopened.read(before.id).state()).isEqualTo(continued.state())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun catalog13PrivateBidsAndMetadataSurviveRestartAndFurtherSubmission() {
        val directory = Files.createTempDirectory("aor-catalog13-private-")
        try {
            val driver = GameDriver(FixedRandom())
            val original =
                driver
                    .submit(legacyGame(MapRule.EUROPEAN), owner, "bid-capital 7")
                    .copy(name = GameName("Existing table"))
            FileGameStore(directory).create(original)

            val reopened = FileGameStore(directory).read(original.id)
            val updated = driver.submit(reopened, owner, "bid-capital 8")

            assertThat(reopened).isEqualTo(original)
            assertThat(updated.name).isEqualTo(original.name)
            assertThat(updated.createdAt).isEqualTo(original.createdAt)
            assertThat(updated.credentials).isEqualTo(original.credentials)
            assertThat(updated.header).isEqualTo(original.header)
            assertThat(updated.pendingBids[owner]).isEqualTo(8)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun catalog14SavesReplayAndContinueWithTheirRecordedConnectionsAfterRestart() {
        for (map in MapRule.entries) {
            val directory = Files.createTempDirectory("aor-catalog14-")
            try {
                val before =
                    legacyCornwallGame(
                        map,
                        version = CatalogVersion.CATALOG_14,
                        route = listOf("portsmouth", "cornwall"),
                    )
                FileGameStore(directory).create(before)
                val reopened = FileGameStore(directory)

                val restored = reopened.read(before.id)
                val continued = GameDriver(FixedRandom()).submit(restored, owner, "pass")
                reopened.update(before.id) { continued }

                assertThat(restored).isEqualTo(before)
                assertThat(restored.state()).isEqualTo(before.state())
                assertThat(restored.state().board.pieces[ProvinceId("cornwall")]).isNotNull()
                assertThat(restored.state().catalog.coastalConnections)
                    .doesNotContain(
                        ProvinceConnection.of(ProvinceId("chester"), ProvinceId("portsmouth"))
                    )
                assertThat(continued.effectiveEntries().take(before.effectiveEntries().size))
                    .isEqualTo(before.effectiveEntries())
                assertThat(reopened.read(before.id).header).isEqualTo(before.header)
                assertThat(reopened.read(before.id).state()).isEqualTo(continued.state())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    private fun legacyGame(
        map: MapRule,
        version: CatalogVersion = CatalogVersion.CATALOG_13,
    ): StoredGame {
        val players = (1..6).map { Player(PlayerId("p$it"), "Player $it") }
        return GameDriver(FixedRandom())
            .create(
                id = UUID.randomUUID().toString(),
                header = GameHeader(players, owner, dataVersion = version.wireValue),
                credentials =
                    players
                        .mapIndexed { index, player ->
                            player.id to index.toString().padStart(64, '0')
                        }
                        .toMap(),
                rules = RuleOptions(map = map),
            )
    }

    /** Follow recorded land connections to Cornwall without ships. */
    private fun legacyCornwallGame(
        map: MapRule,
        version: CatalogVersion = CatalogVersion.CATALOG_13,
        route: List<String> = listOf("york", "chester", "wales", "cornwall"),
    ): StoredGame {
        val driver = GameDriver(FixedRandom())
        var game = legacyGame(map, version)
        for (step in 1..400) {
            val state = game.state()
            if (state.board.pieces[ProvinceId("cornwall")] != null) return game
            val actor =
                when (val prompt = state.prompts().single()) {
                    is Prompt.SinglePlayer -> prompt.player
                    is Prompt.MultiPlayer -> prompt.players.first { it !in game.pendingBids }
                    is Prompt.System -> error("The fixture driver must resolve authority outcomes.")
                }
            val choices = state.legalChoices(actor).commands
            val destination = route.getOrNull((state.turnNumber ?: 0) - 1)
            val command =
                when {
                    state.stage == Stage.BIDDING -> "bid-capital ${if (actor == owner) 6 else 0}"
                    state.stage == Stage.CAPITAL_CHOICE && actor == owner -> "choose-capital london"
                    state.stage == Stage.TOKEN_PURCHASE -> "buy-tokens 8"
                    state.stage == Stage.EXPANSION &&
                        actor == owner &&
                        destination != null &&
                        state.board.pieces[ProvinceId(destination)] == null ->
                        "place ${state.catalog.map.province(ProvinceId(destination)).marketNumber} $destination"
                    "stabilize cash" in choices -> "stabilize cash"
                    "pass" in choices -> "pass"
                    else -> choices.first()
                }
            game = driver.submit(game, actor, command)
        }
        error("The legacy fixture must reach Cornwall through its recorded land connections.")
    }
}
