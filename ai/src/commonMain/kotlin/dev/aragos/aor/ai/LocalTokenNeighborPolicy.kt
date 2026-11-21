package dev.aragos.aor.ai

import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.abs

/** Which actor-owned authority quotes may supplement the unchanged baseline offer list. */
enum class TokenNeighborAdmission {
    OFFERED_ONLY,
    QUOTED_LEGAL_NEIGHBORS,
}

/**
 * An opt-in departure kernel, measured in signed purchase dollars. Baseline inference always uses
 * its original offered actions. A zero [departureProbability] retains the original chooser.
 */
data class LocalTokenNeighborMixture(
    val departureProbability: Double,
    val radius: Int,
    val admission: TokenNeighborAdmission,
) {
    init {
        require(departureProbability.isFinite() && departureProbability in 0.0..0.5) {
            "Token departure probability must be finite and between zero and one half."
        }
        require(radius in 1..2) { "A local token departure radius must be one or two dollars." }
    }

    companion object {
        const val VERSION = "local-token-neighbor-mixture-1"
    }
}

/** A pure capability quoting one signed amount for the current actor and authoritative revision. */
fun interface TokenNeighborQuote {
    fun quote(amount: Int): TokenPurchaseOfferView
}

/**
 * Original offers retain their order, followed by newly quoted signed amounts in ascending order.
 */
data class LocalTokenNeighborDistribution(
    val amounts: List<Int>,
    val probabilities: List<Double>,
    val quotedOffers: List<TokenPurchaseOfferView>,
) {
    fun command(uniform: Double): String {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "A token mixture draw must be finite and in [0,1)."
        }
        val support = probabilities.indices.filter { probabilities[it] > 0.0 }
        var cumulative = 0.0
        val index =
            support.firstOrNull {
                cumulative += probabilities[it]
                uniform < cumulative
            } ?: support.last()
        return "buy-tokens ${amounts[index]}"
    }
}

/** Exact q=(1-epsilon)p+epsilon*pK; no preliminary sampling or additional chance stream exists. */
object LocalTokenNeighborPolicy {
    fun distribution(
        observation: BotObservation,
        baselineProbabilities: List<Double>,
        mixture: LocalTokenNeighborMixture,
        quote: TokenNeighborQuote? = null,
    ): LocalTokenNeighborDistribution {
        require(observation.decision == "buy-tokens") {
            "Only token purchases may use the local departure kernel."
        }
        require(
            baselineProbabilities.size == observation.candidates.size &&
                baselineProbabilities.all { it.isFinite() && it >= 0.0 } &&
                abs(baselineProbabilities.sum() - 1.0) <= 1e-12
        ) {
            "The token mixture requires the complete normalized baseline distribution."
        }
        val amounts = observation.candidates.map { amount(it.command) }
        val offered = amounts.toSet()
        val cash = observation.availableCash.toLong()
        require(amounts.all { it.toLong() in -cash..cash }) {
            "Every baseline token offer must fit the acting player's available cash."
        }
        if (mixture.departureProbability == 0.0) {
            return LocalTokenNeighborDistribution(amounts, baselineProbabilities, emptyList())
        }
        require(mixture.admission == TokenNeighborAdmission.OFFERED_ONLY || quote != null) {
            "Quoted token neighbors require an explicit actor-owned authority capability."
        }
        val quoted = mutableMapOf<Int, TokenPurchaseOfferView>()
        val weights =
            amounts
                .mapIndexed { index, bid ->
                    bid to (1.0 - mixture.departureProbability) * baselineProbabilities[index]
                }
                .toMap()
                .toMutableMap()
        for ((index, bid) in amounts.withIndex()) {
            val mass = mixture.departureProbability * baselineProbabilities[index]
            if (mass == 0.0) continue
            val neighbors =
                ((bid.toLong() - mixture.radius)..(bid.toLong() + mixture.radius))
                    .filter { it != bid.toLong() && it in -cash..cash }
                    .map(Long::toInt)
                    .filter {
                        mixture.admission == TokenNeighborAdmission.QUOTED_LEGAL_NEIGHBORS ||
                            it in offered
                    }
            if (neighbors.isEmpty()) {
                weights[bid] = (weights[bid] ?: 0.0) + mass
                continue
            }
            for (neighbor in neighbors) {
                if (neighbor !in offered && neighbor !in quoted) {
                    val accepted =
                        requireNotNull(quote) {
                                "An unoffered token neighbor requires its authoritative quote."
                            }
                            .quote(neighbor)
                    require(accepted.amount == neighbor) {
                        "The token authority quote must belong to the exact requested amount."
                    }
                    quoted[neighbor] = accepted
                }
                weights[neighbor] = (weights[neighbor] ?: 0.0) + mass / neighbors.size
            }
        }
        val complete = amounts + quoted.keys.sorted()
        val total = weights.values.sum()
        return LocalTokenNeighborDistribution(
            complete,
            complete.map { (weights[it] ?: 0.0) / total },
            quoted.entries.sortedBy { it.key }.map { it.value },
        )
    }

    fun amount(command: String): Int {
        val value = command.removePrefix("buy-tokens ").toIntOrNull()
        require(value != null && command == "buy-tokens $value") {
            "A token departure requires one canonical signed purchase amount."
        }
        return value
    }
}
