package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class FixedRandom : SetupRandom {
    var shuffles = 0
    var rolls = 0

    override fun shuffle(cards: List<String>) = cards.also { shuffles++ }

    override fun roll(count: Int): List<DieRoll> {
        rolls++
        return List(count) { index -> DieRoll(if (rolls == 1) 3 else index + 1) }
    }
}

private object NoRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = error("Replay requested a shuffle")

    override fun roll(count: Int): List<DieRoll> = error("Replay requested dice")
}

class GameServiceTest {
    private val directories = mutableListOf<Path>()

    private fun directory() =
        Files.createTempDirectory("aor-service-test-").also { directories.add(it) }

    private fun token(game: CreatedGame, player: Int) =
        game.links[player].path.substringAfter("token=")

    private fun create(service: GameService) = service.create(listOf("Alice", "Bob", "Carol"))

    @AfterTest
    fun clean() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun pendingBidReplacementSurvivesRestartWithoutAdvancingRevision() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        service.submit(game.gameId, token(game, 0), 2, "bid-capital 10")

        val replaced = service.submit(game.gameId, token(game, 0), 2, "bid-capital 12")
        val restarted = createGameService(directory, NoRandom).get(game.gameId, token(game, 0))

        assertThat(replaced.revision).isEqualTo(2)
        assertThat(replaced.pendingBid).isEqualTo(12)
        assertThat(restarted).isEqualTo(replaced)
    }

    @Test
    fun finalBidRevealsEntireBatchAndPersistsTieOutcomes() {
        val directory = directory()
        val firstRandom = FixedRandom()
        val service = createGameService(directory, firstRandom)
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        service.submit(game.gameId, token(game, 0), 2, "bid-capital 12")
        val secondRandom = FixedRandom()
        val restarted = createGameService(directory, secondRandom)
        restarted.submit(game.gameId, token(game, 1), 2, "bid-capital 12")

        val revealed = restarted.submit(game.gameId, token(game, 2), 2, "bid-capital 0")
        val replayed = createGameService(directory, NoRandom).get(game.gameId, token(game, 2))

        assertThat(revealed.revision).isEqualTo(3)
        assertThat(firstRandom.shuffles).isEqualTo(1)
        assertThat(secondRandom.shuffles).isEqualTo(0)
        assertThat(secondRandom.rolls).isEqualTo(2)
        assertThat(revealed.game.capitalOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p1"), PlayerId("p3"))
            .inOrder()
        assertThat(revealed.game.players.map(PlayerView::cash))
            .containsExactly(28, 28, 40)
            .inOrder()
        assertThat(revealed.submitted).isEmpty()
        assertThat(replayed).isEqualTo(revealed)
    }

    @Test
    fun capitalChoiceSurvivesRestart() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        service.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
        service.submit(game.gameId, token(game, 1), 2, "bid-capital 2")
        service.submit(game.gameId, token(game, 2), 2, "bid-capital 1")

        val chosen = service.submit(game.gameId, token(game, 0), 3, "choose-capital venice")
        val restarted = createGameService(directory, NoRandom).get(game.gameId, token(game, 0))

        assertThat(restarted).isEqualTo(chosen)
        assertThat(restarted.game.players.first().capital).isEqualTo("venice")
    }

    @Test
    fun shorthandCardPlaysAreStoredCanonicallyAndSurviveRestart() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = playingGame(service)
        val before = service.get(game.gameId, token(game, 0))

        val played =
            service.submit(game.gameId, token(game, 0), before.revision, "venice: play stone")
        val stored = FileGameStore(directory).read(game.gameId)
        val restarted = createGameService(directory, NoRandom).get(game.gameId, token(game, 0))

        assertThat(stored.revisions.last().command).isEqualTo("play hand-1")
        assertThat(stored.revisions.last().entries.first()).isEqualTo("p1: play hand-1")
        assertThat(played.game.hand.map(HandCard::reference)).doesNotContain("hand-1")
        assertThat(restarted).isEqualTo(played)
    }

    @Test
    fun actorPrefixesCannotSubmitOnBehalfOfAnotherAuthenticatedSeat() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        val before = FileGameStore(directory).read(game.gameId)

        val failure =
            assertFailsWith<RuleViolation> {
                service.submit(game.gameId, token(game, 0), before.revision, "p2: start")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
        assertThat(FileGameStore(directory).read(game.gameId)).isEqualTo(before)
    }

    private fun playingGame(service: GameService): CreatedGame {
        val game = create(service)
        val commands =
            listOf(
                0 to "start",
                0 to "bid-capital 3",
                1 to "bid-capital 2",
                2 to "bid-capital 1",
                0 to "choose-capital venice",
                1 to "choose-capital genoa",
                2 to "choose-capital barcelona",
                0 to "buy-tokens 0",
                1 to "buy-tokens 1",
                2 to "buy-tokens 2",
            )
        commands.forEach { (seat, command) ->
            val before = service.get(game.gameId, token(game, seat))
            service.submit(game.gameId, token(game, seat), before.revision, command)
        }
        return game
    }

    @Test
    fun failedRevealDoesNotPublishPartialBatchOrOutcomes() {
        val directory = directory()
        var fail = false
        val writer = GameWriter { path, text ->
            if (fail) throw IOException("disk unavailable") else AtomicGameWriter.write(path, text)
        }
        val service = createGameService(directory, FixedRandom(), writer)
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        service.submit(game.gameId, token(game, 0), 2, "bid-capital 12")
        service.submit(game.gameId, token(game, 1), 2, "bid-capital 12")
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)
        fail = true
        assertThat(
                assertFailsWith<ServiceFailure> {
                        service.submit(game.gameId, token(game, 2), 2, "bid-capital 0")
                    }
                    .code
            )
            .isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(path)).isEqualTo(before)
        val recovered = createGameService(directory, NoRandom).get(game.gameId, token(game, 2))
        assertThat(recovered.game.stage).isEqualTo(Stage.BIDDING)
        assertThat(recovered.submitted).isEqualTo(listOf(PlayerId("p1"), PlayerId("p2")))
        assertThat(recovered.pendingBid).isNull()
        assertThat(recovered.history.none { it.startsWith("!roll") || it.contains("bid-capital") })
            .isTrue()
    }

    @Test
    fun concurrentBidsCommitOneBatchInRegistrationOrder() {
        val directory = directory()
        val service = createGameService(directory, NoRandom)
        // A fresh authority is needed only for start's shuffle, never for distinct bids.
        val game = service.create((1..6).map { "Player $it" })
        createGameService(directory, FixedRandom()).submit(game.gameId, token(game, 0), 1, "start")
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results =
                pool
                    .invokeAll(
                        (0..5).map { player ->
                            Callable {
                                service.submit(
                                    game.gameId,
                                    token(game, player),
                                    2,
                                    "bid-capital $player",
                                )
                            }
                        }
                    )
                    .map { it.get() }
            assertThat(results.count { it.revision == 3 }).isEqualTo(1)
            val final = service.get(game.gameId, token(game, 0))
            assertThat(final.history.filter { it.contains("bid-capital") })
                .isEqualTo((1..6).map { "p$it: bid-capital ${it - 1}" })
            assertThat(final.game.capitalOrder)
                .isEqualTo(listOf("p6", "p5", "p4", "p3", "p2", "p1").map(::PlayerId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun staleRequestsDoNotChangeSavedState() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "set-option deck-modification disabled")
        service.submit(game.gameId, token(game, 0), 2, "start")
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)
        assertThat(
                assertFailsWith<ServiceFailure> {
                        service.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
                    }
                    .code
            )
            .isEqualTo("STALE_REVISION")
        assertThat(Files.readString(path)).isEqualTo(before)
        assertThat(service.get(game.gameId, token(game, 0)).game.currentEpoch).isEqualTo(1)
    }

    @Test
    fun invalidRequestsDoNotChangeSavedState() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)

        val failure =
            assertFailsWith<RuleViolation> {
                service.submit(game.gameId, token(game, 0), 2, "bid-capital 41")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_BID)
        assertThat(Files.readString(path)).isEqualTo(before)
    }

    @Test
    fun injectedStoreSupportsTheSameServiceFlow() {
        val store = MemoryGameStore()
        val service = GameService(store = store, driver = GameDriver(FixedRandom()))
        val game = create(service)

        val started = service.submit(game.gameId, token(game, 0), 1, "start")

        assertThat(started.game.stage).isEqualTo(Stage.BIDDING)
        assertThat(store.read(game.gameId).revisions).hasSize(2)
        assertThat(store.read(game.gameId).state().viewForPlayer(PlayerId("p1")))
            .isEqualTo(started.game)
    }

    @Test
    fun explicitCreatorCanConfigureWithoutBeingFirstRegisteredPlayer() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        FileGameStore(directory).update(game.gameId) { stored ->
            stored.copy(
                header = stored.header.copy(creatorId = PlayerId("p2")),
                revisions =
                    stored.revisions.map { revision ->
                        revision.copy(
                            author = PlayerId("p2"),
                            entries = defaultConfigurationText(PlayerId("p2")),
                        )
                    },
            )
        }

        val configured =
            service.submit(
                game.gameId,
                token(game, 1),
                1,
                "set-option deck-modification disabled",
            )

        assertThat(configured.creatorId).isEqualTo(PlayerId("p2"))
        assertThat(configured.game.currentEpoch).isEqualTo(1)
    }

    @Test
    fun firstRegisteredPlayerCannotConfigureWhenSomeoneElseIsCreator() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        FileGameStore(directory).update(game.gameId) { stored ->
            stored.copy(
                header = stored.header.copy(creatorId = PlayerId("p2")),
                revisions =
                    stored.revisions.map { revision ->
                        revision.copy(
                            author = PlayerId("p2"),
                            entries = defaultConfigurationText(PlayerId("p2")),
                        )
                    },
            )
        }

        val failure =
            assertFailsWith<RuleViolation> {
                service.submit(game.gameId, token(game, 0), 1, "start")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun storedFilesRequireCreatorIdentity() {
        val directory = directory()
        val service = createGameService(directory, NoRandom)
        val game = create(service)
        val path = directory.resolve("${game.gameId}.json")
        val incomplete = json.readTree(Files.readString(path)) as ObjectNode
        (incomplete["header"] as ObjectNode).remove("creatorId")
        val contents = json.writeValueAsString(incomplete)
        Files.writeString(path, contents)

        val failure =
            assertFailsWith<ServiceFailure> {
                createGameService(directory, NoRandom).get(game.gameId, token(game, 0))
            }

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(path)).isEqualTo(contents)
    }

    @Test
    fun storedCreatorIdentityMustBelongToARegisteredPlayer() {
        val directory = directory()
        val service = createGameService(directory, NoRandom)
        val game = create(service)
        val path = directory.resolve("${game.gameId}.json")
        val invalid = json.readTree(Files.readString(path)) as ObjectNode
        (invalid["header"] as ObjectNode).put("creatorId", "p6")
        val contents = json.writeValueAsString(invalid)
        Files.writeString(path, contents)

        val failure = assertFailsWith<ServiceFailure> { service.get(game.gameId, token(game, 0)) }

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(path)).isEqualTo(contents)
    }

    @Test
    fun corruptAndIncompatibleFilesAreNeverOverwritten() {
        val directory = directory()
        val service = createGameService(directory)
        val game = create(service)
        val path = directory.resolve("${game.gameId}.json")
        val original = Files.readString(path)
        for (contents in
            listOf(
                "not json",
                original.replace("\"fileVersion\":3", "\"fileVersion\":99"),
                original.replace(RULES_VERSION, "future-rules"),
                original.replace("set-option deck-modification disabled", "invented-command"),
            )) {
            Files.writeString(path, contents)
            assertThat(
                    assertFailsWith<ServiceFailure> { service.get(game.gameId, token(game, 0)) }
                        .code
                )
                .isEqualTo("STORAGE_UNAVAILABLE")
            assertThat(Files.readString(path)).isEqualTo(contents)
        }
    }

    @Test
    fun corruptRevisionTransactionsCannotExposePartiallyCommittedBids() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = create(service)
        service.submit(game.gameId, token(game, 0), 1, "start")
        service.submit(game.gameId, token(game, 0), 2, "bid-capital 3")
        service.submit(game.gameId, token(game, 1), 2, "bid-capital 2")
        service.submit(game.gameId, token(game, 2), 2, "bid-capital 1")
        val path = directory.resolve("${game.gameId}.json")
        val original = Files.readString(path)
        val corruptions: List<(ObjectNode) -> Unit> =
            listOf(
                { it.putArray("pendingBids") },
                { it.put("revision", 99) },
                { (it["credentials"] as ObjectNode).put("p2", it["credentials"]["p1"].asText()) },
                { (it["revisions"][2] as ObjectNode).put("author", "intruder") },
                { (it["revisions"][2] as ObjectNode).put("command", "choose-capital venice") },
                {
                    val revisions = it["revisions"] as ArrayNode
                    (revisions[2] as ObjectNode).putArray("entries").add("p1: bid-capital 3")
                },
                {
                    val revisions = it["revisions"] as ArrayNode
                    (revisions[2] as ObjectNode).putArray("entries").add("p1: bid-capital 3")
                    revisions.add(
                        json.valueToTree<ObjectNode>(
                            Revision(
                                4,
                                3,
                                PlayerId("p3"),
                                "reveal-capital-bids",
                                listOf("p2: bid-capital 2", "p3: bid-capital 1"),
                            )
                        )
                    )
                    it.put("revision", 4)
                },
            )
        for (corrupt in corruptions) {
            val root = json.readTree(original) as ObjectNode
            corrupt(root)
            val contents = json.writeValueAsString(root)
            Files.writeString(path, contents)
            assertThat(
                    assertFailsWith<ServiceFailure> { service.get(game.gameId, token(game, 1)) }
                        .code
                )
                .isEqualTo("STORAGE_UNAVAILABLE")
            assertThat(
                    assertFailsWith<ServiceFailure> {
                            service.submit(game.gameId, token(game, 1), 3, "choose-capital venice")
                        }
                        .code
                )
                .isEqualTo("STORAGE_UNAVAILABLE")
            assertThat(Files.readString(path)).isEqualTo(contents)
        }
        Files.writeString(path, original)
        assertThat(service.get(game.gameId, token(game, 1)).game.stage)
            .isEqualTo(Stage.CAPITAL_CHOICE)
    }
}

private class MemoryGameStore : GameStore {
    private val games = mutableMapOf<String, StoredGame>()

    override fun create(game: StoredGame) {
        check(game.id !in games) { "Cannot replace an existing game during creation." }
        games[game.id] = game
    }

    override fun read(id: String): StoredGame =
        games[id] ?: throw ServiceFailure(404, "GAME_NOT_FOUND")

    override fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame =
        synchronized(games) {
            transform(read(id)).also { next -> games[id] = next }
        }
}
