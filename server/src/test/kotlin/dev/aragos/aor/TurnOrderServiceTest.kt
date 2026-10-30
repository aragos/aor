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

class TurnOrderServiceTest {
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun clean() {
        directories.forEach { directory -> directory.toFile().deleteRecursively() }
    }

    @Test
    fun tokenQuoteUsesExactSignedAmountWithoutChangingTheGame() {
        val fixture = ready()
        val before = Files.readString(fixture.path)

        val response =
            fixture.service.quoteTokenPurchase(fixture.game.gameId, fixture.token(0), 6, -13)

        assertThat(response.revision).isEqualTo(6)
        assertThat(response.quote.amount).isEqualTo(-13)
        assertThat(response.quote.cost).isEqualTo(13)
        assertThat(response.quote.tokensReceived).isEqualTo(0)
        assertThat(Files.readString(fixture.path)).isEqualTo(before)
    }

    @Test
    fun tokenQuoteRemainsAvailableForReplacingASealedPurchase() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")

        val response =
            fixture.service.quoteTokenPurchase(fixture.game.gameId, fixture.token(0), 6, 11)

        assertThat(response.quote.tokensReceived).isEqualTo(11)
        assertThat(fixture.view(0).pendingBid).isEqualTo(9)
    }

    @Test
    fun tokenQuoteRequiresTheCurrentRevision() {
        val fixture = ready()

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.quoteTokenPurchase(fixture.game.gameId, fixture.token(0), 5, 8)
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
    }

    @Test
    fun tokenQuoteAuthenticatesBeforeReturningRevisionInformation() {
        val fixture = ready()

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.quoteTokenPurchase(fixture.game.gameId, "invalid-token", 5, 8)
            }

        assertThat(failure.code).isEqualTo("UNAUTHORIZED")
    }

    @Test
    fun aSealedTokenPurchaseDoesNotEnterVisualActivity() {
        val fixture = ready()
        val before = fixture.view(1).events

        fixture.submit(0, "buy-tokens 9")

        assertThat(fixture.view(1).events).isEqualTo(before)
    }

    @Test
    fun pendingTokenPurchaseIsPrivateAndDoesNotChangePublicResources() {
        val fixture = ready()
        val initial = fixture.view(1)

        fixture.submit(0, "buy-tokens 9")
        val own = fixture.view(0)
        val other = fixture.view(1)

        assertThat(own.pendingBid).isEqualTo(9)
        assertThat(other.pendingBid).isNull()
        assertThat(other.game).isEqualTo(initial.game)
        assertThat(other.history).isEqualTo(initial.history)
        assertThat(other.submitted).containsExactly(PlayerId("p1"))
        assertThat(other.game.revealedTokenPurchases).isEmpty()
        assertThat(own.game.players.map(PlayerView::writtenCash)).containsExactly(40, 40, 40)
        assertThat(other.game.players.map(PlayerView::writtenCash))
            .isEqualTo(initial.game.players.map(PlayerView::writtenCash))
        assertThat(other.revision).isEqualTo(6)
    }

    @Test
    fun capitalBiddingDoesNotReplaceTheInitialWrittenCashRecord() {
        val fixture = ready()

        val players = fixture.view(0).game.players

        assertThat(players.map(PlayerView::cash)).containsExactly(37, 38, 39).inOrder()
        assertThat(players.map(PlayerView::writtenCash)).containsExactly(40, 40, 40)
    }

    @Test
    fun pendingReplacementSurvivesRestartWithoutAdvancingRevision() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")

        val replaced = fixture.submit(0, "buy-tokens -7")
        val recovered = fixture.restart().view(0)

        assertThat(replaced.pendingBid).isEqualTo(-7)
        assertThat(replaced.revision).isEqualTo(6)
        assertThat(recovered).isEqualTo(replaced)
    }

    @Test
    fun finalPurchaseRevealsTheBatchInRegistrationOrderAndReplayMatches() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")
        fixture.submit(1, "buy-tokens -5")

        val revealed = fixture.submit(2, "buy-tokens 1")
        val recovered = fixture.restart().view(2)
        val stored = FileGameStore(fixture.directory).read(fixture.game.gameId)

        assertThat(revealed.revision).isEqualTo(7)
        assertThat(revealed.game.stage).isEqualTo(Stage.PLAY_CARDS)
        assertThat(revealed.game.revealedTokenPurchases)
            .containsExactly(PlayerId("p1"), 9, PlayerId("p2"), -5, PlayerId("p3"), 1)
        assertThat(revealed.history.filter { entry -> entry.contains("buy-tokens") })
            .containsExactly("p1: buy-tokens 9", "p2: buy-tokens -5", "p3: buy-tokens 1")
            .inOrder()
        assertThat(stored.revisions.last().command).isEqualTo("reveal-token-purchases")
        assertThat(revealed.submitted).isEmpty()
        assertThat(revealed.pendingBid).isNull()
        assertThat(revealed.game.players.map(PlayerView::writtenCash))
            .containsExactly(28, 33, 38)
            .inOrder()
        assertThat(recovered).isEqualTo(revealed)
    }

    @Test
    fun stalePurchaseCannotReplaceTheCommittedBatch() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")
        fixture.submit(1, "buy-tokens 5")
        fixture.submit(2, "buy-tokens 1")
        val before = Files.readString(fixture.path)

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.submit(fixture.game.gameId, fixture.token(0), 6, "buy-tokens 10")
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
        assertThat(Files.readString(fixture.path)).isEqualTo(before)
    }

    @Test
    fun concurrentPurchasesCommitExactlyOneCompleteBatch() {
        val fixture = ready()
        val pool = Executors.newFixedThreadPool(3)

        val results =
            try {
                pool
                    .invokeAll(
                        (0..2).map { player ->
                            Callable {
                                fixture.service.submit(
                                    fixture.game.gameId,
                                    fixture.token(player),
                                    6,
                                    "buy-tokens ${9 - player}",
                                )
                            }
                        }
                    )
                    .map { result -> result.get() }
            } finally {
                pool.shutdownNow()
            }
        val final = fixture.view(0)

        assertThat(results.count { snapshot -> snapshot.revision == 7 }).isEqualTo(1)
        assertThat(final.game.stage).isEqualTo(Stage.PLAY_CARDS)
        assertThat(final.history.filter { entry -> entry.contains("buy-tokens") })
            .containsExactly("p1: buy-tokens 9", "p2: buy-tokens 8", "p3: buy-tokens 7")
            .inOrder()
        assertThat(FileGameStore(fixture.directory).read(fixture.game.gameId).revisions).hasSize(7)
    }

    @Test
    fun failedTokenRevealLeavesPendingDecisionsAndHistoryUnchanged() {
        var failing = false
        val writer = GameWriter { path, contents ->
            if (failing) throw IOException("Disk unavailable")
            AtomicGameWriter.write(path, contents)
        }
        val fixture = ready(writer)
        fixture.submit(0, "buy-tokens 9")
        fixture.submit(1, "buy-tokens 5")
        val before = Files.readString(fixture.path)
        failing = true

        val failure = assertFailsWith<ServiceFailure> { fixture.submit(2, "buy-tokens 1") }
        val recovered = fixture.restart().view(2)

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(fixture.path)).isEqualTo(before)
        assertThat(recovered.game.stage).isEqualTo(Stage.TOKEN_PURCHASE)
        assertThat(recovered.submitted).containsExactly(PlayerId("p1"), PlayerId("p2"))
        assertThat(recovered.pendingBid).isNull()
        assertThat(recovered.history.none { entry -> entry.contains("buy-tokens") }).isTrue()
    }

    @Test
    fun partiallyResolvedTurnOrderSurvivesRestartWithoutRandomness() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 2")
        fixture.submit(1, "buy-tokens 2")
        fixture.submit(2, "buy-tokens 2")

        fixture.submit(1, "choose-turn-position 1")
        val inserted = fixture.view(2)
        val recovered = fixture.restart().view(2)

        assertThat(inserted.game.stage).isEqualTo(Stage.TURN_ORDER_TIE)
        assertThat(inserted.game.provisionalTieOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p1"))
            .inOrder()
        assertThat(inserted.game.turnOrder).isEmpty()
        assertThat(inserted.prompt?.players).containsExactly(PlayerId("p3"))
        assertThat(recovered).isEqualTo(inserted)
    }

    @Test
    fun lastTurnOrderInsertionPersistsTheFinalOrderAndBoxes() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 2")
        fixture.submit(1, "buy-tokens 2")
        fixture.submit(2, "buy-tokens 2")
        fixture.submit(1, "choose-turn-position 1")

        val finalized = fixture.restart().submit(2, "choose-turn-position 2")
        val recovered = fixture.restart().view(2)

        assertThat(finalized.game.stage).isEqualTo(Stage.PLAY_CARDS)
        assertThat(finalized.game.turnOrder)
            .containsExactly(PlayerId("p2"), PlayerId("p3"), PlayerId("p1"))
            .inOrder()
        assertThat(finalized.game.orderBoxes)
            .containsExactly(PlayerId("p2"), 2, PlayerId("p3"), 4, PlayerId("p1"), 6)
        assertThat(recovered).isEqualTo(finalized)
    }

    @Test
    fun outOfRangeTokenPurchaseDoesNotChangePendingOrSavedState() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")
        val before = Files.readString(fixture.path)

        val failure = assertFailsWith<RuleViolation> { fixture.submit(0, "buy-tokens -38") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_TOKEN_PURCHASE)
        assertThat(Files.readString(fixture.path)).isEqualTo(before)
        assertThat(fixture.view(0).pendingBid).isEqualTo(9)
    }

    @Test
    fun partialTokenBatchInCommittedHistoryIsRejectedWithoutOverwrite() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")
        fixture.submit(1, "buy-tokens 5")
        fixture.submit(2, "buy-tokens 1")
        val root = json.readTree(Files.readString(fixture.path)) as ObjectNode
        val revision = (root["revisions"] as ArrayNode)[6] as ObjectNode
        revision.putArray("entries").add("p1: buy-tokens 9")
        val corrupt = json.writeValueAsString(root)
        Files.writeString(fixture.path, corrupt)

        val failure = assertFailsWith<ServiceFailure> { fixture.restart().view(0) }

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(fixture.path)).isEqualTo(corrupt)
    }

    @Test
    fun standaloneTokenPurchaseRevisionCannotExposeAPartialBatch() {
        val fixture = ready()
        val root = json.readTree(Files.readString(fixture.path)) as ObjectNode
        (root["revisions"] as ArrayNode).add(
            json.valueToTree<ObjectNode>(
                Revision(
                    id = 7,
                    parent = 6,
                    author = PlayerId("p1"),
                    command = "buy-tokens 9",
                    entries = listOf("p1: buy-tokens 9"),
                )
            )
        )
        root.put("revision", 7)
        val corrupt = json.writeValueAsString(root)
        Files.writeString(fixture.path, corrupt)

        val failure = assertFailsWith<ServiceFailure> { fixture.restart().view(1) }

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(fixture.path)).isEqualTo(corrupt)
    }

    @Test
    fun pendingTokenPurchasesAreRejectedOutsideTheirPhase() {
        val fixture = ready()
        fixture.submit(0, "buy-tokens 9")
        fixture.submit(1, "buy-tokens 5")
        fixture.submit(2, "buy-tokens 1")
        val root = json.readTree(Files.readString(fixture.path)) as ObjectNode
        (root["pendingBids"] as ObjectNode).put("p1", 9)
        val corrupt = json.writeValueAsString(root)
        Files.writeString(fixture.path, corrupt)

        val failure = assertFailsWith<ServiceFailure> { fixture.restart().view(0) }

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(fixture.path)).isEqualTo(corrupt)
    }

    private fun ready(writer: GameWriter = AtomicGameWriter): TurnFixture {
        val directory = Files.createTempDirectory("aor-turn-order-").also(directories::add)
        val service = createGameService(directory, FixedRandom(), writer)
        val game = service.create(listOf("Alice", "Bob", "Carol"))
        val fixture = TurnFixture(directory, service, game)
        fixture.submit(0, "bid-capital 3")
        fixture.submit(1, "bid-capital 2")
        fixture.submit(2, "bid-capital 1")
        fixture.submit(0, "choose-capital venice")
        fixture.submit(1, "choose-capital genoa")
        fixture.submit(2, "choose-capital barcelona")
        return fixture
    }
}

private data class TurnFixture(
    val directory: Path,
    val service: GameService,
    val game: CreatedGame,
) {
    val path: Path
        get() = directory.resolve("${game.gameId}.json")

    fun token(player: Int): String = game.links[player].path.substringAfter("token=")

    fun view(player: Int): GameSnapshot = service.get(game.gameId, token(player))

    fun submit(player: Int, text: String): GameSnapshot =
        service.submit(
            game.gameId,
            token(player),
            view(player).revision,
            text,
            view(player).submissionVersion,
        )

    fun restart(): TurnFixture = copy(service = createGameService(directory, NoTurnRandom))
}

private object NoTurnRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = error("Replay requested a shuffle")

    override fun roll(count: Int): List<DieRoll> = error("Replay requested dice")
}
