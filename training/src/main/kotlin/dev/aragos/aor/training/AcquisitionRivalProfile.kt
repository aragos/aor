package dev.aragos.aor.training

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Training-only rival exploration. The controlled seat always keeps the original controller. One
 * SHA-256 bit is a fixed half-probability coin keyed by origin seed, zero-based seat, positive turn
 * number and acquisition route. All opportunities in that turn share the coin, including repeated
 * expansion purchases. Chance-tail seeds and hidden state are never keys.
 */
internal enum class AcquisitionRivalProfile {
    TURN_ROUTE_HALF_1;

    fun buys(originSeed: Int, seat: Int, turn: Int, route: AcquisitionRoute): Boolean {
        require(seat in 0..3) { "Acquisition rival seats must be zero-based four-player seats." }
        require(turn > 0) { "An acquisition coin requires a positive turn number." }
        val key = "aor-acquisition-rival-turn-coin-1:$originSeed:$seat:$turn:${route.name}"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        return digest[0].toInt() and 1 == 0
    }

    fun controllers(
        controlledSeat: Int,
        originSeed: Int,
        ordinary: Map<Int, SimulationController>,
    ): Map<Int, SimulationController> {
        require(controlledSeat in 0..3) { "The controlled acquisition seat must be in 0..3." }
        return ordinary +
            (0..3)
                .filter { it != controlledSeat }
                .associateWith { seat ->
                    AcquisitionRivalController(this, originSeed, seat, ordinary[seat])
                }
    }

    fun metadata(): Map<String, Any> =
        mapOf(
            "profile" to name,
            "probability" to 0.5,
            "scope" to "rival seats only; controlled seat retains the exact incumbent",
            "coin" to
                "SHA-256 UTF-8 aor-acquisition-rival-turn-coin-1:originSeed:seat:turn:ROUTE; " +
                    "first-byte low bit zero",
            "reuse" to "same origin/seat/turn/route coin at every opportunity in every tail",
            "fallback" to "exact existing sampled purchase, expansion, token and ordinary roles",
        )
}

/** Stateless wrapper: no flag, sampled policy RNG, future outcome or hidden deck is consulted. */
private class AcquisitionRivalController(
    private val profile: AcquisitionRivalProfile,
    private val originSeed: Int,
    private val seat: Int,
    private val fallback: SimulationController?,
) : SimulationController {
    override fun controls(decision: String): Boolean =
        AcquisitionRoute.entries.any { it.decision == decision } ||
            fallback?.controls(decision) == true

    override fun choose(decision: SimulationDecision): String? {
        require(decision.seat == seat) { "The acquisition wrapper must remain bound to its seat." }
        val route =
            AcquisitionRoute.entries.singleOrNull { it.decision == decision.observation.decision }
        if (
            route != null &&
                decision.observation.candidates.any { it.command == route.buyCommand } &&
                profile.buys(
                    originSeed,
                    seat,
                    requireNotNull(decision.view.turnNumber) {
                        "An offered acquisition requires the current turn."
                    },
                    route,
                )
        ) {
            return route.buyCommand
        }
        return fallback?.takeIf { it.controls(decision.observation.decision) }?.choose(decision)
    }
}
