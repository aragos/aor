package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test

class DrawRenaissanceMigrationTest {
    private val directories = mutableListOf<Path>()
    private val id = "3dd8402c-8a4c-4837-b5cd-25e674a0a8e1"

    @AfterTest
    fun clean() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun archivedLegacySaveReplaysAndContinuesWithPendingPrivateBidAfterUpgrade() {
        val store = fixture()
        val original = store.document(id)
        val before = store.read(id)

        assertThat(DrawRenaissanceMigration.run(store)).containsExactly(id)
        val reopened = FileGameStore(store.directory)
        val upgraded = reopened.read(id)
        val continued = GameDriver(FixedRandom()).submit(upgraded, PlayerId("p2"), "bid-capital 2")
        reopened.update(id) { continued }

        assertThat(upgraded.header.rulesVersion).isEqualTo(RULES_VERSION)
        assertThat(upgraded.header.drawRenaissanceFromTurn).isEqualTo(1)
        assertThat(upgraded.state()).isEqualTo(before.state().copy(header = upgraded.header))
        assertThat(upgraded.effectiveEntries()).isEqualTo(before.effectiveEntries())
        assertThat(upgraded.revisions.dropLast(1)).isEqualTo(before.revisions)
        assertThat(upgraded.pendingBids).isEqualTo(before.pendingBids)
        assertThat(upgraded.submissionVersions).isEqualTo(before.submissionVersions)
        assertThat(upgraded.submissionReceipts).isEqualTo(before.submissionReceipts)
        assertThat(upgraded.credentials).isEqualTo(before.credentials)
        assertThat(upgraded.memberships).isEqualTo(before.memberships)
        assertThat(upgraded.name).isEqualTo(before.name)
        assertThat(upgraded.createdAt).isEqualTo(before.createdAt)
        assertThat(upgraded.planVersions).isEqualTo(before.planVersions)
        assertThat(upgraded.plans).isEqualTo(before.plans)
        val archive =
            requireNotNull(upgraded.revisions.last().storageArchive) {
                "The rules migration requires its exact source archive."
            }
        assertThat(reopened.archivedDocument(id, archive)).isEqualTo(original)
        assertThat(reopened.read(id).pendingBids)
            .containsExactly(PlayerId("p1"), 3, PlayerId("p2"), 2)
        assertThat(DrawRenaissanceMigration.run(reopened)).isEmpty()
    }

    @Test
    fun postgresUpgradePreservesItsPrivateStateAndArchiveAcrossRestart() =
        PostgresFixture().use { database ->
            val source = fixture()
            importFileStorage(source.directory, database.database)
            val original = database.store.document(id)
            val before = database.store.read(id)
            val listing =
                database.store.listGames(
                    AccountId("observer"),
                    GameListScope.ALL,
                    null,
                    null,
                    10,
                )
            assertThat(listing.games.single().status).isEqualTo(GameListStatus.ONGOING)

            DrawRenaissanceMigration.run(database.store)

            val reopened = database.reopened().store as ManagedGameStore
            val upgraded = reopened.read(id)
            assertThat(upgraded.state()).isEqualTo(before.state().copy(header = upgraded.header))
            assertThat(upgraded.effectiveEntries()).isEqualTo(before.effectiveEntries())
            assertThat(upgraded.pendingBids).isEqualTo(before.pendingBids)
            assertThat(upgraded.submissionVersions).isEqualTo(before.submissionVersions)
            val archive =
                requireNotNull(upgraded.revisions.last().storageArchive) {
                    "The rules migration requires its exact source archive."
                }
            assertThat(reopened.archivedDocument(id, archive)).isEqualTo(original)
        }

