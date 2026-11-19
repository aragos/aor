package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId

/**
 * Estimate the value of moving before each rival from public land proximity. This planning signal
 * considers affordable current markets, our marked provinces, and rivals' coloured pieces or
 * original capitals. An adjacency is evidence of pressure, not a legal route or commitment.
 */
internal object InitiativeGeography {
    /** Adjustment in [-1, 1] per rival, from no adjacent target to at least three. */
    fun orderAdjustments(view: GameView, actor: PlayerId, pool: Int): Map<PlayerId, Double> {
        val forecast = view.expansionForecast ?: return emptyMap()
        val borders = view.catalog?.landBorders ?: return emptyMap()
        val targets = buildSet {
            forecast.peacefulMarkets
                .filter { it.requiredTokens <= pool }
                .forEach {
                    add(it.destination)
                }
            forecast.contestedMarkets
                .filter { it.requiredTokens <= pool }
                .forEach {
                    add(it.destination)
                }
            view.board.filter { it.marker?.owner == actor }.forEach { add(it.id) }
        }
        val rivals = view.players.filter { it.id != actor && it.misery != MiseryStep.CHAOS }
        return rivals.associate { rival ->
            val bases = buildSet {
                rival.capital?.let { add(ProvinceId(it)) }
                for (province in view.board) {
                    val marker = province.marker
                    if (
                        marker?.owner == rival.id && marker.side == PieceSide.COLOURED ||
                            province.tokens.any { it.owner == rival.id && it.coloured > 0 }
                    ) {
                        add(province.id)
                    }
                }
            }
            val threatened = buildSet {
                addAll(targets.intersect(bases))
                for ((first, second) in borders) {
                    if (first in bases && second in targets) add(second)
                    if (second in bases && first in targets) add(first)
                }
            }
            val pressure = threatened.size.coerceAtMost(3) / 3.0
            rival.id to (2.0 * pressure - 1.0)
        }
    }
}
