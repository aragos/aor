package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.abs

/**
 * Small offline-optimized token-action head. Continuation-fitted coefficients estimate terminal
 * composite advantage (0.8 win share plus 0.2 rank reward); coefficients optimized directly through
 * full games define uncalibrated action-advantage scores. Neither is a calibrated win probability.
 * Inference subtracts the incumbent's feature vector, with no intercept, and normally requires an
 * advantage strictly above the 0.02 override margin. A separate experimental gate removes that
 * margin only for increased planned peaceful income without increased uncommitted tokens. Exact
 * ties still retain the incumbent. Collection retains its six proposals; separately gated inference
 * may also compare every offered useful nonnegative amount. That controller change requires its own
 * full-game qualification. The reserved namespace is excluded from ordinary action scores,
 * including when this gate is off. Only HARD token buying may use this head; older models and
 * incomplete observations fall back.
 */
object BotTokenValue {
    const val FEATURE_VERSION = "token-value-2"
    const val GATE = "planning.tokenValue.enabled"

    /**
     * Exactly one adds offered amounts from zero through min(cash, stock) during inference only.
     * This controller setting stays outside the fitted namespace so [withWeights] preserves it.
     */
    const val COMPLETE_AMOUNTS_GATE = "planning.completeTokenAmounts"

    /**
     * Exactly one permits strictly positive advantage for candidates whose joint peaceful plan
     * increases income without increasing uncommitted tokens, relative to the incumbent. Other
     * candidates retain the ordinary margin. Independent of the amount-set gate and preserved by
     * fitted head weights; neither forecast nor coefficient is changed.
     */
    const val PEACEFUL_MARGIN_GATE = "planning.peacefulTokenMargin"
    const val MINIMUM_ADVANTAGE = 0.02
    const val FEATURE_LIMIT = 16.0

    private const val PREFIX = "planning.tokenValue."
    private const val FEATURE_PREFIX = "${PREFIX}v1."
    private val actions = listOf("signedAmount", "cost", "received")
    private val context =
        listOf(
            "cash",
            "income",
            "handCount",
            "markerStock",
            "endgame",
            "peacefulAccess",
            "contestedAccess",
            "rivalCash",
        )

    /**
     * Stable column order: eight action terms, then action-major state interactions (32 v1
     * columns), followed by the seven jointly feasible plan columns owned by
     * [BotTokenPlanProjection]. V1 names and values remain unchanged, so older head weights retain
     * their meaning on new views. Amount, received/pool tokens and commitment use 32-token units;
     * cash cost uses 64 dollars, affordable counts use ten markets, and costFraction divides by
     * max(current cash, 1). State interactions use cash/rival cash per 200 dollars, income per 100,
     * hand per ten cards, marker stock per 25, endgame in [0, 1], and access per twenty
     * current-route markets. Every final term, including interactions, is clipped to [-16, 16]
     * after normalization.
     */
    val featureNames: List<String> =
        (actions +
                listOf(
                    "costFraction",
                    "poolAfter",
                    "affordablePeaceful",
                    "peacefulCommitment",
                    "affordableContested",
                ) +
                actions.flatMap { action -> context.map { state -> "$action.$state" } })
            .map { "$FEATURE_PREFIX$it" } + BotTokenPlanFeature.entries.map { it.key }

    internal fun reserved(name: String): Boolean =
        name.startsWith(PREFIX) || BotTokenCorrection.reserved(name)

    /** A complete feature vector, including explicit zeros, identifies this observation schema. */
    fun available(observation: BotObservation): Boolean =
        observation.decision == "buy-tokens" &&
            observation.candidates.all { candidate ->
                featureNames.all(candidate.features::containsKey)
            }

