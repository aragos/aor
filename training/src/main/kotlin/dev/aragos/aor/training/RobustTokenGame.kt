package dev.aragos.aor.training

import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import java.security.MessageDigest
import kotlin.math.abs

/** Offered neighboring bids around an actor's own qualified choice, without hidden amounts. */
internal object RobustTokenIntervention {
    fun command(
        original: String,
        commands: List<String>,
        style: RobustOpponentStyle,
        turn: Int,
        stock: Int,
    ): String {
        if (style == RobustOpponentStyle.QUALIFIED) return original
        require(style in setOf(RobustOpponentStyle.UNDERCUT, RobustOpponentStyle.OVERBID)) {
            "Only a resolved rival style may change its own qualified token command."
        }
        val amount =
            requireNotNull(original.substringAfter("buy-tokens ").toIntOrNull()) {
                "The qualified token command must contain its signed amount."
            }
        val delta = (1 + turn % 2) * if (style == RobustOpponentStyle.UNDERCUT) -1 else 1
        val target = amount.toLong() + delta
        return commands
            .mapNotNull { command ->
                command.removePrefix("buy-tokens ").toIntOrNull()?.let { command to it }
            }
            .filter { (_, bid) ->
                bid.toLong() in minOf(amount.toLong(), target)..maxOf(amount.toLong(), target) &&
                    (bid <= stock || bid == amount)
            }
            .minByOrNull { (_, bid) -> abs(bid.toLong() - target) }
            ?.first ?: original
    }
}

/** Per-game public/own behavior counters. These never become an action-selection input. */
internal class RobustTokenDiagnostics(
    private val controlledSeat: Int,
    private val recordTokenAdmissions: Boolean = false,
) {
    private val controlledBids = mutableMapOf<Int, Int>()
    private val rivalBids = mutableMapOf<Int, Int>()
    private val orderPositions = mutableMapOf<Int, Int>()
    private val seenTurns = mutableSetOf<Int>()
    private var committedCash = 0L
    private var discardedTokensAtPass = 0L
    private var expansionCards = 0
    private var attacks = 0
    private var leaderAttackOpportunities = 0
    private var attacksOnPublicLeader = 0
    private val decisions = MessageDigest.getInstance("SHA-256")
    private val quotedBids = mutableMapOf<Int, Int>()
    private val quoteAdmissions = mutableListOf<Map<String, Any>>()

    fun admitted(value: SimulationTokenQuoteAdmission) {
        val amount = value.quote.amount
        val owner = value.acceptedView.players.single { it.id.value == value.original.playerId }
        quotedBids[amount] = (quotedBids[amount] ?: 0) + 1
        quoteAdmissions.add(
            mapOf(
                "command" to value.command,
                "amount" to amount,
                "originalOfferedAmounts" to
                    value.original.candidates.map {
                        LocalTokenNeighborPolicy.amount(it.command)
                    },
                "quote" to
                    mapOf(
                        "amount" to amount,
                        "cost" to value.quote.cost,
                        "tokensReceived" to value.quote.tokensReceived,
                        "cashAfter" to value.quote.cashAfter,
                        "stockAfter" to value.quote.stockAfter,
                        "poolAfter" to value.quote.poolAfter,
                    ),
                "ownerResourceBefore" to
                    mapOf(
                        "cash" to owner.cash,
                        "stock" to owner.stockTokens,
                        "pool" to owner.poolTokens,
                    ),
                "originalObservationSha256" to CpePairedDigest.json(value.original),
                "acceptedObservationSha256" to CpePairedDigest.json(value.accepted),
                "acceptedCandidateSha256" to CpePairedDigest.json(value.candidate),
                "acceptedQuotedViewSha256" to CpePairedDigest.json(value.acceptedView),
                "retainedForDecisionTrace" to true,
            )
        )
    }

    fun chosen(decision: SimulationDecision, command: String) {
        decisions.update(
            "${decision.number}:${decision.seat}:$command\n".toByteArray(Charsets.UTF_8)
        )
        if (decision.observation.decision == "buy-tokens") {
            val amount =
                requireNotNull(command.substringAfter("buy-tokens ").toIntOrNull()) {
                    "A recorded token commitment requires its signed amount."
                }
            val counts = if (decision.seat == controlledSeat) controlledBids else rivalBids
            counts[amount] = (counts[amount] ?: 0) + 1
            if (decision.seat == controlledSeat) committedCash += abs(amount.toLong())
        }
        if (decision.seat != controlledSeat) return
        val view = decision.view
        val player = PlayerId(decision.observation.playerId)
        val own = view.players.single { it.id == player }
        val turn = view.turnNumber
        if (
            turn != null &&
                view.stage !in setOf(Stage.TOKEN_PURCHASE, Stage.TURN_ORDER_TIE) &&
                player in view.turnOrder &&
                seenTurns.add(turn)
        ) {
            val position = view.turnOrder.indexOf(player) + 1
            orderPositions[position] = (orderPositions[position] ?: 0) + 1
        }
        if (decision.observation.decision != "expand") return
        if (command == "pass") discardedTokensAtPass += own.poolTokens
        if (command == "buy-expansion-card") expansionCards++
        val maximum = view.scores.mapNotNull { it.total }.maxOrNull() ?: return
        val leaders =
            view.scores
                .filter { it.total == maximum && it.player != player }
                .map { it.player }
                .toSet()
        fun leaderTarget(candidate: String): Boolean {
            if (!candidate.startsWith("attack ")) return false
            val destination = candidate.substringAfter("attack ").substringBefore(' ')
            val province = view.board.singleOrNull { it.id.value == destination } ?: return false
            return province.marker?.owner in leaders || province.tokens.any { it.owner in leaders }
        }
        if (decision.observation.candidates.any { leaderTarget(it.command) })
            leaderAttackOpportunities++
        if (command.startsWith("attack ")) attacks++
        if (leaderTarget(command)) attacksOnPublicLeader++
    }

    fun result(): Map<String, Any> =
        mapOf(
            "controlledBidCounts" to controlledBids.toSortedMap().mapKeys { it.key.toString() },
            "rivalBidCounts" to rivalBids.toSortedMap().mapKeys { it.key.toString() },
            "controlledOrderPositions" to
                orderPositions.toSortedMap().mapKeys { it.key.toString() },
            "controlledCashCommittedToTokens" to committedCash,
            "controlledDiscardedPoolAtPass" to discardedTokensAtPass,
            "controlledExpansionCardsBought" to expansionCards,
            "controlledAttacks" to attacks,
            "controlledLeaderAttackDecisionOpportunities" to leaderAttackOpportunities,
            "controlledAttacksOnCurrentPublicLeader" to attacksOnPublicLeader,
            "chosenPolicyDecisionsSha256" to
                decisions.digest().joinToString("") { "%02x".format(it) },
        ) +
            if (recordTokenAdmissions)
                mapOf(
                    "controlledUnofferedTokenBidCounts" to
                        quotedBids.toSortedMap().mapKeys { it.key.toString() },
                    "controlledTokenQuoteAdmissions" to quoteAdmissions.toList(),
                )
            else emptyMap()
}

