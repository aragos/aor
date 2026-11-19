package dev.aragos.aor.training

import dev.aragos.aor.Command
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
import dev.aragos.aor.ai.SecondCapitalGeography
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
    private val recordCapital: Boolean = false,
) {
    private val controlledBids = mutableMapOf<Int, Int>()
    private val rivalBids = mutableMapOf<Int, Int>()
    private val orderPositions = mutableMapOf<Int, Int>()
    private val seenTurns = mutableSetOf<Int>()
    private var committedCash = 0L
    private var opening: Map<String, Any>? = null
    private var discardedTokensAtPass = 0L
    private var expansionCards = 0
    private var attacks = 0
    private var leaderAttackOpportunities = 0
    private var attacksOnPublicLeader = 0
    private val decisions = MessageDigest.getInstance("SHA-256")
    private val quotedBids = mutableMapOf<Int, Int>()
    private val quoteAdmissions = mutableListOf<Map<String, Any>>()
    private var capital: Map<String, Any>? = null

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
        if (turn == 1 && view.stage == Stage.TOKEN_PURCHASE) {
            val capitalId = requireNotNull(own.capital) { "An opening bidder needs a capital." }
            val capitalPosition = view.capitalOrder.indexOf(player)
            opening =
                mapOf(
                    "bid" to LocalTokenNeighborPolicy.amount(command),
                    "capital" to capitalId,
                    "capitalPosition" to capitalPosition,
                    "cash" to own.cash,
                )
            if (recordCapital) {
                capital =
                    mapOf(
                        "paid" to
                            requireNotNull(view.revealedBids[player]) {
                                "Setup must reveal the paid capital bid."
                            },
                        "capital" to capitalId,
                        "choicePosition" to (capitalPosition + 1),
                        "cashBeforeFirstBid" to own.cash,
                        "rivalPaid" to
                            (1..3).map { offset ->
                                val rival = view.players[(controlledSeat + offset) % 4].id
                                requireNotNull(view.revealedBids[rival]) {
                                    "A completed capital auction must reveal the rival's paid bid."
                                }
                            },
                    )
            }
        }
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
            (opening?.let { mapOf("openingBid" to it) } ?: emptyMap()) +
            (if (recordTokenAdmissions)
                mapOf(
                    "controlledUnofferedTokenBidCounts" to
                        quotedBids.toSortedMap().mapKeys { it.key.toString() },
                    "controlledTokenQuoteAdmissions" to quoteAdmissions.toList(),
                )
            else emptyMap()) +
            (capital?.let { mapOf("capitalAuction" to it) } ?: emptyMap())
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
        val targetingRivals = input.rivalTargetingLineup?.seated(seat).orEmpty()
        val rivalPolicies =
            input.initiativeLineup?.seated(seat)
                ?: input.initiativeRival
                    ?.let { rival ->
                        rival.seats(seat).associateWith { rival.policy }
                    }
                    .orEmpty()
        val style = input.opponentStyle.at(index, seat)
        val references =
            arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
        val diagnostics =
            RobustTokenDiagnostics(
                seat,
                input.tokenNeighborMixture != null,
                recordCapital = input.capitalAuction != null,
            )
        val league =
            input.adaptiveOpponent?.let {
                // Simulation owns the documented p1..p4 registration identifiers.
                val players = (1..4).map { number -> PlayerId("p$number") }
                val prior = input.seriesOpeningPriors.getOrNull(index)
                val response = input.seriesOpeningResponses.getOrNull(index)
                val midgame = input.seriesMidgamePriors.getOrNull(index)
                val learned =
                    if (prior == null && response == null && midgame == null) it
                    else
                        it.copy(
                            capitalOrderOpeningPrior = prior,
                            capitalOrderOpeningResponse = response,
                            seriesMidgamePrior = midgame,
                        )
                RevealedTokenCounterLeague(players[seat], players, learned)
            }
        var finalAdvanceController: FinalAdvanceController? = null
        var phaseBids: PhaseAwareBid? = null
        val controllers = references.mapValues { (player, reference) ->
            val ordinary = if (player == seat) arena.candidate else arena.opponents.first()
            val candidate = player == seat && role == "CANDIDATE"
            val offset = (player - seat + 4) % 4
            val previewPeer = offset in input.previewPeerOffsets
            val chooseGeography = (candidate || previewPeer) && input.secondCapitalGeography
            val capitalBids = input.capitalAuction?.bidsFor(offset).orEmpty()
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
                    if (candidate) input.initiative else rivalPolicies[player],
                    if (candidate) input.leadingRivalRetargeting
                    else input.rivalTargetingLineup?.policy?.takeIf { player in targetingRivals },
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
                        capitalBids.isNotEmpty() && decision == "bid-capital" ||
                            chooseGeography && decision == SecondCapitalGeography.DECISION ||
                            qualified.controls(decision)

                    override fun tokenNeighborMixture(): LocalTokenNeighborMixture? =
                        qualified.tokenNeighborMixture()

                    override fun acceptedTokenQuote(admission: SimulationTokenQuoteAdmission) {
                        check(player == seat && candidate) {
                            "Only the evaluated candidate may admit a new quoted token neighbor."
                        }
                        diagnostics.admitted(admission)
                    }

                    override fun choose(decision: SimulationDecision): String? {
                        if (
                            chooseGeography &&
                                decision.observation.decision == SecondCapitalGeography.DECISION
                        ) {
                            return SecondCapitalGeography.choose(
                                decision.view,
                                decision.observation,
                            )
                                ?: qualified
                                    .takeIf { it.controls(SecondCapitalGeography.DECISION) }
                                    ?.choose(decision)
                        }
                        if (
                            capitalBids.isNotEmpty() &&
                                decision.observation.decision == "bid-capital"
                        ) {
                            val index =
                                Math.floorMod(decision.seed xor 0x6F7B1AC9, capitalBids.size)
                            val command = Command.BidCapital(capitalBids[index]).toText()
                            check(decision.observation.candidates.any { it.command == command }) {
                                "The experimental capital bid must belong to the legal batch offers."
                            }
                            return command
                        }
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
            val regular = league?.wrap(PlayerId("p${player + 1}"), rival, baseline) ?: baseline
            val finishing =
                input.finalAdvanceMargin
                    ?.takeIf { candidate || previewPeer }
                    ?.let { margin ->
                        FinalAdvanceController(regular, margin, input.finalAdvancePassLead).also {
                            if (candidate) finalAdvanceController = it
                        }
                    } ?: regular
            val controller =
                input.phaseBidProfile
                    ?.takeIf { candidate || previewPeer }
                    ?.let { profile ->
                        PhaseAwareBid(finishing, profile).also {
                            if (candidate) phaseBids = it
                        }
                    } ?: finishing
            object : SimulationController by controller {
                override fun choose(decision: SimulationDecision): String? =
                    controller.choose(decision)?.also { diagnostics.chosen(decision, it) }
            }
        }
        require(controllers.keys == setOf(0, 1, 2, 3)) {
            "Every robust league seat must retain a complete reference controller."
        }
        val trace = if (index in input.replayIndices) RobustCanonicalTrace(seat) else null
        val game =
            Simulation()
                .play(
                    arena.configurations.single(),
                    input.seeds[index],
                    List(4) { player ->
                        Competitor(
                            if (player == seat) arena.candidate else arena.opponents.first(),
                            BotDifficulty.HARD,
                        )
                    },
                    deadlineNanos = deadline,
                    controllers = controllers,
                    trace = trace,
                )
        val terminal = ArenaGame.from(game).copy(elapsedMillis = 0)
        CoordinatedEsAttempts.terminal(terminal)
        return RobustTokenGameResult(
            terminal,
            diagnostics.result() +
                (if (rivalPolicies.isEmpty()) emptyMap()
                else mapOf("initiativeRivalSeats" to rivalPolicies.keys.toList())) +
                (if (targetingRivals.isEmpty()) emptyMap()
                else mapOf("retargetingRivalSeats" to targetingRivals.toList())) +
                (finalAdvanceController?.let {
                    mapOf(
                        "finalAdvancePlans" to it.availablePlans,
                        "finalAdvanceOverrides" to it.overrides,
                        "finalAdvanceLastBuyOverrides" to it.lastAdvanceOverrides,
                    )
                } ?: emptyMap()) +
                (phaseBids?.let { mapOf("phaseAwareBidDecisions" to it.evidence()) }
                    ?: emptyMap()) +
                (league?.diagnostics()?.plus("publicCounterResolvedStyle" to style.name)
                    ?: emptyMap()),
            trace?.sha256,
        )
    }
}
