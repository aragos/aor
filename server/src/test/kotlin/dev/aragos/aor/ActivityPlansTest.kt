package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ActivityPlansTest {
    private val directories = mutableListOf<Path>()
    private val purchase =
        listOf(
            PlannedAction("purchase-shipping", "pass"),
            PlannedAction("purchase-advances", "buy-advance V"),
            PlannedAction("purchase-advances", "pass"),
            PlannedAction("purchase-stabilization", "stabilize cash"),
        )

    @AfterTest
    fun clean() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    private fun fileFixture(
        writer: GameWriter = AtomicGameWriter,
        policy: UndoPolicy = UndoPolicy.COMPETITIVE,
    ): PlanningFixture {
        val directory = Files.createTempDirectory("aor-plans-").also(directories::add)
        val store = FileGameStore(directory, writer)
        val service = GameService(store, GameDriver(FixedRandom()))
        return PlanningFixture(
            service,
            store,
            service.create(listOf("Alice", "Bob", "Carol"), undoPolicy = policy),
        )
    }

    @Test
    fun historyEditsDisarmUnexecutedPlans() {
        val fixture = fileFixture(policy = UndoPolicy.FRIENDLY_GAME)
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.plan(1, PlanOperation.ARM)
        fixture.submit(0, "pass")

        fixture.submit(0, "undo")

        assertThat(fixture.view(1).planning?.saved?.pause).isEqualTo(PlanPause.EDITED)
        assertThat(fixture.view(1).planning?.saved?.armed).isFalse()
        assertThat(fixture.view(1).planning?.saved?.actions).isEqualTo(purchase)
    }

    @Test
    fun reopeningACompletedActivityCannotResurrectItsConsumedAuthorization() {
        val fixture = fileFixture(policy = UndoPolicy.FRIENDLY_GAME)
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.plan(1, PlanOperation.ARM)
        fixture.finishFirst()
        val receipt =
            requireNotNull(fixture.view(1).planning?.executions?.singleOrNull()) {
                "The fixture requires one completed plan execution."
            }
        val stored = fixture.store.read(fixture.game.gameId)
        val from = stored.effectiveEntries().indexOfFirst { it.id == "${receipt.fromRevision}:0" }

        fixture.service.editHistory(
            fixture.game.gameId,
            fixture.access(0),
            stored.revision,
            from,
            emptyList(),
        )
        fixture.service.recoverPlans { _, failure -> throw failure }

        assertThat(fixture.view(1).game.activity?.actor).isEqualTo(PlayerId("p2"))
        assertThat(fixture.view(1).planning?.saved).isNull()
        assertThat(fixture.view(1).game.players[1].advances)
            .doesNotContain(AdvanceId.URBAN_ASCENDANCY)
    }

    @Test
    fun spectatorsCannotReadOrMutatePrivatePlans() {
        val fixture = fileFixture()
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        val access = GameAccess.Account(AccountId("spectator"))

        val public = fixture.service.get(fixture.game.gameId, access)
        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.writePlan(
                    fixture.game.gameId,
                    access,
                    public.revision,
                    null,
                    UUID.randomUUID().toString(),
                    PlanOperation.SAVE,
                    purchase,
                )
            }

        assertThat(public.planning).isNull()
        assertThat(failure.code).isEqualTo("GAME_MEMBERSHIP_REQUIRED")
    }

    @Test
    fun saveForReviewRemainsEditableOnTheLegalTurnEvenUnderCompetitiveUndo() {
        val fixture = fileFixture()
        fixture.toPurchase()
        val before = fixture.view(1)
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.finishFirst()

        val ready = fixture.view(1)
        assertThat(ready.game.players[1].cash).isEqualTo(before.game.players[1].cash)
        assertThat(ready.planning?.saved?.armed).isFalse()
        val withoutAdvance = purchase.filterNot { it.command.startsWith("buy-advance") }
        fixture.plan(1, PlanOperation.SAVE, withoutAdvance)
        fixture.plan(1, PlanOperation.EXECUTE)

        assertThat(fixture.view(1).game.players[1].advances)
            .doesNotContain(AdvanceId.URBAN_ASCENDANCY)
        assertThat(fixture.view(1).game.activity?.actor).isEqualTo(PlayerId("p3"))
    }

    @Test
    fun authorizationSurvivesRestartAndExecutesOnceWithoutTheOwnersBrowser() {
        val fixture = fileFixture()
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        val armed = fixture.plan(1, PlanOperation.ARM)
        val restarted =
            fixture.copy(service = GameService(fixture.store, GameDriver(FixedRandom())))

        restarted.finishFirst()
        val after = restarted.view(1)
        restarted.service.recoverPlans { _, failure -> throw failure }

        assertThat(after.game.players[1].advances).contains(AdvanceId.URBAN_ASCENDANCY)
        assertThat(restarted.view(1).revision).isEqualTo(after.revision)
        assertThat(after.planning?.executions).hasSize(1)
        assertThat(after.planning?.executions?.single()?.operationId)
            .isEqualTo(armed.planning?.saved?.authorizationOperationId)
        assertThat(after.history.count { it == "p2: buy-advance V" }).isEqualTo(1)
    }

    @Test
    fun privatePlanWritesDoNotChangeAnotherSeatsViewOrEtag() {
        val fixture = fileFixture()
        fixture.toPurchase()
        val before = fixture.service.read(fixture.game.gameId, fixture.access(0), null)

        fixture.plan(1, PlanOperation.SAVE, purchase)

        val after = fixture.service.read(fixture.game.gameId, fixture.access(0), null)
        assertThat(after).isEqualTo(before)
        assertThat(after.snapshot?.planning?.saved).isNull()
    }

    @Test
    fun staleSameSeatSavesCannotOverwriteANewerPlan() {
        val fixture = fileFixture()
        fixture.toPurchase()
        val before = fixture.view(1)
        fixture.plan(1, PlanOperation.SAVE, purchase)

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.writePlan(
                    fixture.game.gameId,
                    fixture.access(1),
                    before.revision,
                    before.planning?.version,
                    UUID.randomUUID().toString(),
                    PlanOperation.SAVE,
                )
            }

        assertThat(failure.code).isEqualTo("STALE_PLAN")
        assertThat(fixture.view(1).planning?.saved?.actions).isEqualTo(purchase)
    }

    @Test
    fun saveAcknowledgesItsOwnVersionWhenAutomaticProgressIncludesANewerSave() {
        val fixture = fileFixture()
        fixture.toPurchase()
        val first = fixture.plan(0, PlanOperation.SAVE, purchase)
        fixture.store.update(fixture.game.gameId) { current ->
            val plan =
                requireNotNull(current.plans[PlayerId("p1")]) {
                    "The fixture requires the first player's prepared plan."
                }
            current.copy(
                plans =
                    current.plans +
                        (PlayerId("p1") to
                            plan.copy(
                                signature = first.planning?.previewToken,
                                authorizationOperationId = UUID.randomUUID().toString(),
                            ))
            )
        }
        val competingVersion = UUID.randomUUID().toString()
        var intervene = true
        val interleaved =
            object : GameStore {
                override fun create(game: StoredGame) = fixture.store.create(game)

                override fun read(id: String): StoredGame = fixture.store.read(id)

                override fun update(id: String, transform: (StoredGame) -> StoredGame): StoredGame {
                    val accepted = fixture.store.update(id, transform)
                    if (intervene) {
                        intervene = false
                        fixture.store.update(id) { current ->
                            current.copy(
                                planVersions =
                                    current.planVersions + (PlayerId("p2") to competingVersion)
                            )
                        }
                    }
                    return accepted
                }
            }
        val service = GameService(interleaved, GameDriver(FixedRandom()))
        val before = fixture.view(1)
        val operation = UUID.randomUUID().toString()

        val response =
            service.writePlan(
                fixture.game.gameId,
                fixture.access(1),
                before.revision,
                before.planning?.version,
                operation,
                PlanOperation.SAVE,
                purchase,
            )

        assertThat(response.planning?.version).isEqualTo(competingVersion)
        assertThat(response.planWriteReceipt?.operationId).isEqualTo(operation)
        assertThat(response.planWriteReceipt?.version).isNotEqualTo(competingVersion)
        assertThat(response.game.activity?.actor).isEqualTo(PlayerId("p2"))
    }

    @Test
    fun cancellationBeforeTheTurnPreventsAutomaticExecution() {
        val fixture = fileFixture()
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.plan(1, PlanOperation.ARM)
        fixture.plan(1, PlanOperation.DISARM)

        fixture.finishFirst()

        assertThat(fixture.view(1).game.activity?.actor).isEqualTo(PlayerId("p2"))
        assertThat(fixture.view(1).planning?.executions).isEmpty()
    }

    @Test
    fun cancellationCanWinAfterPublicProgressWhenItsAuthorizationIsStillUnconsumed() {
        val fixture = fileFixture()
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        val armed = fixture.plan(1, PlanOperation.ARM)
        fixture.store.update(fixture.game.gameId) { game ->
            listOf("pass", "pass", "stabilize cash").fold(game) { current, command ->
                GameDriver(FixedRandom()).submit(current, PlayerId("p1"), command)
            }
        }

        val canceled =
            fixture.service.writePlan(
                fixture.game.gameId,
                fixture.access(1),
                armed.revision,
                armed.planning?.version,
                UUID.randomUUID().toString(),
                PlanOperation.DISARM,
            )

        assertThat(canceled.planning?.saved?.armed).isFalse()
        assertThat(canceled.planning?.executions).isEmpty()
        assertThat(canceled.game.activity?.actor).isEqualTo(PlayerId("p2"))
    }

    @Test
    fun anInvalidSegmentCannotBeArmedOrCommitItsValidPrefix() {
        val fixture = fileFixture()
        fixture.toPurchase()
        val before = fixture.view(1)
        fixture.plan(
            1,
            PlanOperation.SAVE,
            listOf(
                purchase.first(),
                PlannedAction("purchase-advances", "buy-advance U"),
            ),
        )

        val failure = assertFailsWith<ServiceFailure> { fixture.plan(1, PlanOperation.ARM) }

        assertThat(failure.code).isEqualTo("PLAN_INVALID")
        assertThat(fixture.view(1).revision).isEqualTo(before.revision)
    }

    @Test
    fun changedConsequencesDisarmInsteadOfExecuting() {
        val fixture = fileFixture()
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.plan(1, PlanOperation.ARM)
        fixture.store.update(fixture.game.gameId) { current ->
            current.copy(
                plans =
                    current.plans +
                        (PlayerId("p2") to
                            requireNotNull(current.plans[PlayerId("p2")]) {
                                    "The fixture requires its saved plan."
                                }
                                .copy(signature = "0".repeat(64)))
            )
        }

        fixture.finishFirst()

        assertThat(fixture.view(1).planning?.saved?.pause).isEqualTo(PlanPause.CHANGED)
        assertThat(fixture.view(1).game.activity?.actor).isEqualTo(PlayerId("p2"))
    }

    @Test
    fun formatUpgradePreservesPendingBidsAndOriginalRevisions() {
        val fixture = fileFixture()
        fixture.submit(0, "bid-capital 3")
        val before = fixture.store.read(fixture.game.gameId)
        val source = fixture.makeVersionSix()
        val quote = fixture.service.planningUpgrade(fixture.game.gameId, fixture.access(0))

        fixture.service.planningUpgrade(
            fixture.game.gameId,
            fixture.access(0),
            quote.revision,
            quote.sourceHash,
        )

        val after = fixture.store.read(fixture.game.gameId)
        assertThat(after.state()).isEqualTo(before.state())
        assertThat(after.revisions.dropLast(1)).isEqualTo(before.revisions)
        assertThat(after.effectiveEntries()).isEqualTo(before.effectiveEntries())
        assertThat(after.pendingBids).isEqualTo(before.pendingBids)
        assertThat(after.submissionVersions).isEqualTo(before.submissionVersions)
        assertThat(
                fixture.store.archivedDocument(
                    fixture.game.gameId,
                    requireNotNull(after.revisions.last().storageArchive) {
                        "The upgrade requires its source archive."
                    },
                )
            )
            .isEqualTo(source)
    }

    @Test
    fun aChangedPrivateBidInvalidatesTheUpgradeDryRun() {
        val fixture = fileFixture()
        fixture.submit(0, "bid-capital 3")
        val source = fixture.makeVersionSix()
        val quote = fixture.service.planningUpgrade(fixture.game.gameId, fixture.access(0))
        val changed = json.readTree(source) as ObjectNode
        (changed["pendingBids"] as ObjectNode).put("p1", 4)
        val directory = directories.last()
        Files.writeString(directory.resolve("${fixture.game.gameId}.json"), changed.toString())

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.planningUpgrade(
                    fixture.game.gameId,
                    fixture.access(0),
                    quote.revision,
                    quote.sourceHash,
                )
            }

        assertThat(failure.code).isEqualTo("STALE_UPGRADE")
        assertThat(fixture.store.archives(fixture.game.gameId)).isEmpty()
    }

    @Test
    fun postgresRestartAndConcurrentWorkersPublishOneExecution() =
        PostgresFixture().use { database ->
            val fixture = PlanningFixture(database.service(), database.store)
            fixture.toPurchase()
            fixture.plan(1, PlanOperation.SAVE, purchase)
            fixture.plan(1, PlanOperation.ARM)
            // Advance with the driver to leave durable actionable work for the recovery workers.
            fixture.store.update(fixture.game.gameId) { game ->
                listOf("pass", "pass", "stabilize cash").fold(game) { current, command ->
                    GameDriver(FixedRandom()).submit(current, PlayerId("p1"), command)
                }
            }
            val second = database.reopened()
            val pool = Executors.newFixedThreadPool(2)
            try {
                pool
                    .invokeAll(
                        listOf(
                            Callable { fixture.service.drainPlans(fixture.game.gameId) },
                            Callable { second.drainPlans(fixture.game.gameId) },
                        )
                    )
                    .forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }

            assertThat(fixture.view(1).history.count { it == "p2: buy-advance V" }).isEqualTo(1)
            assertThat(fixture.view(1).planning?.executions).hasSize(1)
        }

    @Test
    fun failedPublicationLeavesAuthorizationAndGameProgressTogether() {
        var fail = false
        val fixture =
            fileFixture(
                GameWriter { path, text ->
                    if (fail) throw IOException("fixture write failure")
                    AtomicGameWriter.write(path, text)
                }
            )
        fixture.toPurchase()
        fixture.plan(1, PlanOperation.SAVE, purchase)
        fixture.plan(1, PlanOperation.ARM)
        fixture.store.update(fixture.game.gameId) { game ->
            listOf("pass", "pass", "stabilize cash").fold(game) { current, command ->
                GameDriver(FixedRandom()).submit(current, PlayerId("p1"), command)
            }
        }
        val before = fixture.store.document(fixture.game.gameId)
        fail = true

        assertFailsWith<ServiceFailure> { fixture.service.drainPlans(fixture.game.gameId) }

        assertThat(fixture.store.document(fixture.game.gameId)).isEqualTo(before)
    }

    @Test
    fun cardPurchaseStopsAtTheDrawWithoutSubmittingThePlannedPass() {
        val fixture = fileFixture()
        fixture.toPurchase()
        for (seat in 0..2) {
            fixture.plan(seat, PlanOperation.SAVE, purchase)
            fixture.plan(seat, PlanOperation.EXECUTE)
        }
        for (seat in 0..2) fixture.submit(seat, "pass")
        for (seat in 0..2) fixture.submit(seat, "buy-tokens ${seat - 2}")
        if (fixture.view(0).game.stage == Stage.DRAW_CARD) fixture.submit(0, "pass")
        assertThat(fixture.view(1).game.stage).isEqualTo(Stage.BUY_CARD)
        val before = fixture.view(1)
        fixture.plan(
            1,
            PlanOperation.SAVE,
            listOf(
                PlannedAction("buy-card", "buy-card"),
                PlannedAction("buy-card", "pass"),
            ),
        )
        fixture.plan(1, PlanOperation.ARM)

        fixture.submit(0, "pass")

        val after = fixture.view(1)
        assertThat(after.game.hand).hasSize(before.game.hand.size + 1)
        assertThat(after.history.drop(before.history.size)).contains("p2: buy-card")
        assertThat(after.history.drop(before.history.size)).doesNotContain("p2: pass")
        assertThat(after.game.activity?.actor).isEqualTo(PlayerId("p3"))
    }

    @Test
    fun postgresFormatUpgradeKeepsOldRevisionRowsAndPrivateBids() =
        PostgresFixture().use { database ->
            val fixture = PlanningFixture(database.service(), database.store)
            fixture.submit(0, "bid-capital 3")
            val before = fixture.store.read(fixture.game.gameId)
            database.database.write { connection ->
                connection
                    .prepareStatement(
                        "UPDATE games SET document = jsonb_set(document, '{fileVersion}', '6'), storage_version = storage_version + 1 WHERE id = ?"
                    )
                    .use { statement ->
                        statement.setObject(1, UUID.fromString(fixture.game.gameId))
                        statement.executeUpdate()
                    }
            }
            val source = fixture.store.document(fixture.game.gameId)
            val quote = fixture.service.planningUpgrade(fixture.game.gameId, fixture.access(0))

            fixture.service.planningUpgrade(
                fixture.game.gameId,
                fixture.access(0),
                quote.revision,
                quote.sourceHash,
            )

            val after = database.reopened().store.read(fixture.game.gameId)
            assertThat(after.revisions.dropLast(1)).isEqualTo(before.revisions)
            assertThat(after.pendingBids).isEqualTo(before.pendingBids)
            assertThat(after.submissionVersions).isEqualTo(before.submissionVersions)
            assertThat(
                    fixture.store.archivedDocument(
                        fixture.game.gameId,
                        requireNotNull(after.revisions.last().storageArchive) {
                            "The upgrade requires its exact source archive."
                        },
                    )
                )
                .isEqualTo(source)
        }

    private fun PlanningFixture.makeVersionSix(): String {
        val root = json.readTree(store.document(game.gameId)) as ObjectNode
        root.put("fileVersion", 6)
        root.remove(listOf("plans", "planVersions", "planReceipts", "planExecutions"))
        val contents = root.toString()
        Files.writeString(directories.last().resolve("${game.gameId}.json"), contents)
        return contents
    }
}

