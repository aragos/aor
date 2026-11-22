package dev.aragos.aor

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.PhaseInitiativeInput
import dev.aragos.aor.ai.PhaseInitiativePolicy
import dev.aragos.aor.ai.hardCheckpointVersions
import java.security.MessageDigest
import java.util.UUID

/** A new automated seat; its display name is fixed when the game is created. */
data class AutomatedPlayerRequest(val name: String) {
    init {
        require(name.isNotBlank() && name == name.trim() && name.length <= 60) {
            "An automated player requires a trimmed display name of one through 60 characters."
        }
    }
}

/** Policy randomness is separate from authoritative game outcomes. */
data class AutomatedPlayer(
    val difficulty: BotDifficulty,
    val policyVersion: String,
    val seed: Int,
) {
    init {
        require(policyVersion.matches(Regex("[a-z0-9-]{1,80}"))) {
            "An automated-player policy requires a canonical version identifier."
        }
    }
}

/** A private receipt confirms an accepted command without exposing its hidden parameters. */
data class BotOperationReceipt(
    val operationId: String,
    val requestHash: String,
    val revision: Int,
) {
    init {
        require(GameDocuments.GAME_ID_PATTERN.matches(operationId)) {
            "Automated command receipts require canonical UUIDs."
        }
        require(requestHash.matches(Regex("[0-9a-f]{64}"))) {
            "Automated command receipts require a SHA-256 request hash."
        }
        require(revision > 0) { "Automated command receipts require a positive revision." }
    }
}

/**
 * Browser leases last 30 seconds. [epoch] fences every earlier owner after release or takeover.
 * Receipts are bounded reconciliation facts and survive rewind; they never replay bot decisions.
 * [pauseAfterPhase] is the creator's game-wide pacing setting. [pausedAfterPhase] records the
 * reviewed phase and [pausedAfterPlayer] identifies the automated seat for a sequential activity. A
 * shared reveal or a phase pause saved before seat-level pacing has no seat.
 */
data class BotRunnerState(
    val epoch: Int = 0,
    val paused: Boolean = false,
    val runnerId: String? = null,
    val leaseUntil: Long = 0,
    val receipts: List<BotOperationReceipt> = emptyList(),
    val pauseAfterPhase: Boolean = false,
    val pausedAfterPhase: Stage? = null,
    val pausedAfterPlayer: PlayerId? = null,
) {
    init {
        require(epoch >= 0 && leaseUntil >= 0) {
            "Browser ownership requires a nonnegative epoch and lease deadline."
        }
        require((runnerId == null) == (leaseUntil == 0L)) {
            "A browser lease requires both its runner identity and deadline."
        }
        require(pausedAfterPhase == null || paused) {
            "A completed phase can only be the reason for a paused browser runner."
        }
        require(pausedAfterPlayer == null || pausedAfterPhase != null) {
            "A reviewed seat requires a completed phase."
        }
        require(!paused || runnerId == null) {
            "Paused automated players cannot retain an active browser lease."
        }
        require(runnerId == null || GameDocuments.GAME_ID_PATTERN.matches(runnerId)) {
            "Browser runner identifiers must be canonical UUIDs."
        }
        require(
            receipts.size <= 64 && receipts.map { it.operationId }.distinct().size == receipts.size
        ) {
            "Automated command receipts must be bounded and unique."
        }
    }
}

data class AutomatedPlayerView(
    val id: PlayerId,
    val name: String,
    val difficulty: BotDifficulty,
    val policyVersion: String,
)

/** Public controller configuration; private lease identity and receipts are excluded. */
data class AutomationView(
    val players: List<AutomatedPlayerView>,
    val canControl: Boolean,
    val paused: Boolean,
    val epoch: Int,
    val pauseAfterPhase: Boolean,
    val pausedAfterPhase: Stage?,
    val pausedAfterPlayer: PlayerId?,
)

data class BotDecisionWork(
    val seat: PlayerId,
    val difficulty: BotDifficulty,
    val seed: Int,
    val decisionId: String,
    val submissionVersion: String?,
    val observation: BotObservation,
    val policyVersion: String,
    val initiative: PhaseInitiativeInput? = null,
    val finish: FinalAdvanceOffer? = null,
)

/** Creator-only work. Each observation belongs only to its acting automated seat. */
data class BotWork(
    val revision: Int,
    val epoch: Int,
    val leaseUntil: Long,
    val paused: Boolean,
    val observations: List<BotDecisionWork>,
    val operationReceipts: List<String>,
)

/** Fenced automated decisions use the ordinary authority driver under the game lock. */
internal object BotAutomation {
    private const val LEASE_MILLIS = 30000L

