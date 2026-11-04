package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotDifficulty
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BotAutomationTest(private val postgres: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}")
        fun backends(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @Test
    fun automatedSeatsHaveOrdinaryIdentityWithoutHumanCredentials() =
        AutomationFixture(postgres).use { fixture ->
            val stored = fixture.service.store.read(fixture.game.gameId)
            val snapshot = fixture.snapshot()

            assertThat(stored.header.players.map(Player::id))
                .containsExactly(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))
                .inOrder()
            assertThat(stored.credentials.keys).containsExactly(PlayerId("p1"))
            assertThat(fixture.game.links).hasSize(1)
            assertThat(snapshot.automation?.players?.map { it.difficulty })
                .containsExactly(BotDifficulty.NORMAL, BotDifficulty.HARD)
                .inOrder()
            assertThat(snapshot.automation?.canControl).isTrue()
        }

    @Test
    fun anotherHumanCannotReadAutomatedPrivateObservations() =
        AutomationFixture(postgres, humans = 2, bots = 1).use { fixture ->
            val failure =
                assertFailsWith<ServiceFailure> {
                    BotAutomation.control(
                        fixture.service,
                        fixture.game.gameId,
                        fixture.access(1),
                        "claim",
                        fixture.runnerId,
                        expectedEpoch = 0,
                        now = 1000,
                    )
                }

            assertThat(failure.code).isEqualTo("BOT_CONTROL_FORBIDDEN")
            assertThat(fixture.snapshot(1).automation?.canControl).isFalse()
        }

    @Test
    fun oneLeaseOwnsTheBotsAndExpiredOwnersAreFenced() =
        AutomationFixture(postgres).use { fixture ->
            val first = fixture.claim()
            val anotherRunner = UUID.randomUUID().toString()
            val busy =
                assertFailsWith<ServiceFailure> {
                    fixture.control("claim", first.epoch, anotherRunner, now = 2000)
                }
            assertThat(busy.code).isEqualTo("BOT_RUNNER_BUSY")

            val replacement = fixture.control("claim", first.epoch, anotherRunner, now = 32000)
            val stale =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(first, first.observations.first(), "bid-capital 10", now = 32001)
                }

            assertThat(replacement.epoch).isGreaterThan(first.epoch)
            assertThat(stale.code).isEqualTo("BOT_STALE_EPOCH")
            assertThat(fixture.service.store.read(fixture.game.gameId).pendingBids).isEmpty()
        }

    @Test
    fun competingBrowsersCanPublishOnlyOneInitialLease() =
        AutomationFixture(postgres).use { fixture ->
            val writers =
                listOf(fixture.service, if (postgres) fixture.reopened() else fixture.service)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = writers.map { writer ->
                    executor.submit(
                        Callable {
                            start.await()
                            runCatching {
                                BotAutomation.control(
                                    writer,
                                    fixture.game.gameId,
                                    fixture.access(),
                                    "claim",
                                    UUID.randomUUID().toString(),
                                    expectedEpoch = 0,
                                    now = 1000,
                                )
                            }
                        }
                    )
                }
                start.countDown()
                val completed = results.map { it.get() }

                assertThat(completed.count { it.isSuccess }).isEqualTo(1)
                assertThat(completed.count { it.exceptionOrNull() is ServiceFailure }).isEqualTo(1)
                assertThat(fixture.service.store.read(fixture.game.gameId).botRunner?.epoch)
                    .isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun explicitResumeTransfersOwnershipAndFencesTheOldBrowser() =
        AutomationFixture(postgres).use { fixture ->
            val original = fixture.claim()
            val replacement =
                fixture.control("resume", original.epoch, UUID.randomUUID().toString())

            val stale =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(original, original.observations.first(), "bid-capital 10")
                }

            assertThat(replacement.epoch).isGreaterThan(original.epoch)
            assertThat(stale.code).isEqualTo("BOT_STALE_EPOCH")
        }

    @Test
    fun acceptedSealedCommandHasDurableReceiptAndRetryDoesNotActAgain() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val job = work.observations.first()
            val operationId = UUID.randomUUID().toString()
            val accepted = fixture.submit(work, job, "bid-capital 10", operationId)
            val restarted = fixture.reopened()
            val retried =
                BotAutomation.submit(
                    restarted,
                    fixture.game.gameId,
                    fixture.access(),
                    fixture.runnerId,
                    work.epoch,
                    work.revision,
                    job.submissionVersion,
                    job.seat,
                    job.decisionId,
                    "bid-capital 10",
                    operationId,
                    now = 1100,
                )
            val stored = restarted.store.read(fixture.game.gameId)

            assertThat(retried.operationReceipts).contains(operationId)
            assertThat(retried.revision).isEqualTo(accepted.revision)
            assertThat(stored.pendingBids).containsExactly(job.seat, 10)
            assertThat(stored.submissionVersions[job.seat]).isNotNull()
            assertThat(stored.botRunner?.receipts).hasSize(1)
        }

    @Test
    fun operationIdentityCannotAuthorizeADifferentCommand() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val job = work.observations.first()
            val operationId = UUID.randomUUID().toString()
            fixture.submit(work, job, "bid-capital 10", operationId)

            val failure =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(work, job, "bid-capital 11", operationId)
                }

            assertThat(failure.code).isEqualTo("OPERATION_ID_REUSED")
            assertThat(fixture.service.store.read(fixture.game.gameId).pendingBids[job.seat])
                .isEqualTo(10)
        }

    @Test
    fun hiddenHumanBidDoesNotChangeAnyBotObservation() =
        AutomationFixture(postgres).use { fixture ->
            val before = fixture.claim()
            fixture.humanCommand("bid-capital 37")

            val after =
                BotAutomation.read(
                    fixture.service,
                    fixture.game.gameId,
                    fixture.access(),
                    fixture.runnerId,
                    before.epoch,
                    now = 1100,
                )

            assertThat(after.observations).isEqualTo(before.observations)
        }

    @Test
    fun botPrivateSubmissionPreservesHumanReadValidator() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val humanRead = fixture.service.read(fixture.game.gameId, fixture.access(), null)
            fixture.submit(work, work.observations.first(), "bid-capital 10")
            val beforeReplacement =
                fixture.service.read(fixture.game.gameId, fixture.access(), null)
            val bot = work.observations.first().seat
            fixture.service.store.update(fixture.game.gameId) { current ->
                current.copy(pendingBids = current.pendingBids + (bot to 11))
            }

            val after =
                fixture.service.read(fixture.game.gameId, fixture.access(), beforeReplacement.etag)

            assertThat(humanRead.snapshot?.pendingBid).isNull()
            assertThat(after.etag).isEqualTo(beforeReplacement.etag)
            assertThat(after.snapshot).isNull()
        }

    @Test
    fun pausingCancelsOutstandingWorkAndRequiresExplicitResume() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val paused = fixture.control("pause", work.epoch)
            val claimed = fixture.control("claim", paused.epoch)
            val stale =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(work, work.observations.first(), "bid-capital 10")
                }

            assertThat(claimed.paused).isTrue()
            assertThat(claimed.observations).isEmpty()
            assertThat(stale.code).isEqualTo("BOT_STALE_EPOCH")
        }

    @Test
    fun resumeObtainsFreshDecisionWorkAfterPause() =
        AutomationFixture(postgres).use { fixture ->
            val paused = fixture.control("pause", fixture.claim().epoch)

            val resumed = fixture.control("resume", paused.epoch)

            assertThat(resumed.paused).isFalse()
            assertThat(resumed.epoch).isGreaterThan(paused.epoch)
            assertThat(resumed.observations).hasSize(1)
        }

    @Test
    fun regularUndoPreservesTheSavedGameAndRunner() =
        AutomationFixture(postgres, policy = UndoPolicy.REGULAR_TURN).use { fixture ->
            fixture.prepareHumanExpansion()
            val work = fixture.claim()
            val saved = GameDocuments.read(fixture.game.gameId, json.readTree(fixture.document()))
            val placement = fixture.placement()
            fixture.humanCommand(placement)

            val undone = fixture.humanCommand("undo")
            val restored = fixture.reopened().store.read(fixture.game.gameId)

            assertThat(undone.automation?.paused).isFalse()
            assertThat(restored.botRunner).isEqualTo(saved.botRunner)
            assertThat(restored.state()).isEqualTo(saved.state())
            assertThat(restored.revisions.take(saved.revisions.size)).isEqualTo(saved.revisions)
            assertThat(restored.bots).isEqualTo(saved.bots)
            assertThat(restored.credentials).isEqualTo(saved.credentials)
            assertThat(restored.pendingBids).isEqualTo(saved.pendingBids)
            assertThat(fixture.read(work.epoch).observations).isEmpty()
        }

    @Test
    fun regularUndoAllowsTheSameBrowserToSubmitTheNextBotDecision() =
        AutomationFixture(postgres, policy = UndoPolicy.REGULAR_TURN).use { fixture ->
            fixture.prepareHumanExpansion()
            val work = fixture.claim()
            fixture.humanCommand(fixture.placement())
            fixture.humanCommand("undo")
            fixture.humanCommand("pass")
            val fresh = fixture.read(work.epoch)
            val job = fresh.observations.first()
            val command = job.observation.candidates.first().command

            val accepted = fixture.submit(fresh, job, command)

            assertThat(accepted.paused).isFalse()
            assertThat(accepted.epoch).isEqualTo(work.epoch)
            assertThat(accepted.operationReceipts).hasSize(1)
        }

    @Test
    fun regularHistoryEditPreservesTheRunner() =
        AutomationFixture(postgres, policy = UndoPolicy.REGULAR_TURN).use { fixture ->
            fixture.prepareHumanExpansion()
            fixture.claim()
            val before = fixture.service.store.read(fixture.game.gameId).botRunner
            val placement = fixture.placement()
            val replacement = fixture.placement(tokens = 2)
            val placed = fixture.humanCommand(placement)

            val edited =
                fixture.service.editHistory(
                    fixture.game.gameId,
                    fixture.access(),
                    placed.revision,
                    placed.undo.actions.last().entry,
                    listOf(replacement),
                )

            assertThat(edited.automation?.paused).isFalse()
            assertThat(fixture.service.store.read(fixture.game.gameId).botRunner).isEqualTo(before)
        }

    @Test
    fun regularUndoPreservesAnExplicitPause() =
        AutomationFixture(postgres, policy = UndoPolicy.REGULAR_TURN).use { fixture ->
            fixture.prepareHumanExpansion()
            fixture.control("pause", fixture.claim().epoch)
            val before = fixture.service.store.read(fixture.game.gameId).botRunner
            fixture.humanCommand(fixture.placement())

            val undone = fixture.humanCommand("undo")

            assertThat(undone.automation?.paused).isTrue()
            assertThat(fixture.service.store.read(fixture.game.gameId).botRunner).isEqualTo(before)
        }

    @Test
    fun rewindPausesBotsAndFencesThePreviousBrowser() =
        AutomationFixture(postgres).use { fixture ->
            fixture.prepareFirstTurn()
            val work = fixture.claim()
            val target =
                checkNotNull(fixture.snapshot().undo.latestTarget) {
                    "A complete token-purchase batch must provide a rewind destination."
                }

            val rewound = fixture.humanCommand("undo ${target.entryCount}")
            val stale =
                assertFailsWith<ServiceFailure> {
                    BotAutomation.read(
                        fixture.service,
                        fixture.game.gameId,
                        fixture.access(),
                        fixture.runnerId,
                        work.epoch,
                        now = 1100,
                    )
                }

            assertThat(rewound.automation?.paused).isTrue()
            assertThat(rewound.automation?.epoch).isGreaterThan(work.epoch)
            assertThat(stale.code).isEqualTo("BOT_STALE_EPOCH")
        }

    @Test
    fun rejectedCommandDoesNotConsumeReceiptOrAdvanceState() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val before = fixture.document()

            assertFailsWith<RuleViolation> {
                fixture.submit(work, work.observations.first(), "bid-capital 999")
            }

            assertThat(fixture.document()).isEqualTo(before)
        }

    @Test
    fun botCannotSubmitForTheHumanSeat() =
        AutomationFixture(postgres).use { fixture ->
            val work = fixture.claim()
            val fake = work.observations.first().copy(seat = PlayerId("p1"))

            val failure =
                assertFailsWith<ServiceFailure> {
                    fixture.submit(work, fake, "bid-capital 10")
                }

            assertThat(failure.code).isEqualTo("BOT_DECISION_CHANGED")
        }

    @Test
    fun creatorAbandonmentRecordsEveryBotsSeparateAgreement() =
        AutomationFixture(postgres).use { fixture ->
            fixture.claim()

            val abandoned =
                fixture.service.abandonment(
                    fixture.game.gameId,
                    fixture.access(),
                    fixture.snapshot().revision,
                    UUID.randomUUID().toString(),
                    AbandonmentOperation.PROPOSE,
                    null,
                )
            val stored = fixture.service.store.read(fixture.game.gameId)

            assertThat(abandoned.status).isEqualTo(GameStatus.ABANDONED)
            assertThat(stored.revisions.takeLast(3).map(Revision::author))
                .containsExactly(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))
                .inOrder()
            assertThat(stored.botRunner?.paused).isTrue()
            assertThat(stored.botRunner?.runnerId).isNull()
        }

    @Test
    fun anotherHumansProposalDoesNotAgreeForBotsBeforeCreatorAgrees() =
        AutomationFixture(postgres, humans = 2, bots = 1).use { fixture ->
            val proposed =
                fixture.service.abandonment(
                    fixture.game.gameId,
                    fixture.access(1),
                    fixture.snapshot().revision,
                    UUID.randomUUID().toString(),
                    AbandonmentOperation.PROPOSE,
                    null,
                )

            assertThat(proposed.abandonment?.agreed).containsExactly(PlayerId("p2"))
            assertThat(proposed.status).isEqualTo(GameStatus.ACTIVE)
        }

    @Test
    fun creatorAgreementToAnotherHumansProposalAlsoAgreesForBots() =
        AutomationFixture(postgres, humans = 2, bots = 1).use { fixture ->
            val proposed =
                fixture.service.abandonment(
                    fixture.game.gameId,
                    fixture.access(1),
                    fixture.snapshot().revision,
                    UUID.randomUUID().toString(),
                    AbandonmentOperation.PROPOSE,
                    null,
                )

            val accepted =
                fixture.service.abandonment(
                    fixture.game.gameId,
                    fixture.access(),
                    proposed.revision,
                    UUID.randomUUID().toString(),
                    AbandonmentOperation.AGREE,
                    checkNotNull(proposed.abandonment).requestId.toString(),
                )

            assertThat(accepted.status).isEqualTo(GameStatus.ABANDONED)
        }

    @Test
    fun oldHumanOnlyDocumentsLoadReplayAndContinueWithoutBotFields() =
        AutomationFixture(postgres, humans = 3, bots = 0).use { fixture ->
            val root = json.readTree(fixture.document()) as ObjectNode
            root.remove(listOf("bots", "botRunner"))

            val restored = GameDocuments.read(fixture.game.gameId, root)
            val continued =
                fixture.service.driver.submit(restored, PlayerId("p1"), "bid-capital 12")

            assertThat(restored.bots).isEmpty()
            assertThat(restored.botRunner).isNull()
            assertThat(continued.pendingBids[PlayerId("p1")]).isEqualTo(12)
            assertThat(continued.state()).isEqualTo(restored.state())
        }
}