    @Test
    fun inProgressPurchaseKeepsArmedPlansAndActivatesDrawSwapsNextTurn() {
        val directory = Files.createTempDirectory("aor-renaissance-plans-").also(directories::add)
        val store = FileGameStore(directory)
        val service = GameService(store, GameDriver(FixedRandom()))
        val created = service.create(listOf("Alice", "Bob", "Carol"))
        val access = created.links.map { GameAccess.SeatToken(it.path.substringAfter("token=")) }
        store.update(created.gameId) { game ->
            game.copy(header = game.header.copy(rulesVersion = LEGACY_RULES_VERSION))
        }
        listOf(
                0 to "bid-capital 3",
                1 to "bid-capital 2",
                2 to "bid-capital 1",
                0 to "choose-capital venice",
                1 to "choose-capital genoa",
                2 to "choose-capital barcelona",
                0 to "buy-tokens -2",
                1 to "buy-tokens -1",
                2 to "buy-tokens 0",
                0 to "pass",
                1 to "pass",
                2 to "pass",
            )
            .forEach { (seat, command) ->
                val view = service.get(created.gameId, access[seat])
                service.submit(
                    created.gameId,
                    access[seat],
                    view.revision,
                    command,
                    view.submissionVersion,
                )
            }
        val actions =
            listOf(
                PlannedAction("purchase-shipping", "pass"),
                PlannedAction("purchase-advances", "buy-advance V"),
            )
        for (operation in listOf(PlanOperation.SAVE, PlanOperation.ARM)) {
            val view = service.get(created.gameId, access[1])
            service.writePlan(
                created.gameId,
                access[1],
                view.revision,
                view.planning?.version,
                UUID.randomUUID().toString(),
                operation,
                actions,
                view.planning?.previewToken,
            )
        }
        val before = store.read(created.gameId)

        DrawRenaissanceMigration.run(store)

        val upgraded = FileGameStore(directory).read(created.gameId)
        assertThat(upgraded.header.drawRenaissanceFromTurn).isEqualTo(2)
        assertThat(upgraded.state()).isEqualTo(before.state().copy(header = upgraded.header))
        assertThat(upgraded.plans).isEqualTo(before.plans)
        assertThat(upgraded.plans[PlayerId("p2")]?.armed).isTrue()
        assertThat(upgraded.planVersions).isEqualTo(before.planVersions)
        assertThat(upgraded.planReceipts).isEqualTo(before.planReceipts)
        assertThat(upgraded.planExecutions).isEqualTo(before.planExecutions)
        assertThat(upgraded.submissionReceipts).isEqualTo(before.submissionReceipts)
    }

    @Test
    fun crossTurnRewindRetainsTheTimingOfRemovedHistoricalTurns() {
        val directory = Files.createTempDirectory("aor-renaissance-rewound-").also(directories::add)
        val store = FileGameStore(directory)
        val driver = GameDriver(FixedRandom())
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        val initial =
            driver.create(
                UUID.randomUUID().toString(),
                GameHeader(players, players.first().id, rulesVersion = LEGACY_RULES_VERSION),
                players.associate { player ->
                    player.id to player.id.value.last().toString().padStart(64, '0')
                },
                undoPolicy = UndoPolicy.LINEAR_GAME,
            )
        val positions =
            generateSequence(initial) { game ->
                    val state = game.state()
                    if ((state.turnNumber ?: 0) >= 3) return@generateSequence null
                    val actor =
                        when (val prompt = state.prompts().single()) {
                            is Prompt.SinglePlayer -> prompt.player
                            is Prompt.MultiPlayer ->
                                prompt.players.first { it !in game.pendingBids }
                            is Prompt.System ->
                                error("The fixture driver resolves authority outcomes.")
                        }
                    val seat = state.playerIds.indexOf(actor)
                    val choices = state.legalChoices(actor).commands
                    val command =
                        when {
                            state.stage == Stage.BIDDING -> "bid-capital ${3 - seat}"
                            state.stage == Stage.TOKEN_PURCHASE -> "buy-tokens ${seat - 3}"
                            "stabilize cash" in choices -> "stabilize cash"
                            "pass" in choices -> "pass"
                            else -> choices.first()
                        }
                    driver.submit(game, actor, command)
                }
                .take(300)
                .toList()
        val source = positions.last()
        check(source.state().turnNumber == 3) { "The fixture must reach the third turn." }
        val target = positions.first { it.state().stage == Stage.PURCHASE }.state().entryCount
        val rewound = HistoryEdits.undo(source, players.first().id, target)
        store.create(rewound)

        DrawRenaissanceMigration.run(store)

        val upgraded = FileGameStore(directory).read(rewound.id)
        assertThat(upgraded.header.drawRenaissanceFromTurn).isEqualTo(4)
        assertThat(upgraded.state().turnNumber).isEqualTo(1)
        assertThat(upgraded.state()).isEqualTo(rewound.state().copy(header = upgraded.header))
        assertThat(upgraded.revisions.dropLast(1)).isEqualTo(rewound.revisions)
    }

    private fun fixture(): FileGameStore {
        val directory = Files.createTempDirectory("aor-renaissance-upgrade-").also(directories::add)
        val resource =
            requireNotNull(javaClass.getResourceAsStream("/games/upgraded-format-seven.json")) {
                    "The released rules-v4 save must be available."
                }
                .bufferedReader()
                .use { it.readText() }
        Files.writeString(directory.resolve("$id.json"), resource)
        val archiveDirectory = Files.createDirectories(directory.resolve("archives").resolve(id))
        val source =
            requireNotNull(
                    javaClass.getResourceAsStream("/games/upgraded-format-seven-source.json")
                ) {
                    "The released storage upgrade requires its original archive."
                }
                .bufferedReader()
                .use { it.readText() }
        Files.writeString(
            archiveDirectory.resolve(
                "2-9ec97e68026d5f7fa07bbe518eae0622560e5b354b8e546638dfaf041d311d23.json"
            ),
            source,
        )
        return FileGameStore(directory)
    }
}
