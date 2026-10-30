package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BidWithdrawalTest(private val postgres: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}")
        fun backends(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @Test
    fun lostPrivateWriteResponseCanBeReconciledAfterALaterReplacementAndRestart() =
        WithdrawalFixture(postgres).use { fixture ->
            val reviewed = fixture.snapshot(0)
            val operationId = "550e8400-e29b-41d4-a716-446655440000"
            fixture.service.submit(
                fixture.game.gameId,
                fixture.access(0),
                reviewed.revision,
                "bid-capital 12",
                reviewed.submissionVersion,
                operationId,
            )
            fixture.submit(0, "bid-capital 17")

            val restored = fixture.restarted(0)

            assertThat(restored.submissionReceipts).contains(operationId)
            assertThat(restored.pendingBid).isEqualTo(17)
            assertThat(fixture.snapshot(1).submissionReceipts).isEmpty()
        }

    @Test
    fun staleSameSeatWritesCannotReplaceOrWithdrawNewerPrivateWork() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 12")
            val reviewed = fixture.snapshot(0)
            fixture.submit(0, "bid-capital 17")
            val writer = fixture.independentWriter()
            for (command in listOf("withdraw-bid", "bid-capital 9")) {
                val failure =
                    assertFailsWith<ServiceFailure> {
                        writer.submit(
                            fixture.game.gameId,
                            fixture.access(0),
                            reviewed.revision,
                            command,
                            reviewed.submissionVersion,
                        )
                    }
                assertThat(failure.code).isEqualTo("STALE_SUBMISSION")
            }
            assertThat(fixture.restarted(0).pendingBid).isEqualTo(17)
        }

    @Test
    fun returningToTheSameAmountStillInvalidatesAnEarlierReceipt() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 12")
            val reviewed = fixture.snapshot(0)
            fixture.submit(0, "bid-capital 17")
            fixture.submit(0, "bid-capital 12")
            assertThat(fixture.snapshot(0).submissionVersion)
                .isNotEqualTo(reviewed.submissionVersion)
        }

    @Test
    fun privateReplacementIsInvisibleToOtherViewersIncludingTheirReadValidators() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 12")
            val before = fixture.service.read(fixture.game.gameId, fixture.access(1), null)
            fixture.submit(0, "bid-capital 17")
            val after = fixture.service.read(fixture.game.gameId, fixture.access(1), before.etag)
            assertThat(after.etag).isEqualTo(before.etag)
            assertThat(after.snapshot).isNull()
        }

    @Test
    fun capitalWithdrawalSurvivesRestartAndReopensOnlyTheCallersDecision() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            fixture.submit(1, "bid-capital 0")
            val before = fixture.snapshot(0)

            val withdrawn = fixture.submit(0, "withdraw-bid")
            val restarted = fixture.restarted(0)

            assertThat(withdrawn.pendingBid).isNull()
            assertThat(withdrawn.submitted).containsExactly(PlayerId("p2"))
            assertThat(withdrawn.prompt?.players).containsExactly(PlayerId("p1"), PlayerId("p3"))
            assertThat(withdrawn.legalChoices.commands).containsExactly("bid-capital")
            assertThat(withdrawn.game).isEqualTo(before.game)
            assertThat(withdrawn.revision).isEqualTo(before.revision)
            assertThat(withdrawn.history).isEqualTo(before.history)
            assertThat(restarted).isEqualTo(withdrawn)
            assertThat(fixture.snapshot(1).pendingBid).isEqualTo(0)
        }

    @Test
    fun tokenPurchaseWithdrawalSurvivesRestartWithoutSpendingCashOrTokens() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.chooseCapitals()
            fixture.submit(0, "buy-tokens -5")
            fixture.submit(1, "buy-tokens 7")
            val before = fixture.snapshot(0)

            val withdrawn = fixture.submit(0, "withdraw-bid")

            assertThat(withdrawn.pendingBid).isNull()
            assertThat(withdrawn.prompt?.players).containsExactly(PlayerId("p1"), PlayerId("p3"))
            assertThat(withdrawn.game).isEqualTo(before.game)
            assertThat(withdrawn.revision).isEqualTo(before.revision)
            assertThat(withdrawn.history).isEqualTo(before.history)
            assertThat(fixture.restarted(0)).isEqualTo(withdrawn)
            assertThat(fixture.snapshot(1).pendingBid).isEqualTo(7)
        }

    @Test
    fun withdrawalIsOfferedOnlyToTheOwnerOfAnUnrevealedSubmission() =
        WithdrawalFixture(postgres).use { fixture ->
            val before = fixture.snapshot(0)
            val submitted = fixture.submit(0, "bid-capital 0")
            val other = fixture.snapshot(1)
            val historical =
                fixture.service.getRevision(
                    fixture.game.gameId,
                    fixture.access(0),
                    submitted.revision,
                )

            assertThat(before.legalChoices.commands).doesNotContain("withdraw-bid")
            assertThat(submitted.legalChoices.commands).contains("withdraw-bid")
            assertThat(other.legalChoices.commands).doesNotContain("withdraw-bid")
            assertThat(other.pendingBid).isNull()
            assertThat(historical.legalChoices.commands).doesNotContain("withdraw-bid")
            assertThat(historical.pendingBid).isNull()
        }

    @Test
    fun withdrawalInvalidatesOtherViewersReadinessWithoutExposingTheAmount() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            val before = fixture.service.read(fixture.game.gameId, fixture.access(1), null)
            val beforeSnapshot =
                requireNotNull(before.snapshot) { "A fresh read needs a snapshot." }

            fixture.submit(0, "withdraw-bid")
            val after = fixture.service.read(fixture.game.gameId, fixture.access(1), before.etag)
            val afterSnapshot =
                requireNotNull(after.snapshot) { "Changed readiness must return a new snapshot." }

            assertThat(after.etag).isNotEqualTo(before.etag)
            assertThat(afterSnapshot.pendingBid).isNull()
            assertThat(afterSnapshot.submitted).isEmpty()
            assertThat(afterSnapshot.game).isEqualTo(beforeSnapshot.game)
            assertThat(afterSnapshot.history).isEqualTo(beforeSnapshot.history)
            assertThat(afterSnapshot.events).isEqualTo(beforeSnapshot.events)
        }

    @Test
    fun withdrawalRestoresTheMembersMyMoveListingAtTheSameRevision() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            val before = fixture.myMoveGames()
            val revision = fixture.snapshot(0).revision

            fixture.submit(0, "withdraw-bid")
            val after = fixture.myMoveGames()

            assertThat(before.games).isEmpty()
            assertThat(after.games.map(GameSummary::id)).containsExactly(fixture.game.gameId)
            assertThat(after.games.single().myMove).isTrue()
            assertThat(after.games.single().revision).isEqualTo(revision)
        }

    @Test
    fun aCallerWithoutAPendingBidCannotWithdrawAnotherPlayersSubmission() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            val before = fixture.store.document(fixture.game.gameId)

            val failure = assertFailsWith<ServiceFailure> { fixture.submit(1, "withdraw-bid") }

            assertThat(failure.status).isEqualTo(422)
            assertThat(failure.code).isEqualTo("NO_PENDING_BID")
            assertThat(fixture.store.document(fixture.game.gameId)).isEqualTo(before)
        }

    @Test
    fun aRevealedRoundRejectsWithdrawalUsingTheCurrentRevision() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.revealCapitalBids()
            val before = fixture.store.document(fixture.game.gameId)

            val failure = assertFailsWith<ServiceFailure> { fixture.submit(0, "withdraw-bid") }

            assertThat(failure.status).isEqualTo(422)
            assertThat(failure.code).isEqualTo("NO_PENDING_BID")
            assertThat(fixture.store.document(fixture.game.gameId)).isEqualTo(before)
        }

    @Test
    fun aStaleCapitalWithdrawalCannotClearANewPendingTokenPurchase() =
        WithdrawalFixture(postgres).use { fixture ->
            val capitalRevision = fixture.snapshot(0).revision
            fixture.chooseCapitals()
            fixture.submit(0, "buy-tokens 0")
            val before = fixture.store.document(fixture.game.gameId)

            val failure =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(0, "withdraw-bid", expectedRevision = capitalRevision)
                }

            assertThat(failure.status).isEqualTo(409)
            assertThat(failure.code).isEqualTo("STALE_REVISION")
            assertThat(fixture.store.document(fixture.game.gameId)).isEqualTo(before)
            assertThat(fixture.snapshot(0).pendingBid).isEqualTo(0)
        }

    @Test
    fun withdrawingPreventsRevealUntilAReplacementSubmissionArrives() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            fixture.submit(1, "bid-capital 12")
            val revision = fixture.snapshot(0).revision

            fixture.submit(0, "withdraw-bid")
            val waiting = fixture.submit(2, "bid-capital 3")
            val revealed = fixture.submit(0, "bid-capital 9")

            assertThat(waiting.revision).isEqualTo(revision)
            assertThat(waiting.game.stage).isEqualTo(Stage.BIDDING)
            assertThat(waiting.submitted).containsExactly(PlayerId("p2"), PlayerId("p3"))
            assertThat(revealed.game.revealedBids[PlayerId("p1")]).isEqualTo(9)
            assertThat(revealed.history).contains("p1: bid-capital 9")
            assertThat(revealed.history).doesNotContain("p1: bid-capital 17")
            assertThat(revealed.revision).isEqualTo(revision + 1)
        }

    @Test
    fun racingWithdrawalAndFinalSubmissionNeverPartiallyReveal() =
        WithdrawalFixture(postgres).use { fixture ->
            fixture.submit(0, "bid-capital 17")
            fixture.submit(1, "bid-capital 12")
            val revision = fixture.snapshot(0).revision
            val secondWriter = fixture.independentWriter()
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val results =
                try {
                    val withdrawal =
                        executor.submit(
                            Callable {
                                start.await()
                                runCatching { fixture.submit(0, "withdraw-bid", revision) }
                            }
                        )
                    val finalSubmission =
                        executor.submit(
                            Callable {
                                start.await()
                                runCatching {
                                    secondWriter.submit(
                                        fixture.game.gameId,
                                        fixture.access(2),
                                        revision,
                                        "bid-capital 3",
                                    )
                                }
                            }
                        )
                    start.countDown()
                    listOf(withdrawal.get(), finalSubmission.get())
                } finally {
                    executor.shutdownNow()
                }
            val state = fixture.snapshot(0)

            if (state.game.stage == Stage.BIDDING) {
                assertThat(results.all(Result<GameSnapshot>::isSuccess)).isTrue()
                assertThat(state.revision).isEqualTo(revision)
                assertThat(state.submitted).containsExactly(PlayerId("p2"), PlayerId("p3"))
                assertThat(state.game.revealedBids).isEmpty()
                assertThat(state.history.none { it.contains("bid-capital") }).isTrue()
            } else {
                assertThat(state.game.stage).isEqualTo(Stage.CAPITAL_CHOICE)
                assertThat(state.revision).isEqualTo(revision + 1)
                assertThat(state.submitted).isEmpty()
                assertThat((results.first().exceptionOrNull() as ServiceFailure).code)
                    .isEqualTo("STALE_REVISION")
                assertThat(results.last().isSuccess).isTrue()
                assertThat(state.game.revealedBids[PlayerId("p1")]).isEqualTo(17)
            }
        }
}

