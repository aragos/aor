package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenPurchaseActivityTest {
    private val players =
        listOf(
            Player(PlayerId("p1"), "Alice"),
            Player(PlayerId("p2"), "Bob"),
            Player(PlayerId("p3"), "Carol"),
        )
    private val viewer = players.first().id
    private val driver = GameDriver(FixedRandom())

    @Test
    fun unrevealedPurchasesDoNotEnterAnyPlayersActivity() {
        val initial = ready()
        val pending =
            driver.submit(
                driver.submit(initial, players[0].id, "buy-tokens -8"),
                players[1].id,
                "buy-tokens 0",
            )

        val histories = players.map { player -> HistoryProjection.forPlayer(pending, player.id) }

        for ((player, history) in players.zip(histories)) {
            val before = HistoryProjection.forPlayer(initial, player.id)
            assertThat(history.events).isEqualTo(before.events)
            assertThat(history.lines).isEqualTo(before.lines)
        }
    }

    @Test
    fun oneRevealEntryIncludesNegativeZeroAndStockCappedPurchasesInCommittedOrder() {
        val revealed = reveal(ready(), listOf(37, -8, 0))

        val history = HistoryProjection.forPlayer(revealed, viewer)
        val summary = history.events.single { it.kind == HistoryEventKind.TOKEN_PURCHASES }

        assertThat(summary.id).isEqualTo("${revealed.revision}:0:token_purchases")
        assertThat(summary.turnNumber).isEqualTo(1)
        assertThat(summary.stage).isEqualTo(Stage.TOKEN_PURCHASE)
        assertThat(summary.actor).isNull()
        assertThat(summary.tokenPurchases)
            .containsExactly(
                HistoryTokenPurchaseView(
                    players[0].id,
                    amount = 37,
                    cashCost = 37,
                    tokensReceived = 36,
                ),
                HistoryTokenPurchaseView(
                    players[1].id,
                    amount = -8,
                    cashCost = 8,
                    tokensReceived = 0,
                ),
                HistoryTokenPurchaseView(
                    players[2].id,
                    amount = 0,
                    cashCost = 0,
                    tokensReceived = 0,
                ),
            )
            .inOrder()
        assertThat(summary.cashChanges)
            .containsExactly(
                HistoryCashChangeView(players[0].id, -37),
                HistoryCashChangeView(players[1].id, -8),
            )
        assertThat(summary.participants)
            .containsExactly(
                HistoryParticipantView(players[0].id, "Alice", "venice"),
                HistoryParticipantView(players[1].id, "Bob", "genoa"),
                HistoryParticipantView(players[2].id, "Carol", "barcelona"),
            )
        assertThat(history.events.none { it.kind == HistoryEventKind.BUY_TOKENS }).isTrue()
    }

    @Test
    fun theSummaryPrecedesItsPrivateDrawOutcomesAndPreservesCanonicalHistory() {
        val initial = ready()
        val before = HistoryProjection.forPlayer(initial, viewer)
        val revealed = reveal(initial, listOf(1, 2, 3))

        val history = HistoryProjection.forPlayer(revealed, viewer)

        assertThat(history.events.drop(before.events.size).map(HistoryEventView::kind))
            .containsExactly(
                HistoryEventKind.TOKEN_PURCHASES,
                HistoryEventKind.DRAW_CARD,
                HistoryEventKind.DRAW_CARD,
                HistoryEventKind.DRAW_CARD,
            )
            .inOrder()
        assertThat(history.lines.filter { it.contains(": buy-tokens ") })
            .containsExactly("p1: buy-tokens 1", "p2: buy-tokens 2", "p3: buy-tokens 3")
            .inOrder()
        assertThat(history.lines).contains("!draw p2 hand-5 <hidden>")
        assertThat(
                history.events
                    .filter { it.kind == HistoryEventKind.DRAW_CARD && it.actor != viewer }
                    .all { it.card == null }
            )
            .isTrue()
    }

    @Test
    fun pollingAndDocumentRestartKeepOneIdenticalSummary() {
        val revealed = reveal(ready(), listOf(1, 2, 3))
        val before = HistoryProjection.forPlayer(revealed, viewer)
        val recovered = GameDocuments.read(revealed.id, GameDocuments.write(revealed))

        val reloaded = HistoryProjection.forPlayer(recovered, viewer)

        assertThat(reloaded).isEqualTo(before)
        assertThat(HistoryProjection.forPlayer(revealed, viewer)).isEqualTo(before)
        assertThat(reloaded.events.count { it.kind == HistoryEventKind.TOKEN_PURCHASES })
            .isEqualTo(1)
    }

    @Test
    fun unrevealedValuesStayOutOfActivityAfterDocumentRestart() {
        val initial = ready()
        val pending = driver.submit(initial, viewer, "buy-tokens -8")
        val recovered = GameDocuments.read(pending.id, GameDocuments.write(pending))

        val history = HistoryProjection.forPlayer(recovered, players[1].id)

        assertThat(history).isEqualTo(HistoryProjection.forPlayer(initial, players[1].id))
    }

    @Test
    fun appendingTheNextRoundDoesNotRewriteTheFirstRoundOrItsFacts() {
        val firstRound = reveal(ready(), listOf(1, 2, 3))
        val early = HistoryProjection.forPlayer(firstRound, viewer).events
        val nextRound = finishRound(firstRound)
        val secondRound = reveal(nextRound, listOf(-1, 0, 1))

        val later = HistoryProjection.forPlayer(secondRound, viewer).events

        assertThat(later.take(early.size)).isEqualTo(early)
        val summaries = later.filter { it.kind == HistoryEventKind.TOKEN_PURCHASES }
        assertThat(summaries.map(HistoryEventView::turnNumber)).containsExactly(1, 2).inOrder()
        assertThat(summaries.map(HistoryEventView::id).distinct()).hasSize(2)
        assertThat(summaries.last().tokenPurchases.map(HistoryTokenPurchaseView::amount))
            .containsExactly(-1, 0, 1)
            .inOrder()
    }

    @Test
    fun aValidWholeBatchHistoryEditReplacesItsSummaryInsteadOfDuplicatingIt() {
        val initial = ready()
        val from = initial.effectiveEntries().size
        val revealed = reveal(initial, listOf(1, 3, 5))
        val original = HistoryProjection.forPlayer(revealed, viewer).events

        val edited =
            HistoryEdits.edit(
                revealed,
                viewer,
                from,
                listOf(
                    "buy-tokens 2",
                    "@${from + 1}",
                    "@${from + 2}",
                ),
            )
        val history = HistoryProjection.forPlayer(edited, viewer)

        val summary = history.events.single { it.kind == HistoryEventKind.TOKEN_PURCHASES }
        assertThat(summary.tokenPurchases.map(HistoryTokenPurchaseView::amount))
            .containsExactly(2, 3, 5)
            .inOrder()
        assertThat(summary.id).isEqualTo("${edited.revision}:0:token_purchases")
        assertThat(
                history.events.none {
                    it.id == original.single { it.kind == HistoryEventKind.TOKEN_PURCHASES }.id
                }
            )
            .isTrue()
        assertThat(history.events.takeWhile { it.kind != HistoryEventKind.TOKEN_PURCHASES })
            .isEqualTo(original.takeWhile { it.kind != HistoryEventKind.TOKEN_PURCHASES })
    }

    @Test
    fun historyEditsCannotSplitARevealedTokenPurchaseBatch() {
        val initial = ready()
        val from = initial.effectiveEntries().size
        val revealed = reveal(initial, listOf(1, 3, 5))

        val failure =
            assertFailsWith<ServiceFailure> {
                HistoryEdits.edit(revealed, viewer, from + 2, emptyList())
            }

        assertThat(failure.code).isEqualTo("HISTORY_EDIT_BOUNDARY")
    }

    private fun ready(): StoredGame {
        val credentials =
            players
                .mapIndexed { index, player ->
                    player.id to (index + 1).toString(16).padStart(64, '0')
                }
                .toMap()
        val initial =
            driver.create(
                "00000000-0000-0000-0000-000000000001",
                GameHeader(players, viewer),
                credentials,
                undoPolicy = UndoPolicy.FRIENDLY_GAME,
            )
        val bids =
            players.foldIndexed(initial) { index, game, player ->
                driver.submit(game, player.id, "bid-capital ${3 - index}")
            }
        return players.zip(listOf("venice", "genoa", "barcelona")).fold(bids) {
            game,
            (player, capital) ->
            driver.submit(game, player.id, "choose-capital $capital")
        }
    }

    private fun reveal(initial: StoredGame, amounts: List<Int>): StoredGame =
        players.zip(amounts).fold(initial) { game, (player, amount) ->
            driver.submit(game, player.id, "buy-tokens $amount")
        }

    private fun finishRound(initial: StoredGame): StoredGame {
        var game = initial
        repeat(100) {
            val state = game.state()
            if (state.stage == Stage.TOKEN_PURCHASE) return game
            val prompt = state.prompts().singleOrNull()
            val actor =
                when (prompt) {
                    is Prompt.SinglePlayer -> prompt.player
                    is Prompt.MultiPlayer -> prompt.players.first()
                    else -> error("The completed transaction must expose a player decision.")
                }
            val commands = state.legalChoices(actor).commands
            val command =
                when {
                    "stabilize cash" in commands -> "stabilize cash"
                    "pass" in commands -> "pass"
                    else -> commands.first()
                }
            game = driver.submit(game, actor, command)
        }
        error("The round must reach its next token purchase within 100 actions.")
    }
}