private data class PlanningFixture(
    val service: GameService,
    val store: ManagedGameStore,
    val game: CreatedGame =
        service.create(listOf("Alice", "Bob", "Carol"), undoPolicy = UndoPolicy.COMPETITIVE),
) {
    fun access(seat: Int): GameAccess =
        GameAccess.SeatToken(game.links[seat].path.substringAfter("token="))

    fun view(seat: Int): GameSnapshot = service.get(game.gameId, access(seat))

    fun submit(seat: Int, command: String) {
        val before = view(seat)
        service.submit(
            game.gameId,
            access(seat),
            before.revision,
            command,
            before.submissionVersion,
        )
    }

    fun toPurchase() {
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
            .forEach { (seat, command) -> submit(seat, command) }
        assertThat(view(0).game.stage).isEqualTo(Stage.PURCHASE)
    }

    fun finishFirst() {
        listOf("pass", "pass", "stabilize cash").forEach { submit(0, it) }
    }

    fun plan(
        seat: Int,
        operation: PlanOperation,
        actions: List<PlannedAction> = emptyList(),
    ): GameSnapshot {
        val snapshot = view(seat)
        return service.writePlan(
            game.gameId,
            access(seat),
            snapshot.revision,
            snapshot.planning?.version,
            UUID.randomUUID().toString(),
            operation,
            actions,
            snapshot.planning?.previewToken,
        )
    }
}
