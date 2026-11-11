package dev.aragos.aor.training

import dev.aragos.aor.DieRoll
import dev.aragos.aor.Entry
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.SystemNeed
import kotlin.random.Random

/**
 * Shared executor/replay sampling. Kotlin bounded integers and shuffle retain unbiased sampling.
 */
internal object ExperimentOutcomes {
    fun from(state: GameState, need: SystemNeed, random: Random, canonicalOrder: Boolean): Entry =
        when (need) {
            is SystemNeed.Shuffle ->
                Entry.Shuffle(
                    (if (canonicalOrder) need.cards.sorted() else need.cards).shuffled(random)
                )
            is SystemNeed.Draw -> GameOutcome.Draw(need.player, need.reference, need.card)
            is SystemNeed.GameRoll ->
                GameOutcome.Roll(
                    need.kind,
                    List(need.diceCount) { DieRoll(random.nextInt(1, 7)) },
                    need.players,
                )
            is SystemNeed.Trade ->
                GameOutcome.Trade(
                    need.owner,
                    need.recipient,
                    state
                        .playerState(need.owner)
                        .hand
                        .map(HandCard::reference)
                        .let { if (canonicalOrder) it.sorted() else it }
                        .random(random),
                )
        }
}
