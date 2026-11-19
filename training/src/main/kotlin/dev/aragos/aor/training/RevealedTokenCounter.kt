package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.Command
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.LocalTokenNeighborPolicy

/** First-turn amounts fixed before this game, for earlier and later capital choosers. */
internal data class CapitalOrderOpeningBid(val earlyBid: Int, val lateBid: Int) {
    init {
        require(earlyBid in -40..40 && lateBid in -40..40) {
            "Series opening amounts must fit the initial signed bid range."
        }
    }

    fun amount(target: PlayerId, capitalOrder: List<PlayerId>): Int {
        val position = capitalOrder.indexOf(target)
        require(position >= 0) { "A series response needs the target's public capital order." }
        return if (position < (capitalOrder.size + 1) / 2) earlyBid else lateBid
    }
}

/**
 * Bounded learning from public token purchases. An optional [openingBidPrior] represents a
 * prediction a rival acquired across completed games and fixed before the simulated game starts.
 * [forecastQuantile] selects a lower or upper order statistic of recent bids. Omission uses the
 * observed mode and breaks equal frequencies by recency.
 */
internal data class RevealedTokenCounterConfig(
    val minimumObservations: Int,
    val lookbackTurns: Int,
    val targetedRivalCount: Int,
    val openingBidPrior: Int? = null,
    val forecastQuantile: Double? = null,
    val capitalOrderOpeningPrior: CapitalOrderOpeningBid? = null,
    val capitalOrderOpeningResponse: CapitalOrderOpeningBid? = null,
) {
    init {
        require(lookbackTurns in 1..16 && minimumObservations in 1..lookbackTurns) {
            "Public counter learning requires 1–16 retained turns and a feasible observation minimum."
        }
        require(targetedRivalCount in 1..3) { "A public counter targets one to three rival seats." }
        require(
            forecastQuantile == null || forecastQuantile.isFinite() && forecastQuantile in 0.0..1.0
        ) {
            "A public counter quantile must be between zero and one."
        }
    }

    val identity: Map<String, Any>
        get() =
            mapOf(
                "kind" to VERSION,
                "minimumObservations" to minimumObservations,
                "lookbackTurns" to lookbackTurns,
                "targetedRivalCount" to targetedRivalCount,
            ) +
                (openingBidPrior?.let { mapOf("openingBidPrior" to it) } ?: emptyMap()) +
                (forecastQuantile?.let { mapOf("forecastQuantile" to it) } ?: emptyMap()) +
                (capitalOrderOpeningPrior?.let { mapOf("capitalOrderOpeningPrior" to it) }
                    ?: emptyMap()) +
                (capitalOrderOpeningResponse?.let { mapOf("capitalOrderOpeningResponse" to it) }
                    ?: emptyMap())

    companion object {
        const val VERSION = "public-token-history-counter-1"

        fun read(node: JsonNode): RevealedTokenCounterConfig {
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "minimumObservations",
                    "lookbackTurns",
                    "targetedRivalCount",
                ),
                optional = setOf("openingBidPrior", "forecastQuantile"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "Declare the exact public counter version."
            }
            return RevealedTokenCounterConfig(
                PurchaseJson.integer(node["minimumObservations"], "public observation minimum"),
                PurchaseJson.integer(node["lookbackTurns"], "public lookback turns"),
                PurchaseJson.integer(node["targetedRivalCount"], "targeted rival count"),
                node["openingBidPrior"]?.let { PurchaseJson.integer(it, "opening bid prior") },
                node["forecastQuantile"]?.let {
                    PurchaseJson.number(it, "public bid forecast quantile")
                },
            )
        }
    }
}

/**
 * Shared arena/collector adapter. Construct once per game or independent tail, feed any simulated
 * prefix through [observe] chronologically, and wrap every seat. Only the next configured rival
 * seats counter the target; all others retain their complete baseline policy. No hidden prefix
 * records or chosen current commands are accepted by this history owner.
 */
