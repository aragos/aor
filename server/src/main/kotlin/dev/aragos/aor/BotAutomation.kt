package dev.aragos.aor

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import java.security.MessageDigest
import java.util.UUID

/** A new automated seat; its display name is fixed when the game is created. */
data class AutomatedPlayerRequest(val name: String, val difficulty: BotDifficulty) {
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
 */
data class BotRunnerState(
    val epoch: Int = 0,
    val paused: Boolean = false,
    val runnerId: String? = null,
    val leaseUntil: Long = 0,
    val receipts: List<BotOperationReceipt> = emptyList(),
) {
    init {
        require(epoch >= 0 && leaseUntil >= 0) {
            "Browser ownership requires a nonnegative epoch and lease deadline."
        }
        require((runnerId == null) == (leaseUntil == 0L)) {
            "A browser lease requires both its runner identity and deadline."
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
)

data class BotDecisionWork(
    val seat: PlayerId,
    val difficulty: BotDifficulty,
    val seed: Int,
    val decisionId: String,
    val submissionVersion: String?,
    val observation: BotObservation,
)

/** Creator-only work; each observation belongs to exactly one automated seat. */
data class BotWork(
    val revision: Int,
    val epoch: Int,
    val leaseUntil: Long,
    val paused: Boolean,
    val observations: List<BotDecisionWork>,
    val operationReceipts: List<String>,
)

/** Client-computed commands pass through the ordinary authority driver under the game lock. */
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
        )
    }

    fun pause(game: StoredGame): StoredGame {
        val runner = game.botRunner ?: return game
        return game.copy(
            botRunner =
                runner.copy(
                    epoch = nextEpoch(runner),
                    paused = true,
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
    ): BotWork {
        requireUuid(runnerId)
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
                when (operation) {
                    "pause" -> pause(current)
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
                                        runnerId = runnerId,
                                        leaseUntil = now + LEASE_MILLIS,
                                    )
                            )
                    }
                    else -> throw ServiceFailure(400, "INVALID_REQUEST")
                }
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
        if (runner.epoch != epoch) throw ServiceFailure(409, "BOT_STALE_EPOCH")
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
                        BotDecisionWork(
                            seat = seat,
                            difficulty = bot.difficulty,
                            seed = bot.seed xor decision.hashCode(),
                            decisionId = decision,
                            submissionVersion = game.submissionVersions[seat],
                            observation = BotObservations.from(game.state(), seat),
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
