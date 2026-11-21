package dev.aragos.aor.ai

import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import kotlin.random.Random

/**
 * The dollar-valued timing signals scale one expected printed turn-order box. The expansion
 * allocation, relative bid limit and public counter forecast remain the ordinary initiative rules.
 */
data class PhaseInitiativeConfig(
    val allocation: InitiativePolicyConfig,
    val earlyPayout: Double,
    val buyingCard: Double,
    val secureMarkets: Double,
    val earlyCompetition: Double,
    val earlyBias: Double = 0.0,
    val lowBidResponse: PersistentLowBidResponse? = null,
) {
    init {
        require(
            listOf(earlyPayout, buyingCard, secureMarkets, earlyCompetition, earlyBias).all {
                it.isFinite() && it in 0.0..2.0
            }
        ) {
            "Phase initiative weights must be finite numbers between zero and two."
        }
    }
}

/**
 * Use [earlyBias] when one active rival underbids the others by [tokenMargin] in two of three
 * rounds.
 */
data class PersistentLowBidResponse(val tokenMargin: Int, val earlyBias: Double) {
    init {
        require(tokenMargin in 1..8) { "The low-bid margin must be one through eight tokens." }
        require(earlyBias in 0.0..2.0) { "The low-bid timing bias must be between zero and two." }
    }

    internal fun appliesTo(input: InitiativeBidInput): Boolean {
        val rounds = input.pastPurchases.takeLast(3)
        if (rounds.size < 3) return false
        val rivals = input.rivals.filterNot(InitiativeRival::inChaos)
        return rivals.any { rival ->
            rounds.count { bids ->
                val amount = bids[rival.id] ?: return@count false
                val others = rivals.filter { it.id != rival.id }.mapNotNull { bids[it.id] }.sorted()
                val reference = others.getOrNull(others.size / 2) ?: return@count false
                amount <= reference - tokenMargin
            } >= 2
        }
    }
}

/** Timing signals are normalized public/own-seat facts, independent of strategy weights. */
data class PhaseInitiativeInput(
    val bids: InitiativeBidInput,
    val earlyPayout: Double,
    val buyingCard: Double,
    val earlyCompetition: Double,
    val secureMarkets: Double,
)

/** Price earlier and later positions from the acting seat's hand and the public board. */
object PhaseInitiativePolicy {
    /** Conservative timing and allocation settings qualified at six-player tables. */
    val conservative =
        PhaseInitiativeConfig(
            allocation =
                InitiativePolicyConfig(
                    maximumTokenLoss = 3,
                    tokenPenalty = .10,
                    participation = 1.0,
                    requireAffordableContest = false,
                    attackBonus = 0.0,
                    historyDecay = .9,
                    bidScoreTolerance = .25,
                    maximumExtraTokens = 2,
                    planWeight = .018,
                    extraCashPenalty = .24,
                    counterAnticipation = .35,
                    respectRivalCash = true,
                ),
            earlyPayout = .55,
            buyingCard = .65,
            secureMarkets = .5,
            earlyCompetition = .45,
        )

    /** Widen the token tradeoff while retaining the conservative estimates and cash penalty. */
    fun response(
        maximumTokenLoss: Int,
        earlyBias: Double,
        lowBidResponse: PersistentLowBidResponse? = null,
    ): PhaseInitiativeConfig =
        conservative.copy(
            allocation = conservative.allocation.copy(maximumTokenLoss = maximumTokenLoss),
            earlyBias = earlyBias,
            lowBidResponse = lowBidResponse,
        )

    /** Vary the sealed capital bid across 0–4 dollars while respecting the current offers. */
    fun varyCapitalBid(observation: BotObservation, ordinary: String, seed: Int): String {
        val amount = Random(seed).nextInt(0, 5)
        return observation.candidates.firstOrNull { it.command == "bid-capital $amount" }?.command
            ?: ordinary
    }

    fun bid(
        view: GameView,
        actor: PlayerId,
        ordinary: String,
        seed: Int,
        config: PhaseInitiativeConfig,
    ): String {
        val input = project(view, actor, config.allocation.planWeight > 0.0) ?: return ordinary
        return bid(input, actor, ordinary, seed, config)
    }

    fun project(
        view: GameView,
        actor: PlayerId,
        includePlans: Boolean = true,
    ): PhaseInitiativeInput? {
        if (view.pastTokenPurchases.size < 2) return null
        val payouts = view.commodityPayouts.associateBy { it.commodity }
        val heldAdvantage =
            view.hand.maxOfOrNull { card ->
                val effect = CardEffectData.forCard(card.definition.id)
                if (effect !is CardEffectSpec.CommodityChoices) return@maxOfOrNull 0
                effect.choices.maxOf { commodity ->
                    val owners = payouts[commodity]?.owners.orEmpty()
                    val own = owners.firstOrNull { it.player == actor }?.payout ?: 0
                    val rival = owners.filter { it.player != actor }.maxOfOrNull { it.payout } ?: 0
                    (own - rival).coerceAtLeast(0)
                }
            } ?: 0
        val cardNeed =
            if (view.deckRemaining > view.players.size) (2 - view.hand.size).coerceAtLeast(0) / 2.0
            else 0.0
        val competition =
            if (view.expansionForecast?.contestedMarkets?.isNotEmpty() == true) 1.0 else 0.0
        val exposed = exposedMarkets(view, actor)
        val exposedPayout =
            payouts.values.maxOfOrNull { payout ->
                val owner =
                    payout.owners.firstOrNull { it.player == actor } ?: return@maxOfOrNull 0.0
                if (owner.provinces.none { it in exposed }) return@maxOfOrNull 0.0
                val box = owner.effectiveBox.coerceAtLeast(1)
                owner.payout.toDouble() * (2 * box - 1) / (box * box)
            } ?: 0.0
        return PhaseInitiativeInput(
            bids = InitiativeBidInput.project(view, actor, includePlans),
            earlyPayout = (heldAdvantage / 40.0).coerceAtMost(1.0),
            buyingCard = cardNeed,
            earlyCompetition = competition,
            secureMarkets = (exposedPayout / 24.0).coerceAtMost(1.0),
        )
    }

    fun bid(
        input: PhaseInitiativeInput,
        actor: PlayerId,
        ordinary: String,
        seed: Int,
        config: PhaseInitiativeConfig,
    ): String {
        val bias =
            config.lowBidResponse?.takeIf { it.appliesTo(input.bids) }?.earlyBias
                ?: config.earlyBias
        val earlier =
            bias +
                config.earlyPayout * input.earlyPayout +
                config.buyingCard * input.buyingCard +
                config.earlyCompetition * input.earlyCompetition
        val later = config.secureMarkets * input.secureMarkets
        return InitiativePolicy.bid(
            input.bids,
            actor,
            ordinary,
            seed,
            config.allocation,
            orderWeight = (earlier - later).coerceIn(-1.5, 1.5),
        )
    }

    private fun exposedMarkets(view: GameView, actor: PlayerId): Set<ProvinceId> {
        val rivals =
            view.players
                .filter {
                    it.id != actor && it.misery != MiseryStep.CHAOS && it.stockTokens > 0
                }
                .map { it.id }
                .toSet()
        if (rivals.isEmpty()) return emptySet()
        val present =
            view.board
                .filter { province ->
                    province.marker?.owner in rivals || province.tokens.any { it.owner in rivals }
                }
                .map { it.id }
                .toSet()
        if (present.isEmpty()) return emptySet()
        return buildSet {
            for (border in view.catalog?.landBorders.orEmpty()) {
                if (border.first in present) add(border.second)
                if (border.second in present) add(border.first)
            }
        }
    }
}