    /**
     * Fixed at most six legal commands for continuation collection and legacy inference. Typical
     * incumbent 16 with stock 36 yields `16, 0, -4, 8, 24, 36`. Expanded inference retains these
     * commands before adding quantities under [COMPLETE_AMOUNTS_GATE]; it does not change this
     * collector contract or the feature version. Negative purchases pay for earlier turn order
     * without receiving tokens. No pending opponent bid is inspected or predicted.
     */
    fun alternatives(
        observation: BotObservation,
        baseline: BotCandidate,
    ): List<BotCandidate> {
        require(available(observation)) {
            "Token alternatives require the complete $FEATURE_VERSION feature set."
        }
        val amounts = observation.candidates.map { it to amount(it) }
        val positive = amount(baseline).coerceAtLeast(0)
        val maximum = maximumUsefulAmount(observation)
        val targets =
            listOf(
                amount(baseline),
                0,
                -minOf(4, observation.availableCash),
                positive / 2,
                minOf(maximum.toLong(), positive.toLong() + 8).toInt(),
                maximum,
            )
        return targets
            .map { target ->
                amounts
                    .minWith(
                        compareBy<Pair<BotCandidate, Int>> { (_, amount) ->
                                abs(amount.toLong() - target)
                            }
                            .thenBy { it.second }
                            .thenBy { it.first.command }
                    )
                    .first
            }
            .distinctBy(BotCandidate::command)
    }

    fun features(observation: BotObservation, candidate: BotCandidate): List<Double> {
        require(observation.decision == "buy-tokens") {
            "Token value features belong to token-purchase decisions."
        }
        return featureNames.map { name ->
            requireNotNull(candidate.features[name]) {
                "Token value candidate ${candidate.command} is missing feature $name."
            }
        }
    }

    fun difference(
        observation: BotObservation,
        candidate: BotCandidate,
        baseline: BotCandidate,
    ): List<Double> =
        features(observation, candidate).zip(features(observation, baseline)) { after, before ->
            after - before
        }

    /** Preserve the incumbent evaluator and replace only this head's versioned coefficients. */
    fun withWeights(base: BotModel, coefficients: List<Double>): BotModel {
        require(coefficients.size == featureNames.size && coefficients.all(Double::isFinite)) {
            "Token value fitting requires one finite coefficient per $FEATURE_VERSION feature."
        }
        return base.copy(
            weights =
                base.weights.filterKeys { !it.startsWith(PREFIX) } +
                    featureNames.zip(coefficients).toMap() +
                    (GATE to 1.0)
        )
    }

    internal fun choose(
        observation: BotObservation,
        baseline: BotCandidate,
        model: BotModel,
    ): BotCandidate {
        if (model.weights[GATE] != 1.0 || !available(observation)) return baseline
        val baselineFeatures = features(observation, baseline)
        fun advantage(candidate: BotCandidate): Double =
            featureNames.indices.sumOf { index ->
                ((candidate.features[featureNames[index]] ?: 0.0) - baselineFeatures[index]) *
                    (model.weights[featureNames[index]] ?: 0.0)
            }
        val proposals = inferenceAlternatives(observation, baseline, model)
        if (model.weights[PEACEFUL_MARGIN_GATE] != 1.0) {
            val best = proposals.maxBy(::advantage)
            return if (advantage(best) > MINIMUM_ADVANTAGE) best else baseline
        }
        return proposals
            .filter { candidate ->
                val margin =
                    if (improvesPeacefulPlan(candidate, baseline)) 0.0 else MINIMUM_ADVANTAGE
                advantage(candidate) > margin
            }
            .maxByOrNull(::advantage) ?: baseline
    }

    private fun improvesPeacefulPlan(candidate: BotCandidate, baseline: BotCandidate): Boolean {
        val income = BotTokenPlanFeature.INCOME.key
        val uncommitted = BotTokenPlanFeature.UNCOMMITTED_TOKENS.key
        return (candidate.features[income] ?: 0.0) > (baseline.features[income] ?: 0.0) &&
            (candidate.features[uncommitted] ?: 0.0) <= (baseline.features[uncommitted] ?: 0.0)
    }

    private fun inferenceAlternatives(
        observation: BotObservation,
        baseline: BotCandidate,
        model: BotModel,
    ): List<BotCandidate> {
        val original = alternatives(observation, baseline)
        if (model.weights[COMPLETE_AMOUNTS_GATE] != 1.0) return original
        val maximum = maximumUsefulAmount(observation)
        val additional =
            observation.candidates
                .filter { amount(it) in 0..maximum }
                .sortedWith(compareBy<BotCandidate> { amount(it) }.thenBy { it.command })
        // Original ordering preserves baseline and signed-proposal precedence on exact ties.
        return (original + additional).distinctBy(BotCandidate::command)
    }

