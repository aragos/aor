package dev.aragos.aor.ai

import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerView

/** Infer one-step counters only from prior completed, jointly revealed bids. */
internal object BidResponseForecast {
    private data class HistoryForecast(val previous: List<Int>, val next: Int)

    fun next(
        history: List<Map<PlayerId, Int>>,
        player: PlayerId,
        rivals: List<PlayerView>,
    ): Map<PlayerId, Int> {
        val observed = history.takeLast(8)
        if (observed.size < 4) return emptyMap()
        val comparisons = maxOf(2, observed.size - 4) until observed.size
        val own = observed.map { row ->
            requireNotNull(row[player]) { "A revealed row needs the bidder's own prior bid." }
        }
        val forecasts =
            listOf(
                HistoryForecast(comparisons.map { index -> mode(own.take(index)) }, mode(own)),
                HistoryForecast(comparisons.map { index -> own[index - 1] }, own.last()),
            )
        return buildMap {
            for (rival in rivals) {
                if (rival.misery == MiseryStep.CHAOS) continue
                val responses = comparisons.map { index ->
                    requireNotNull(observed[index][rival.id]) {
                        "A revealed row needs every rival's prior bid."
                    }
                }
                val (predictedBid, hits) =
                    forecasts
                        .flatMap { forecast ->
                            val differences =
                                responses.zip(forecast.previous) { actual, prediction ->
                                    actual.toLong() - prediction
                                }
                            listOf(-1L, 1L).map { offset ->
                                (forecast.next.toLong() + offset) to
                                    differences.count { it == offset }
                            }
                        }
                        .maxBy { (_, count) -> count }
                if (hits * 2 <= responses.size) continue
                val cash = rival.cash.toLong().coerceAtLeast(0L)
                put(rival.id, predictedBid.coerceIn(-cash, cash).toInt())
            }
        }
    }

    private fun mode(amounts: List<Int>): Int {
        val counts = amounts.groupingBy { it }.eachCount()
        val maximum = counts.values.max()
        return amounts.last { counts[it] == maximum }
    }
}
