package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.RuleViolation
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExperimentSessionTest {
    private val configuration =
        TrainingConfiguration(
            playerCount = 4,
            rules =
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                    map = MapRule.STANDARD,
                ),
        )

    @Test
    fun setupAndSealedBidOutcomesReplayThroughOrdinaryEngine() {
        val session = ExperimentSession.start(configuration, seed = 51L, recordEntries = true)
        val bids = session.actors().associateWith { "bid-capital 0" }

        session.apply(bids)

        val replayed =
            session.entries.fold(GameState(session.state.header)) { state, entry ->
                state.step(TextCodec.parse(entry.toText()))
            }
        assertThat(replayed).isEqualTo(session.state)
        assertThat(session.decisions).isEqualTo(4)
        assertThat(session.entries.any { it is GameOutcome.Roll }).isTrue()
        assertThat(session.systemTransitions).isGreaterThan(0)
    }

    @Test
    fun completeExperimentGameReplaysThroughOrdinaryEngine() {
        val session = ExperimentSession.start(configuration, seed = 51L, recordEntries = true)

        while (session.state.stage != Stage.FINISHED) {
            check(session.decisions < 12000) {
                "The seeded experiment must finish within the decision guard."
            }
            val commands =
                session.actors().associateWith { actor ->
                    BotPolicy.choose(
                        session.observation(actor),
                        BotDifficulty.NORMAL,
                        seed = 51 xor session.decisions,
                    )
                }
            session.apply(commands)
        }

        val replayed =
            session.entries.fold(GameState(session.state.header)) { state, entry ->
                state.step(TextCodec.parse(entry.toText()))
            }
        assertThat(replayed).isEqualTo(session.state)
        assertThat(replayed.publicView().scores).hasSize(4)
    }

    @Test
    fun forkCopiesChancePositionWithoutMutatingItsParent() {
        val session = ExperimentSession.start(configuration, seed = 51L, recordEntries = true)
        val before = session.state
        val first = session.fork()
        val second = session.fork()
        val bids = session.actors().associateWith { "bid-capital 0" }

        first.apply(bids)
        second.apply(bids)

        assertThat(first.state).isEqualTo(second.state)
        assertThat(first.entries).isEqualTo(second.entries)
        assertThat(session.state).isEqualTo(before)
        assertThat(session.decisions).isEqualTo(0)
    }

    @Test
    fun omittedSealedPlayerRejectsWholeBatch() {
        val session = ExperimentSession.start(configuration, seed = 51L)
        val before = session.state
        val bids = session.actors().dropLast(1).associateWith { "bid-capital 0" }

        val failure = assertFailsWith<IllegalArgumentException> { session.apply(bids) }

        assertThat(failure).hasMessageThat().contains("exactly the current deciding players")
        assertThat(session.state).isEqualTo(before)
        assertThat(session.decisions).isEqualTo(0)
    }

    @Test
    fun invalidLastSealedCommandDoesNotPublishEarlierCommands() {
        val session = ExperimentSession.start(configuration, seed = 51L)
        val before = session.state
        val actors = session.actors()
        val bids = actors.associateWith { actor ->
            if (actor == actors.last()) "bid-capital 999" else "bid-capital 0"
        }

        assertFailsWith<RuleViolation> { session.apply(bids) }

        assertThat(session.state).isEqualTo(before)
        assertThat(session.decisions).isEqualTo(0)
    }

    @Test
    fun expiredDeadlineStopsBeforeApplyingActions() {
        val session = ExperimentSession.start(configuration, seed = 51L)
        val before = session.state
        val bids = session.actors().associateWith { "bid-capital 0" }

        assertFailsWith<CancellationException> {
            session.apply(bids, deadlineNanos = System.nanoTime() - 1)
        }

        assertThat(session.state).isEqualTo(before)
        assertThat(session.decisions).isEqualTo(0)
    }

    @Test
    fun allSealedObservationsAreAvailableBeforeBatchReveal() {
        val session = ExperimentSession.start(configuration, seed = 51L)
        val observations = session.actors().map(session::observation)

        val bids = observations.associate { observation ->
            PlayerId(observation.playerId) to
                BotPolicy.choose(observation, BotDifficulty.NORMAL, seed = 51)
        }
        session.apply(bids)

        assertThat(observations.map { it.decision })
            .containsExactly(
                "bid-capital",
                "bid-capital",
                "bid-capital",
                "bid-capital",
            )
        assertThat(session.state.prompts().single()).isInstanceOf(Prompt.SinglePlayer::class.java)
    }
}