private class WithdrawalFixture(postgres: Boolean) : AutoCloseable {
    private val directory = Files.createTempDirectory("aor-withdrawal-")
    private val sql = if (postgres) PostgresFixture() else null
    val store: ManagedGameStore = sql?.store ?: FileGameStore(directory)
    private val accounts: AccountStore =
        sql?.accounts ?: FileAccountStore(directory.resolve("accounts.json"))
    private val players =
        listOf("alice", "bobby", "carol").map { name -> accounts.register(AccountId(name), name) }
    val service = GameService(store, GameDriver(FixedRandom()))
    val game =
        service.createRegistered(
            players,
            players.first().id,
            RuleOptions(),
            UndoPolicy.REGULAR_TURN,
        )

    fun access(player: Int): GameAccess = GameAccess.Account(players[player].id)

    fun snapshot(player: Int): GameSnapshot = service.get(game.gameId, access(player))

    fun submit(
        player: Int,
        text: String,
        expectedRevision: Int = snapshot(player).revision,
    ): GameSnapshot =
        service.submit(
            game.gameId,
            access(player),
            expectedRevision,
            text,
            snapshot(player).submissionVersion,
        )

    fun restarted(player: Int): GameSnapshot =
        (sql?.reopened(NoWithdrawalRandom)
                ?: GameService(FileGameStore(directory), GameDriver(NoWithdrawalRandom)))
            .get(game.gameId, access(player))

    fun independentWriter(): GameService = sql?.reopened() ?: service

    fun myMoveGames(): GamePage =
        store.listGames(players.first().id, false, GameListStatus.MY_MOVE, null, 10)

    fun revealCapitalBids() {
        submit(0, "bid-capital 3")
        submit(1, "bid-capital 2")
        submit(2, "bid-capital 1")
    }

    fun chooseCapitals() {
        revealCapitalBids()
        submit(0, "choose-capital venice")
        submit(1, "choose-capital genoa")
        submit(2, "choose-capital barcelona")
    }

    override fun close() {
        sql?.close()
        directory.toFile().deleteRecursively()
    }
}

private object NoWithdrawalRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> =
        error("Reading a withdrawn submission must not shuffle cards.")

    override fun roll(count: Int): List<DieRoll> =
        error("Reading a withdrawn submission must not roll dice.")
}