    fun view(game: StoredGame, viewer: PlayerId?): AutomationView? {
        val runner = game.botRunner ?: return null
        return AutomationView(
            players =
                game.header.players.mapNotNull { player ->
                    game.bots[player.id]?.let { bot ->
                        AutomatedPlayerView(
                            player.id,
                            player.name,
                            bot.difficulty,
                            bot.policyVersion,
                        )
                    }
                },
            canControl = viewer == game.header.creatorId && active(game),
            paused = runner.paused || !active(game),
            epoch = runner.epoch,
            pauseAfterPhase = runner.pauseAfterPhase,
            pausedAfterPhase = runner.pausedAfterPhase,
            pausedAfterPlayer = runner.pausedAfterPlayer,
        )
    }

    /** A successful human game command releases a review pause without granting a browser lease. */
    fun beforeHumanAction(game: StoredGame, player: PlayerId): StoredGame =
        if (player in game.bots) game else resumeReviewPause(game)

    private fun resumeReviewPause(game: StoredGame): StoredGame {
        val runner = game.botRunner ?: return game
        if (runner.pausedAfterPhase == null) return game
        return game.copy(
            botRunner =
                runner.copy(
                    epoch = nextEpoch(runner),
                    paused = false,
                    pausedAfterPhase = null,
                    pausedAfterPlayer = null,
                )
        )
    }

    /** Check the completed seat activity after all authority outcomes for the command resolve. */
    fun afterCommit(game: StoredGame, before: GameState, after: GameState): StoredGame {
        val runner = game.botRunner ?: return game
        if (!runner.pauseAfterPhase || runner.paused || after.stage == Stage.FINISHED) return game
        val phase = before.stage
        if (phase == Stage.TURN_ORDER_TIE) return game
        val phaseChanged = phase != after.stage || before.turnNumber != after.turnNumber
        if (phase == Stage.BIDDING || phase == Stage.TOKEN_PURCHASE) {
            return if (
                phaseChanged &&
                    HistoryProjection.hasAutomatedDecision(game, before.entryCount, game.bots.keys)
            ) {
                pause(game, phase)
            } else game
        }
        val owner = before.flow.actor
        if (owner != null && before.flow.phaseBookkeepingApplied) {
            if (!phaseChanged && owner == after.flow.actor) return game
            return if (
                owner in game.bots &&
                    HistoryProjection.hasAutomatedDecision(
                        game,
                        before.activityStart,
                        setOf(owner),
                        excludeOrderSwaps = true,
                    )
            ) {
                pause(game, phase, owner)
            } else game
        }
        // Setup and phase-opening prompts each belong directly to their responding seat.
        val responder = (before.prompts().singleOrNull() as? Prompt.SinglePlayer)?.player
        return if (
            responder != null &&
                responder in game.bots &&
                HistoryProjection.hasAutomatedDecision(game, before.entryCount, setOf(responder))
        ) {
            pause(game, phase, responder)
        } else game
    }

    fun pause(
        game: StoredGame,
        completedPhase: Stage? = null,
        completedPlayer: PlayerId? = null,
    ): StoredGame {
        val runner = game.botRunner ?: return game
        return game.copy(
            botRunner =
                runner.copy(
                    epoch = nextEpoch(runner),
                    paused = true,
                    pausedAfterPhase = completedPhase,
                    pausedAfterPlayer = completedPlayer,
                    runnerId = null,
                    leaseUntil = 0,
                )
        )
    }

    /** Creator consent authorizes each automated seat's separate audited agreement. */
    fun agreeToAbandonment(game: StoredGame, requestId: UUID): StoredGame =
        game.header.players
            .filter { it.id in game.bots }
            .fold(game) { current, player ->
                val pending = GameAbandonment.open(current)
                if (pending?.requestId != requestId || player.id !in pending.outstanding) current
                else
                    GameAbandonment.apply(
                        current,
                        player.id,
                        AbandonmentEvent(
                            requestId = requestId,
                            operation = AbandonmentOperation.AGREE,
                            operationId = UUID.randomUUID(),
                            baseRevision = current.revision,
                        ),
                    )
            }