    private fun maximumUsefulAmount(observation: BotObservation): Int {
        val stock = (observation.context["stock"] ?: 0.0).toInt().coerceAtLeast(0)
        return minOf(observation.availableCash, stock)
    }

    private fun amount(candidate: BotCandidate): Int =
        requireNotNull(
            candidate.command
                .takeIf { it.startsWith("buy-tokens ") }
                ?.substringAfter(' ')
                ?.toIntOrNull()
        ) {
            "A token value candidate requires a canonical signed token-purchase command."
        }
}

/**
 * Public state/action features with fixed normalization. Current-route forecasts ignore future
 * purchases and opponents' moves. Cheapest-first allocation respects pool, marker and overseas
 * budgets. Peaceful and contested allocations are separate alternatives, never one promised bundle;
 * contested counts describe attempts without assuming success or a sealed turn order.
 */
internal class BotTokenValueProjection(
    view: GameView,
    seat: PlayerId,
    endgame: Double,
) {
    private data class Market(val tokens: Int, val overseas: Boolean, val destination: String)

    private data class Allocation(val count: Int, val commitment: Int)

    private val self = view.players.single { it.id == seat }
    private val forecast = view.expansionForecast
    private val peaceful =
        forecast
            ?.peacefulMarkets
            .orEmpty()
            .map {
                Market(it.requiredTokens, it.usesOverseasSlot, it.destination.value)
            }
            .sortedWith(compareBy<Market> { it.tokens }.thenBy { it.destination })
    private val contested =
        forecast
            ?.contestedMarkets
            .orEmpty()
            .map {
                Market(it.requiredTokens, it.usesOverseasSlot, it.destination.value)
            }
            .sortedWith(compareBy<Market> { it.tokens }.thenBy { it.destination })
    private val allocations = mutableMapOf<Int, Pair<Allocation, Allocation>>()
    private val plans by
        lazy(LazyThreadSafetyMode.NONE) {
            BotTokenPlanProjection(view, seat, endgame)
        }
    private val context =
        listOf(
            self.cash / 200.0,
            (self.income?.ordinary ?: 0) / 100.0,
            self.handCount / 10.0,
            self.stockMarkers / 25.0,
            endgame,
            peaceful.size / 20.0,
            contested.size / 20.0,
            view.players.filter { it.id != seat }.map { it.cash.toDouble() }.average() / 200.0,
        )

    fun features(quote: TokenPurchaseOfferView): Map<String, Double> {
        if (forecast == null) return emptyMap()
        val (peacefulAllocation, contestedAllocation) =
            allocations.getOrPut(quote.poolAfter) {
                allocate(peaceful, quote.poolAfter) to allocate(contested, quote.poolAfter)
            }
        val actions = listOf(quote.amount / 32.0, quote.cost / 64.0, quote.tokensReceived / 32.0)
        val values =
            actions +
                listOf(
                    quote.cost.toDouble() / self.cash.coerceAtLeast(1),
                    quote.poolAfter / 32.0,
                    peacefulAllocation.count / 10.0,
                    peacefulAllocation.commitment / 32.0,
                    contestedAllocation.count / 10.0,
                ) +
                actions.flatMap { action -> context.map { action * it } } +
                plans.features(quote)
        return BotTokenValue.featureNames
            .zip(
                values.map {
                    it.coerceIn(-BotTokenValue.FEATURE_LIMIT, BotTokenValue.FEATURE_LIMIT)
                }
            )
            .toMap()
    }

    private fun allocate(markets: List<Market>, pool: Int): Allocation {
        var tokens = pool
        var markers = self.stockMarkers
        var overseas = forecast?.overseasSlots ?: 0
        for (market in markets) {
            if (markers == 0) break
            if (market.tokens > tokens || market.overseas && overseas == 0) continue
            tokens -= market.tokens
            markers--
            if (market.overseas) overseas--
        }
        return Allocation(self.stockMarkers - markers, pool - tokens)
    }
}