internal class RevealedTokenCounterLeague
private constructor(
    private val target: PlayerId,
    private val registeredPlayers: List<PlayerId>,
    private val config: RevealedTokenCounterConfig,
    private val counter: RevealedTokenCounter,
    private val counterTurns: MutableMap<PlayerId, Int>,
) {
    constructor(
        target: PlayerId,
        registeredPlayers: List<PlayerId>,
        config: RevealedTokenCounterConfig,
    ) : this(
        target,
        registeredPlayers.toList(),
        config,
        RevealedTokenCounter(target, config),
        mutableMapOf(),
    )

    init {
        require(
            registeredPlayers.distinct().size == registeredPlayers.size &&
                target in registeredPlayers &&
                config.targetedRivalCount < registeredPlayers.size
        ) {
            "A public counter league requires a registered target and enough distinct rival seats."
        }
    }

    fun observe(view: GameView) {
        counter.observe(PublicTokenReveal.from(view, target))
        if (view.stage != Stage.TOKEN_PURCHASE) {
            registeredPlayers.filter(::isTargeted).forEach { actor -> counter.order(view, actor) }
        }
    }

    /** Independent continuation after a simulated prefix; no mutable learning state is shared. */
    fun fork(): RevealedTokenCounterLeague =
        RevealedTokenCounterLeague(
            target,
            registeredPlayers,
            config,
            counter.fork(),
            counterTurns.toMutableMap(),
        )

    fun isTargeted(actor: PlayerId): Boolean {
        val index = registeredPlayers.indexOf(actor)
        require(index >= 0) { "A public counter wrapper requires a registered seat." }
        val relative =
            (index - registeredPlayers.indexOf(target) + registeredPlayers.size) %
                registeredPlayers.size
        return relative in 1..config.targetedRivalCount
    }

    fun wrap(
        actor: PlayerId,
        style: RobustOpponentStyle,
        base: SimulationController,
    ): SimulationController {
        require(
            (style.isRevealedCounter &&
                style != RobustOpponentStyle.REVEALED_BALANCED &&
                style != RobustOpponentStyle.QUALIFIED_REVEALED_BALANCED) ||
                style == RobustOpponentStyle.QUALIFIED
        ) {
            "The public-history adapter accepts only its explicit counter or qualified style."
        }
        val active = isTargeted(actor) && style.isRevealedCounter
        return object : SimulationController by base {
            override fun controls(decision: String): Boolean =
                base.controls(decision) ||
                    (active && style.isTieAware && decision == "choose-turn-position")

            override fun choose(decision: SimulationDecision): String? {
                require(decision.observation.playerId == actor.value) {
                    "A public counter wrapper must retain its original deciding seat."
                }
                observe(decision.view)
                val original =
                    if (base.controls(decision.observation.decision)) {
                        base.choose(decision)
                    } else null
                if (!active) return original
                val turn = decision.view.turnNumber ?: return original
                val offered = decision.observation.candidates.map { it.command }
                if (decision.observation.decision == "choose-turn-position") {
                    return if (counterTurns[actor] == turn) {
                        counter.tie(
                            original,
                            offered,
                            turn,
                            actor,
                            decision.view.provisionalTieOrder,
                            style,
                        )
                    } else original
                }
                if (original == null) return null
                val command =
                    when (decision.observation.decision) {
                        "buy-tokens" -> {
                            val result =
                                counter.bid(
                                    original,
                                    offered,
                                    turn,
                                    actor,
                                    decision.view.capitalOrder,
                                    style,
                                )
                            if (result.active) counterTurns[actor] = turn
                            else counterTurns.remove(actor)
                            result.command
                        }
                        else -> original
                    }
                if (decision.observation.decision == "expand") {
                    val pool = decision.view.players.single { it.id == actor }.poolTokens
                    counter.expansion(actor, command, pool)
                }
                return command
            }
        }
    }

    fun diagnostics(): Map<String, Any> =
        counter.diagnostics() +
            mapOf(
                "publicCounterRivalSelection" to "next-registered-seats-cyclic-1",
                "publicCounterTargetedRivals" to
                    registeredPlayers.filter(::isTargeted).map(PlayerId::value),
            )
}