/** Only prescribed reproduction roots retain and verify the complete canonical authority text. */
private class RobustCanonicalTrace(override val seat: Int) : SimulationTrace {
    var sha256: String? = null
        private set

    override fun decision(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
    ) = Unit

    override fun verified(header: GameHeader, entries: List<String>) {
        sha256 = CpePairedDigest.json(mapOf("header" to header, "entries" to entries))
    }
}

internal data class RobustTokenGameResult(
    val terminal: ArenaGame,
    val diagnostics: Map<String, Any>,
    val canonicalSha256: String?,
)

/** All seats use the same complete frozen policy; only the evaluated role may change models. */
internal object RobustTokenGame {
    fun play(
        input: RobustTokenInput,
        index: Int,
        role: String,
        deadline: Long,
    ): RobustTokenGameResult {
        require(index in input.indices && role in input.roles) {
            "A robust game requires its original shard index and explicit paired role."
        }
        val arena = input.arena
        val seat = index % 4
        val initiativeRivalSeats = input.initiativeRival?.seats(seat).orEmpty()
        val style = input.opponentStyle.at(index, seat)
        val diagnostics = RobustTokenDiagnostics(seat, input.tokenNeighborMixture != null)
        val league =
            input.adaptiveOpponent?.let {
                // Simulation owns the documented p1..p4 registration identifiers.
                val players = (1..4).map { number -> PlayerId("p$number") }
                RevealedTokenCounterLeague(players[seat], players, it)
            }
        fun controllers(log: RobustTokenDiagnostics): Map<Int, SimulationController> {
            val references =
                arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
            return references.mapValues { (player, reference) ->
                val ordinary = if (player == seat) arena.candidate else arena.opponents.first()
                val candidate = player == seat && role == "CANDIDATE"
                val qualified =
                    CoordinatedEsController(
                        reference,
                        ordinary,
                        input.asset.baseline.model,
                        if (candidate) input.candidate.models else input.control.models,
                        input.asset.original.models,
                        input.asset.calibration.selectors,
                        JointSupportProfile.KNOWN_OWN_CHAOS_1,
                        if (candidate) input.sampler else input.controlSampler,
                        if (candidate) input.expansionDenial else null,
                        if (candidate) input.tokenNeighborMixture else null,
                        if (candidate) input.candidateTokenAnchoredPolicy?.residual else null,
                        when {
                            candidate -> input.initiative
                            player in initiativeRivalSeats ->
                                requireNotNull(input.initiativeRival) {
                                        "Every configured rival has one frozen initiative policy."
                                    }
                                    .policy
                            else -> null
                        },
                        commodityTiming =
                            input.commodityTiming.takeIf { candidate && !input.commodityRollouts },
                    )
                val rival =
                    if (style == RobustOpponentStyle.MIXED) {
                        val position = (player - seat + 4) % 4
                        listOf(
                            RobustOpponentStyle.QUALIFIED,
                            RobustOpponentStyle.UNDERCUT,
                            RobustOpponentStyle.OVERBID,
                        )[(position - 1).coerceAtLeast(0)]
                    } else style
                val baseline =
                    object : SimulationController {
                        override fun controls(decision: String): Boolean =
                            qualified.controls(decision)

                        override fun tokenNeighborMixture(): LocalTokenNeighborMixture? =
                            qualified.tokenNeighborMixture()

                        override fun acceptedTokenQuote(admission: SimulationTokenQuoteAdmission) {
                            check(player == seat && candidate) {
                                "Only the evaluated candidate may admit a new quoted token neighbor."
                            }
                            log.admitted(admission)
                        }

                        override fun choose(decision: SimulationDecision): String? {
                            val original = qualified.choose(decision) ?: return null
                            val command =
                                if (
                                    player != seat &&
                                        !rival.isRevealedCounter &&
                                        decision.observation.decision == "buy-tokens"
                                ) {
                                    RobustTokenIntervention.command(
                                        original,
                                        decision.observation.candidates.map(BotCandidate::command),
                                        rival,
                                        decision.view.turnNumber ?: 0,
                                        decision.view.players
                                            .single { it.id.value == decision.observation.playerId }
                                            .stockTokens,
                                    )
                                } else original
                            return command
                        }
                    }
                val controller =
                    league?.wrap(PlayerId("p${player + 1}"), rival, baseline) ?: baseline
                object : SimulationController by controller {
                    override fun choose(decision: SimulationDecision): String? =
                        controller.choose(decision)?.also { log.chosen(decision, it) }
                }
            }
        }
        val evaluatedControllers = controllers(diagnostics)
        require(evaluatedControllers.keys == setOf(0, 1, 2, 3)) {
            "Every robust league seat must retain a complete reference controller."
        }
        val observer =
            when {
                input.commodityRollouts ->
                    PairedActionRolloutObserver(
                        seat,
                        timing =
                            requireNotNull(input.commodityTiming) {
                                "Commodity continuations need a timing trigger."
                            },
                    )
                input.expansionCardRollouts -> PairedActionRolloutObserver(seat)
                input.stabilizationRollouts ->
                    PairedActionRolloutObserver(
                        seat,
                        stabilizationEpoch =
                            Math.floorMod(input.seeds[index] xor 0x72510B47, 3) + 1,
                    )
                else -> null
            }
        val competitors =
            List(4) { player ->
                Competitor(
                    if (player == seat) arena.candidate else arena.opponents.first(),
                    BotDifficulty.HARD,
                )
            }
        val trace = if (index in input.replayIndices) RobustCanonicalTrace(seat) else null
        val game =
            Simulation()
                .play(
                    arena.configurations.single(),
                    input.seeds[index],
                    competitors,
                    deadlineNanos = deadline,
                    controllers = evaluatedControllers,
                    trace = trace,
                    stateObserver = observer,
                )
        val terminal = ArenaGame.from(game).copy(elapsedMillis = 0)
        CoordinatedEsAttempts.terminal(terminal)
        val rolloutEvidence =
            observer
                ?.let {
                    mapOf(
                        it.evidenceKey to
                            it.evidence(
                                arena.configurations.single().id,
                                input.seeds[index],
                                competitors.map(Competitor::model),
                                { controllers(RobustTokenDiagnostics(seat)) },
                                deadline,
                            )
                    )
                }
                .orEmpty()
        return RobustTokenGameResult(
            terminal,
            diagnostics.result() +
                (if (initiativeRivalSeats.isEmpty()) emptyMap()
                else mapOf("initiativeRivalSeats" to initiativeRivalSeats)) +
                (league?.diagnostics()?.plus("publicCounterResolvedStyle" to style.name)
                    ?: emptyMap()) +
                rolloutEvidence,
            trace?.sha256,
        )
    }
}
