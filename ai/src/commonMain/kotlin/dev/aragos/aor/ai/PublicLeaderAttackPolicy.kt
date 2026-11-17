package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec

/**
 * Optional experimental score adjustment. [beta] uses calibrated expansion score units; an eligible
 * legal attack receives [beta] times its authoritative dice-only win probability.
 * [minimumWinProbability] is a probability threshold, not a forecast of eventual game victory.
 */
data class PublicLeaderAttackConfig(
    val beta: Double,
    val minimumWinProbability: Double,
) {
    init {
        require(beta.isFinite() && beta > 0.0 && beta <= 8.0) {
            "The public-leader attack bonus must be finite and in (0,8] score units."
        }
        require(minimumWinProbability.isFinite() && minimumWinProbability in 0.0..1.0) {
            "The attack probability threshold must be finite and in [0,1]."
        }
    }

    companion object {
        const val VERSION = "public-leader-attack-bonus-1"
    }
}

/** Public current scores and board occupancy only; this does not estimate a rival's hidden hand. */
object PublicLeaderAttackPolicy {
    fun choose(
        view: GameView,
        player: PlayerId,
        observation: TerritoryObservation,
        deterministic: IncumbentResidualChoice,
        config: PublicLeaderAttackConfig,
    ): IncumbentResidualChoice {
        require(
            view.stage == Stage.EXPANSION &&
                observation.encoderVersion == TerritoryEncoder.EXPANSION_2.version
        ) {
            "Public-leader attack adjustment requires the acting player's expansion window."
        }
        val own = view.scores.singleOrNull { it.player == player }?.total ?: return deterministic
        val maximum = view.scores.mapNotNull { it.total }.maxOrNull() ?: return deterministic
        if (own >= maximum) return deterministic
        val leaders = view.scores.filter { it.total == maximum }.map { it.player }.toSet()
        val provinces = view.board.associateBy { it.id }
        val offers = view.expansionOffers.associateBy { it.destination }
        val bonuses =
            observation.actions.map { action ->
                val attack =
                    TextCodec.parseCommand(action.command) as? BoardCommand.Attack ?: return@map 0.0
                if (action.command != attack.toText()) return@map 0.0
                val province = provinces[attack.province] ?: return@map 0.0
                val targetsLeader =
                    province.marker?.owner in leaders || province.tokens.any { it.owner in leaders }
                if (!targetsLeader) return@map 0.0
                val offer = offers[attack.province] ?: return@map 0.0
                if (offer.primaryAction != ExpansionAction.ATTACK || offer.blockers.isNotEmpty()) {
                    return@map 0.0
                }
                val probability = offer.competitionWinProbability ?: return@map 0.0
                require(probability.isFinite() && probability in 0.0..1.0) {
                    "An eligible public attack requires its finite authoritative dice probability."
                }
                if (probability >= config.minimumWinProbability) config.beta * probability else 0.0
            }
        if (bonuses.all { it == 0.0 }) return deterministic
        return IncumbentResidualPolicy.selectScores(
            observation,
            deterministic.residualDifferences,
            deterministic.scores.mapIndexed { index, score -> score + bonuses[index] },
        )
    }
}