/** The entire predictor input: one signed purchase that the engine has publicly revealed. */
internal data class PublicTokenReveal(val turn: Int, val amount: Int) {
    init {
        require(turn > 0 && amount != Int.MIN_VALUE) {
            "A public token reveal requires its positive turn and representable signed cost."
        }
    }

    val record: Map<String, Int>
        get() = mapOf("turn" to turn, "amount" to amount)

    companion object {
        fun from(view: GameView, target: PlayerId): PublicTokenReveal? {
            // Even a malformed commitment-window view cannot teach the counter a current bid.
            if (view.stage != Stage.TURN_ORDER_TIE && !view.stage.isTurnPhase) return null
            val turn = view.turnNumber ?: return null
            val amount = view.revealedTokenPurchases[target] ?: return null
            return PublicTokenReveal(turn, amount)
        }
    }
}

internal enum class CounterBidReason {
    WARMUP,
    STRICT_NEIGHBOR,
    CAPITAL_PRIORITY_TIE,
    LEARNED_OPENING_RESPONSE,
    TARGET_NOT_OFFERED,
}

internal data class RevealedCounterBid(val command: String, val reason: CounterBidReason) {
    val active: Boolean
        get() =
            reason == CounterBidReason.STRICT_NEIGHBOR ||
                reason == CounterBidReason.CAPITAL_PRIORITY_TIE ||
                reason == CounterBidReason.LEARNED_OPENING_RESPONSE
}

/**
 * One game owns this state. No command, private hand, hidden outcome or current commitment enters
 * its predictor. Modal ties prefer the most recent public observation. Proof retention is bounded;
 * the digest covers every reveal and counter decision, including rows beyond the retained prefix.
 */