private class AutomationFixture(
    postgres: Boolean,
    humans: Int = 1,
    bots: Int = 2,
    policy: UndoPolicy = UndoPolicy.LINEAR_GAME,
) : AutoCloseable {
    private val directory = Files.createTempDirectory("aor-bot-test-")
    private val database = if (postgres) PostgresFixture() else null
    val service = database?.service() ?: createGameService(directory, FixedRandom())
    val game =
        service.create(
            names = List(humans) { "Human ${it + 1}" },
            bots =
                List(bots) { index ->
                    AutomatedPlayerRequest(
                        "Automated ${index + 1}",
                        if (index == 0) BotDifficulty.NORMAL else BotDifficulty.HARD,
                    )
                },
            undoPolicy = policy,
        )
    val runnerId: String = UUID.randomUUID().toString()

    fun access(human: Int = 0): GameAccess =
        GameAccess.SeatToken(game.links[human].path.substringAfter("token="))

    fun snapshot(human: Int = 0): GameSnapshot = service.get(game.gameId, access(human))

    fun claim(): BotWork = control("claim", expectedEpoch = 0)

    fun read(epoch: Int): BotWork =
        BotAutomation.read(service, game.gameId, access(), runnerId, epoch, now = 1100)

    fun control(
        operation: String,
        expectedEpoch: Int,
        runner: String = runnerId,
        now: Long = 1000,
    ): BotWork =
        BotAutomation.control(service, game.gameId, access(), operation, runner, expectedEpoch, now)

    fun submit(
        work: BotWork,
        job: BotDecisionWork,
        command: String,
        operationId: String = UUID.randomUUID().toString(),
        now: Long = 1100,
    ): BotWork =
        BotAutomation.submit(
            service,
            game.gameId,
            access(),
            runnerId,
            work.epoch,
            work.revision,
            job.submissionVersion,
            job.seat,
            job.decisionId,
            command,
            operationId,
            now,
        )

    fun humanCommand(command: String): GameSnapshot {
        val current = snapshot()
        return service.submit(
            game.gameId,
            access(),
            current.revision,
            command,
            current.submissionVersion,
        )
    }

    fun document(): String = (service.store as ManagedGameStore).document(game.gameId)

    fun placement(tokens: Int = 1): String {
        val destination =
            snapshot()
                .game
                .expansionOffers
                .first { offer ->
                    offer.primaryAction == ExpansionAction.PLACE &&
                        ((offer.blockers.isEmpty() && offer.requiredTokens == tokens) ||
                            offer.partialPlacements.any { it.tokens == tokens })
                }
                .destination
        return "place $tokens $destination"
    }

    fun prepareHumanExpansion() {
        prepareFirstTurn()
        service.store.update(game.gameId) { initial ->
            var current = initial
            repeat(100) {
                val state = current.state()
                val prompt = state.prompts().single() as Prompt.SinglePlayer
                if (state.stage == Stage.EXPANSION && prompt.player == PlayerId("p1")) {
                    return@update current
                }
                val choices = state.legalChoices(prompt.player).commands
                val command =
                    when {
                        "stabilize cash" in choices -> "stabilize cash"
                        "pass" in choices -> "pass"
                        else -> choices.first()
                    }
                current = service.driver.submit(current, prompt.player, command)
            }
            error("The fixture must reach the human's expansion activity.")
        }
    }

    fun prepareFirstTurn() {
        service.store.update(game.gameId) { initial ->
            val bids =
                initial.header.players.withIndex().fold(initial) { current, (index, player) ->
                    service.driver.submit(current, player.id, Command.BidCapital(3 - index))
                }
            val capitals =
                initial.header.players.fold(bids) { current, _ ->
                    val prompt = current.state().prompts().single() as Prompt.SinglePlayer
                    service.driver.submit(
                        current,
                        prompt.player,
                        current.state().legalChoices(prompt.player).commands.first(),
                    )
                }
            initial.header.players.withIndex().fold(capitals) { current, (index, player) ->
                service.driver.submit(current, player.id, Command.BuyTokens(3 - index))
            }
        }
    }

    fun reopened(): GameService =
        database?.reopened() ?: createGameService(directory, FixedRandom())

    override fun close() {
        database?.close()
        directory.toFile().deleteRecursively()
    }
}