    fun control(
        service: GameService,
        id: String,
        access: GameAccess,
        operation: String,
        runnerId: String,
        expectedEpoch: Int?,
        now: Long = System.currentTimeMillis(),
        pauseAfterPhase: Boolean? = null,
    ): BotWork {
        requireUuid(runnerId)
        if ((operation == "set-phase-pause") != (pauseAfterPhase != null)) {
            throw ServiceFailure(400, "INVALID_REQUEST")
        }
        val stored =
            service.store.update(id) { current ->
                requireCreator(service, current, access)
                val runner = requireRunner(current)
                if (expectedEpoch != null && expectedEpoch != runner.epoch) {
                    throw ServiceFailure(409, "BOT_STALE_EPOCH")
                }
                if (operation != "claim" && expectedEpoch == null) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                if (!active(current) && operation !in setOf("pause", "release")) {
                    throw ServiceFailure(422, "GAME_INACTIVE")
                }
                val updated =
                    when (operation) {
                        "pause" -> pause(current)
                        "set-phase-pause" -> {
                            val enabled = pauseAfterPhase == true
                            val updated =
                                current.copy(botRunner = runner.copy(pauseAfterPhase = enabled))
                            if (enabled) updated else resumeReviewPause(updated)
                        }
                        "release" -> {
                            if (runner.runnerId != runnerId)
                                throw ServiceFailure(409, "BOT_RUNNER_BUSY")
                            current.copy(
                                botRunner =
                                    runner.copy(
                                        epoch = nextEpoch(runner),
                                        runnerId = null,
                                        leaseUntil = 0,
                                    )
                            )
                        }
                        "renew" -> {
                            requireLease(current, runnerId, runner.epoch, now)
                            current.copy(botRunner = runner.copy(leaseUntil = now + LEASE_MILLIS))
                        }
                        "claim",
                        "resume" -> {
                            if (
                                operation == "claim" &&
                                    runner.runnerId != null &&
                                    runner.runnerId != runnerId &&
                                    runner.leaseUntil > now
                            ) {
                                throw ServiceFailure(409, "BOT_RUNNER_BUSY")
                            }
                            if (operation == "claim" && runner.paused) current
                            else
                                current.copy(
                                    botRunner =
                                        runner.copy(
                                            epoch =
                                                if (
                                                    operation == "claim" &&
                                                        runner.runnerId == runnerId &&
                                                        runner.leaseUntil > now
                                                ) {
                                                    runner.epoch
                                                } else nextEpoch(runner),
                                            paused = false,
                                            pausedAfterPhase = null,
                                            pausedAfterPlayer = null,
                                            runnerId = runnerId,
                                            leaseUntil = now + LEASE_MILLIS,
                                        )
                                )
                        }
                        else -> throw ServiceFailure(400, "INVALID_REQUEST")
                    }
                if (
                    updated.phaseOutcomesDeferred &&
                        updated.botRunner?.paused == false &&
                        active(updated)
                ) {
                    service.driver.continuePhase(updated)
                } else updated
            }
        return work(stored, now)
    }

    fun read(
        service: GameService,
        id: String,
        access: GameAccess,
        runnerId: String,
        epoch: Int,
        now: Long = System.currentTimeMillis(),
    ): BotWork {
        requireUuid(runnerId)
        val game = service.store.read(id)
        requireCreator(service, game, access)
        val runner = requireRunner(game)
        // A command that caused a phase pause can reconcile a lost response against its receipt.
        val phasePause = runner.pausedAfterPhase != null && epoch == runner.epoch - 1
        if (runner.epoch != epoch && !phasePause) throw ServiceFailure(409, "BOT_STALE_EPOCH")
        if (!runner.paused && active(game)) requireLease(game, runnerId, epoch, now)
        return work(game, now)
    }

    fun submit(
        service: GameService,
        id: String,
        access: GameAccess,
        runnerId: String,
        epoch: Int,
        expectedRevision: Int,
        expectedSubmissionVersion: String?,
        seat: PlayerId,
        decisionId: String,
        commandText: String,
        operationId: String,
        now: Long = System.currentTimeMillis(),
    ): BotWork {
        requireUuid(runnerId)
        requireUuid(operationId)
        if (commandText.length !in 1..200 || '\n' in commandText || '\r' in commandText) {
            throw ServiceFailure(400, "INVALID_REQUEST")
        }
        val requestHash =
            digest(
                listOf(
                        seat.value,
                        decisionId,
                        commandText,
                        expectedRevision,
                        expectedSubmissionVersion,
                    )
                    .joinToString("\u0000")
            )
        val stored =
            service.store.update(id) { current ->
                requireCreator(service, current, access)
                val runner = requireRunner(current)
                val receipt = runner.receipts.firstOrNull { it.operationId == operationId }
                if (receipt != null) {
                    if (receipt.requestHash != requestHash)
                        throw ServiceFailure(409, "OPERATION_ID_REUSED")
                    return@update current
                }
                requireLease(current, runnerId, epoch, now)
                if (!active(current)) throw ServiceFailure(422, "GAME_INACTIVE")
                if (current.revision != expectedRevision)
                    throw ServiceFailure(409, "STALE_REVISION")
                if (seat !in current.bots || seat !in readySeats(current)) {
                    throw ServiceFailure(409, "BOT_DECISION_CHANGED")
                }
                if (decisionId != decisionId(current, seat)) {
                    throw ServiceFailure(409, "BOT_DECISION_CHANGED")
                }
                if (expectedSubmissionVersion != current.submissionVersions[seat]) {
                    throw ServiceFailure(409, "STALE_SUBMISSION")
                }
                val command = TextCodec.parseCommand(commandText)
                if (command.toText() != commandText) throw ServiceFailure(400, "INVALID_COMMAND")
                val next =
                    try {
                        service.driver.submit(current, seat, command)
                    } catch (failure: CardRuleViolation) {
                        throw ServiceFailure(
                            422,
                            failure.diagnostic.code,
                            diagnostic = failure.diagnostic,
                        )
                    }
                val privateWrite = command is Command.BidCapital || command is Command.BuyTokens
                val savedRunner = requireRunner(next)
                val saved =
                    next.copy(
                        submissionVersions =
                            if (privateWrite && next.revision == current.revision) {
                                next.submissionVersions + (seat to UUID.randomUUID().toString())
                            } else next.submissionVersions,
                        botRunner =
                            savedRunner.copy(
                                receipts =
                                    (savedRunner.receipts +
                                            BotOperationReceipt(
                                                operationId,
                                                requestHash,
                                                next.revision,
                                            ))
                                        .takeLast(64)
                            ),
                    )
                if (active(saved)) saved else pause(saved)
            }
        return work(stored, now)
    }

