package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ConflictConservationTest {
    @Test
    fun failedCompetitionCountsPendingCommitmentsExactlyOnceDuringReplay() {
        val opening =
            Scenario.create()
                .play(
                    "p1: start",
                    "p1: bid-capital 3",
                    "p2: bid-capital 2",
                    "p3: bid-capital 1",
                    "p1: choose-capital venice",
                    "p2: choose-capital genoa",
                    "p3: choose-capital barcelona",
                )
        val first = reachExpansion(opening)
        val destinations =
            first.state
                .viewForPlayer(PlayerId("p1"))
                .expansionOffers
                .filter { offer ->
                    offer.primaryAction == ExpansionAction.PLACE &&
                        offer.requiredTokens in 2..3 &&
                        offer.partialPlacements.any { it.tokens == 1 }
                }
                .map { it.destination }
        val occupied =
            destinations.fold(first) { scenario, destination ->
                val offered =
                    scenario.state.viewForPlayer(PlayerId("p1")).expansionOffers.any {
                        it.destination == destination &&
                            it.partialPlacements.any { choice -> choice.tokens == 1 }
                    }
                if (offered) scenario.play("p1: place 1 $destination") else scenario
            }
        val second =
            reachExpansion(occupied.play("p1: pass", "p2: pass", "p3: pass")).play("p1: pass")
        val destination =
            second.state
                .viewForPlayer(PlayerId("p2"))
                .expansionOffers
                .first {
                    it.primaryAction == ExpansionAction.ATTACK && it.blockers.isEmpty()
                }
                .destination
        val action = "p2: attack $destination"
        val committed = Scenario(second.state.step(TextCodec.parse(action)), second.lines + action)

        val failed = committed.play("!roll competition 6 1 1")
        val replayed =
            failed.lines.fold(GameState(failed.state.header)) { state, line ->
                state.step(TextCodec.parse(line)).also(::assertResourceConservation)
            }

        assertThat(committed.state.flow.decisions.last())
            .isInstanceOf(BoardDecision.CompetitionDice::class.java)
        assertThat(failed.state.board.pieces[destination])
            .isEqualTo(second.state.board.pieces[destination])
        assertThat(replayed).isEqualTo(failed.state)
    }

    private fun reachExpansion(initial: Scenario): Scenario {
        var current = initial
        repeat(200) {
            if (current.state.stage == Stage.EXPANSION) return current
            val prompt = current.state.prompts().single()
            val actor =
                when (prompt) {
                    is Prompt.MultiPlayer -> prompt.players.first()
                    is Prompt.SinglePlayer -> prompt.player
                    else ->
                        error(
                            "The fixture must resolve authority outcomes before choosing a command."
                        )
                }
            val commands = current.state.legalChoices(actor).commands
            val command =
                when {
                    current.state.stage == Stage.TOKEN_PURCHASE ->
                        "buy-tokens ${10 + current.state.playerIds.indexOf(actor)}"
                    "upgrade-ships" in commands -> "upgrade-ships"
                    "stabilize cash" in commands -> "stabilize cash"
                    "pass" in commands -> "pass"
                    else -> commands.first()
                }
            current = current.play("$actor: $command")
        }
        error("The fixture must reach expansion within its finite opening.")
    }
}