internal class RevealedTokenCounter(
    private val target: PlayerId,
    private val config: RevealedTokenCounterConfig,
) {
    private val recent = ArrayDeque<PublicTokenReveal>()
    private var latest: PublicTokenReveal? = null
    private var reveals = 0
    private var bidDecisions = 0
    private var changedBids = 0
    private var tieDecisions = 0
    private var warmups = 0
    private var unavailable = 0
    private var orderOutcomes = 0
    private var earlierOutcomes = 0
    private var laterOutcomes = 0
    private var predictionHits = 0
    private var predictionsResolved = 0
    private var digest = CpePairedDigest.json(emptyList<String>())
    private val proofs = mutableListOf<Map<String, Any?>>()

    private data class PendingBid(val turn: Int, val prediction: Int?, val chosen: Int)

    private data class ExpansionActivity(
        val decisions: Int = 0,
        val attacks: Int = 0,
        val cards: Int = 0,
        val discardedPool: Long = 0,
    )

    private val pending = mutableMapOf<PlayerId, PendingBid>()
    private val resolvedTurns = mutableMapOf<PlayerId, Int>()
    private val activity = mutableMapOf<PlayerId, ExpansionActivity>()

    fun fork(): RevealedTokenCounter =
        RevealedTokenCounter(target, config).also { copy ->
            copy.recent.addAll(recent)
            copy.latest = latest
            copy.reveals = reveals
            copy.bidDecisions = bidDecisions
            copy.changedBids = changedBids
            copy.tieDecisions = tieDecisions
            copy.warmups = warmups
            copy.unavailable = unavailable
            copy.orderOutcomes = orderOutcomes
            copy.earlierOutcomes = earlierOutcomes
            copy.laterOutcomes = laterOutcomes
            copy.predictionHits = predictionHits
            copy.predictionsResolved = predictionsResolved
            copy.digest = digest
            copy.proofs.addAll(proofs)
            copy.pending.putAll(pending)
            copy.resolvedTurns.putAll(resolvedTurns)
            copy.activity.putAll(activity)
        }

    fun observe(reveal: PublicTokenReveal?) {
        if (reveal == null) return
        val previous = latest
        require(previous == null || reveal.turn >= previous.turn) {
            "Public token history must advance within its own game."
        }
        if (previous?.turn == reveal.turn) {
            require(previous == reveal) { "An already revealed target bid cannot change." }
            return
        }
        recent.addLast(reveal)
        if (recent.size > config.lookbackTurns) recent.removeFirst()
        latest = reveal
        reveals++
        digest = CpePairedDigest.json(listOf(digest, reveal.record))
    }

    fun bid(
        original: String,
        offered: List<String>,
        turn: Int,
        actor: PlayerId,
        capitalOrder: List<PlayerId>,
        style: RobustOpponentStyle,
    ): RevealedCounterBid {
        require(style.isRevealedCounter && actor != target && turn > 0) {
            "Only a distinct rival in an explicit public counter style may counter a token bid."
        }
        val history = recent.filter { it.turn < turn }
        val prediction =
            if (history.size < config.minimumObservations) {
                config.capitalOrderOpeningPrior?.amount(target, capitalOrder)
                    ?: config.openingBidPrior
            } else if (config.forecastQuantile != null) {
                val index = (config.forecastQuantile * (history.size - 1)).toInt()
                history.map(PublicTokenReveal::amount).sorted()[index]
            } else {
                val counts = history.groupingBy(PublicTokenReveal::amount).eachCount()
                val maximum = counts.values.max()
                history.last { counts[it.amount] == maximum }.amount
            }
        val learned =
            config.capitalOrderOpeningResponse
                ?.takeIf { turn == 1 }
                ?.let { response ->
                    Command.BuyTokens(response.amount(target, capitalOrder)).toText()
                }
        val result =
            if (learned != null) {
                if (learned in offered) {
                    RevealedCounterBid(learned, CounterBidReason.LEARNED_OPENING_RESPONSE)
                } else {
                    unavailable++
                    RevealedCounterBid(original, CounterBidReason.TARGET_NOT_OFFERED)
                }
            } else if (prediction == null) {
                warmups++
                RevealedCounterBid(original, CounterBidReason.WARMUP)
            } else {
                val actorPriority = capitalOrder.indexOf(actor)
                val targetPriority = capitalOrder.indexOf(target)
                require(actorPriority >= 0 && targetPriority >= 0) {
                    "A public counter requires the established capital-choice priority."
                }
                val canChooseTie = style.isTieAware && actorPriority > targetPriority
                val neighbor = prediction.toLong() + if (style.countersEarlier) -1 else 1
                val amounts =
                    if (canChooseTie) {
                        listOf(prediction.toLong(), neighbor)
                    } else {
                        listOf(neighbor)
                    }
                val command =
                    amounts
                        .asSequence()
                        .filter { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                        .map { Command.BuyTokens(it.toInt()).toText() }
                        .firstOrNull { it in offered }
                if (command != null) {
                    RevealedCounterBid(
                        command,
                        if (canChooseTie && command == Command.BuyTokens(prediction).toText()) {
                            CounterBidReason.CAPITAL_PRIORITY_TIE
                        } else {
                            CounterBidReason.STRICT_NEIGHBOR
                        },
                    )
                } else {
                    unavailable++
                    RevealedCounterBid(original, CounterBidReason.TARGET_NOT_OFFERED)
                }
            }
        bidDecisions++
        if (result.command != original) changedBids++
        pending[actor] =
            PendingBid(turn, prediction, LocalTokenNeighborPolicy.amount(result.command))
        record(
            mapOf(
                "phase" to "buy-tokens",
                "turn" to turn,
                "actor" to actor.value,
                "target" to target.value,
                "style" to style.name,
                "history" to history.map(PublicTokenReveal::record),
                "prediction" to prediction,
                "originalCommand" to original,
                "command" to result.command,
                "reason" to result.reason.name,
                "capitalOrder" to capitalOrder.map(PlayerId::value),
                "latestEvidenceTurn" to history.lastOrNull()?.turn,
                "strictlyPastPublicEvidence" to history.all { it.turn < turn },
            )
        )
        return result
    }

    /**
     * Join predictions only after the engine publicly exposes bids and the resolved order boxes.
     */
    fun order(view: GameView, actor: PlayerId) {
        if (view.stage == Stage.TOKEN_PURCHASE) return
        val turn = view.turnNumber ?: return
        val attempt = pending[actor] ?: return
        if (attempt.turn != turn || resolvedTurns[actor] == turn) return
        val targetBid = view.revealedTokenPurchases[target] ?: return
        val actorBid = view.revealedTokenPurchases[actor] ?: return
        val targetBox = view.orderBoxes[target] ?: return
        val actorBox = view.orderBoxes[actor] ?: return
        require(actorBid == attempt.chosen) {
            "A public counter outcome must retain its own actually committed command."
        }
        resolvedTurns[actor] = turn
        orderOutcomes++
        if (actorBox < targetBox) earlierOutcomes++
        if (actorBox > targetBox) laterOutcomes++
        if (attempt.prediction != null) {
            predictionsResolved++
            if (attempt.prediction == targetBid) predictionHits++
        }
        record(
            mapOf(
                "phase" to "order-revealed",
                "turn" to turn,
                "actor" to actor.value,
                "target" to target.value,
                "prediction" to attempt.prediction,
                "actualTargetBid" to targetBid,
                "actualRivalBid" to actorBid,
                "targetOrderBox" to targetBox,
                "rivalOrderBox" to actorBox,
                "rivalBeforeTarget" to (actorBox < targetBox),
            )
        )
    }

    fun expansion(actor: PlayerId, command: String, pool: Int) {
        val before = activity[actor] ?: ExpansionActivity()
        activity[actor] =
            before.copy(
                decisions = before.decisions + 1,
                attacks = before.attacks + if (command.startsWith("attack ")) 1 else 0,
                cards = before.cards + if (command == "buy-expansion-card") 1 else 0,
                discardedPool = before.discardedPool + if (command == "pass") pool else 0,
            )
    }

    fun tie(
        original: String?,
        offered: List<String>,
        turn: Int,
        actor: PlayerId,
        placed: List<PlayerId>,
        style: RobustOpponentStyle,
    ): String? {
        if (!style.isTieAware || target !in placed) return original
        val targetIndex = placed.indexOf(target)
        val slot = targetIndex + if (style.countersEarlier) 1 else 2
        val command = Command.ChooseTurnPosition(slot).toText()
        if (command !in offered) return original
        tieDecisions++
        record(
            mapOf(
                "phase" to "choose-turn-position",
                "turn" to turn,
                "actor" to actor.value,
                "target" to target.value,
                "style" to style.name,
                "command" to command,
                "publicPlacedOrder" to placed.map(PlayerId::value),
            )
        )
        return command
    }

    private fun record(value: Map<String, Any?>) {
        digest = CpePairedDigest.json(listOf(digest, value))
        if (proofs.size < PROOF_LIMIT) proofs.add(value)
    }

    fun diagnostics(): Map<String, Any> =
        mapOf(
            "publicTokenCounter" to
                mapOf(
                    "config" to config.identity,
                    "target" to target.value,
                    "reveals" to reveals,
                    "bidDecisions" to bidDecisions,
                    "changedBids" to changedBids,
                    "warmups" to warmups,
                    "unavailableTargets" to unavailable,
                    "tieDecisions" to tieDecisions,
                    "resolvedOrderOutcomes" to orderOutcomes,
                    "rivalBeforeTargetCount" to earlierOutcomes,
                    "rivalAfterTargetCount" to laterOutcomes,
                    "resolvedPredictions" to predictionsResolved,
                    "predictionHits" to predictionHits,
                    "rivalExpansionActivity" to
                        activity
                            .mapKeys { it.key.value }
                            .mapValues { (_, row) ->
                                mapOf(
                                    "decisions" to row.decisions,
                                    "attacks" to row.attacks,
                                    "expansionCards" to row.cards,
                                    "discardedPoolAtPass" to row.discardedPool,
                                )
                            },
                    "latestWindow" to recent.map(PublicTokenReveal::record),
                    "decisionProofs" to proofs.toList(),
                    "proofLimit" to PROOF_LIMIT,
                    "allEvidenceSha256" to digest,
                )
        )

    companion object {
        const val PROOF_LIMIT = 32
    }
}