    private fun work(game: StoredGame, now: Long): BotWork {
        val runner = requireRunner(game)
        val paused = runner.paused || !active(game)
        val seats = if (!paused && runner.leaseUntil > now) readySeats(game) else emptyList()
        return BotWork(
            revision = game.revision,
            epoch = runner.epoch,
            leaseUntil = runner.leaseUntil,
            paused = paused,
            observations =
                seats
                    .filter { it in game.bots }
                    .take(1)
                    .map { seat ->
                        val bot =
                            checkNotNull(game.bots[seat]) {
                                "A ready automated seat requires its controller."
                            }
                        val decision = decisionId(game, seat)
                        val state = game.state()
                        val observation = BotObservations.from(state, seat)
                        val checkpoint = bot.policyVersion in hardCheckpointVersions
                        val initiative =
                            if (checkpoint && observation.decision == "buy-tokens") {
                                PhaseInitiativePolicy.project(state.viewForDecision(seat), seat)
                            } else null
                        val finish =
                            if (checkpoint && observation.decision == "purchase-advances") {
                                FinalAdvancePlan.quote(state, state.viewForDecision(seat), seat)
                            } else null
                        BotDecisionWork(
                            seat = seat,
                            difficulty = bot.difficulty,
                            seed = bot.seed xor decision.hashCode(),
                            decisionId = decision,
                            submissionVersion = game.submissionVersions[seat],
                            observation = observation,
                            policyVersion = bot.policyVersion,
                            initiative = initiative,
                            finish = finish,
                        )
                    },
            operationReceipts = runner.receipts.map(BotOperationReceipt::operationId),
        )
    }

    private fun readySeats(game: StoredGame): List<PlayerId> =
        when (val prompt = game.state().prompts().singleOrNull()) {
            is Prompt.SinglePlayer -> listOf(prompt.player)
            is Prompt.MultiPlayer -> prompt.players.filterNot { it in game.pendingBids }
            else -> emptyList()
        }

    private fun decisionId(game: StoredGame, seat: PlayerId): String =
        digest("${game.id}:${game.revision}:$seat:${game.submissionVersions[seat].orEmpty()}")

    private fun requireCreator(service: GameService, game: StoredGame, access: GameAccess) {
        if (service.authenticate(game, access) != game.header.creatorId) {
            throw ServiceFailure(403, "BOT_CONTROL_FORBIDDEN")
        }
    }

    private fun requireRunner(game: StoredGame): BotRunnerState =
        game.botRunner ?: throw ServiceFailure(422, "NO_AUTOMATED_PLAYERS")

    private fun requireLease(game: StoredGame, runnerId: String, epoch: Int, now: Long) {
        val runner = requireRunner(game)
        if (runner.epoch != epoch) throw ServiceFailure(409, "BOT_STALE_EPOCH")
        if (runner.paused) throw ServiceFailure(409, "BOT_PAUSED")
        if (runner.runnerId != runnerId) throw ServiceFailure(409, "BOT_RUNNER_BUSY")
        if (runner.leaseUntil <= now) throw ServiceFailure(409, "BOT_RUNNER_EXPIRED")
    }

    private fun active(game: StoredGame): Boolean =
        game.status == GameStatus.ACTIVE && game.state().stage != Stage.FINISHED

    private fun nextEpoch(runner: BotRunnerState): Int {
        check(runner.epoch < Int.MAX_VALUE) { "Automated-player ownership epoch is exhausted." }
        return runner.epoch + 1
    }

    private fun requireUuid(value: String) {
        if (!GameDocuments.GAME_ID_PATTERN.matches(value))
            throw ServiceFailure(400, "INVALID_REQUEST")
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
