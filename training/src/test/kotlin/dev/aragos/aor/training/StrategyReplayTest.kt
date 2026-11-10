package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategyReplayTest {
    @Test
    fun tracingPreservesTheExactArenaGameAndDecisionCount() {
        val ordinary = StrategyArena.game(specification, 1, specification.candidate, Long.MAX_VALUE)

        assertThat(recorded.game.scores).isEqualTo(ordinary.scores)
        assertThat(recorded.game.wins).isEqualTo(ordinary.wins)
        assertThat(recorded.game.rewards).isEqualTo(ordinary.rewards)
        assertThat(recorded.game.ranks).isEqualTo(ordinary.ranks)
        assertThat(recorded.game.decisions).isEqualTo(ordinary.decisions)
    }

    @Test
    fun canonicalEntriesReproduceTheRecordedTerminalScores() {
        val replayed =
            recorded.entries.fold(GameState(recorded.header)) { state, text ->
                state.step(TextCodec.parse(text))
            }

        assertThat(recorded.replayVerified).isTrue()
        assertThat(replayed.stage).isEqualTo(Stage.FINISHED)
        assertThat(replayed.publicView().scores.map(PilotReplayScore::from))
            .isEqualTo(recorded.game.scores)
    }

    @Test
    fun decisionSnapshotContainsExactlyTheControlledSeatsOrdinaryView() {
        val decision = recorded.decisions.first { it.snapshot.hand.isNotEmpty() }
        val state =
            recorded.entries.take(decision.snapshotEntryCount).fold(GameState(recorded.header)) {
                current,
                text ->
                current.step(TextCodec.parse(text))
            }
        val actor = state.playerIds[recorded.seat]

        val expected =
            PilotReplayDecision.from(
                decision.snapshot.number,
                state.viewForDecision(actor),
                decision.snapshot.decision,
                decision.snapshot.command,
            )

        assertThat(decision.snapshot).isEqualTo(expected)
        assertThat(TextCodec.parse(recorded.entries[decision.commandEntryIndex]))
            .isEqualTo(TextCodec.parseAction(decision.chosen.command, state, actor))
    }

    @Test
    fun sealedDecisionsRetainTheSnapshotFromBeforeOtherChoicesWereCommitted() {
        val decision = recorded.decisions.first { it.snapshot.decision == "buy-tokens" }
        val preceding = recorded.entries[decision.snapshotEntryCount]
        val selected = TextCodec.parse(recorded.entries[decision.commandEntryIndex]) as Entry.Action

        assertThat(decision.commandEntryIndex).isGreaterThan(decision.snapshotEntryCount)
        assertThat((TextCodec.parse(preceding) as Entry.Action).actor).isNotEqualTo(selected.actor)
        assertThat(selected.actor).isEqualTo(recorded.header.players[recorded.seat].id)
    }

    @Test
    fun ordinaryTokenBaselineMatchesTheActualMoveForALaterSeatInTheSealedBatch() {
        val decision = recorded.decisions.first { it.snapshot.decision == "buy-tokens" }

        assertThat(recorded.seat).isGreaterThan(0)
        assertThat(decision.commandEntryIndex).isGreaterThan(decision.snapshotEntryCount)
        assertThat(decision.ordinaryTokenBaseline).isEqualTo(decision.chosen.command)
    }

    @Test
    fun featureCountsTrackSelectedAndAvailableActions() {
        val expectedCounts =
            recorded.decisions
                .flatMap { decision ->
                    decision.chosen.features.filterValues { it != 0.0 }.keys
                }
                .groupingBy { it }
                .eachCount()

        val actualCounts =
            recorded.featureActivity
                .filterValues { it.selectedDecisions > 0 }
                .mapValues { it.value.selectedDecisions }

        assertThat(actualCounts).isEqualTo(expectedCounts)
        assertThat(
                recorded.featureActivity.values.all {
                    it.candidateOccurrences >= it.availableDecisions &&
                        it.availableDecisions >= it.selectedDecisions
                }
            )
            .isTrue()
    }

    @Test
    fun storedScoresAndAlternativesDescribeTheChosenAction() {
        assertThat(
                recorded.decisions.all { decision ->
                    abs(
                        decision.chosen.score -
                            BotPolicy.score(
                                BotCandidate(decision.chosen.command, decision.chosen.features),
                                specification.candidate,
                            )
                    ) < 1.0e-8 &&
                        decision.alternatives.size <= 5 &&
                        decision.alternatives.none {
                            it.command == decision.chosen.command
                        }
                }
            )
            .isTrue()
    }

    @Test
    fun recordedPairPreservesAnOlderLineupAndVerifiesItsTerminalResult() {
        val diversified =
            specification.copy(
                opponents =
                    listOf(
                        BotModels.default,
                        BotModel(weights = BotModels.default.weights + ("cash" to 2.0)),
                    )
            )
        val lineup = StrategyArena.lineup(diversified, 1)
        val oldIndices = listOf(1, 0, 1)
        val terminal =
            ArenaGame.from(
                Simulation()
                    .play(
                        configuration = lineup.configuration,
                        seed = lineup.seed,
                        competitors =
                            oldIndices.mapIndexed { player, opponent ->
                                Competitor(
                                    if (player == lineup.seat) diversified.candidate
                                    else diversified.opponents[opponent],
                                    BotDifficulty.HARD,
                                )
                            },
                    )
            )
        val pair =
            ArenaPair(
                1,
                lineup.configuration.id,
                lineup.seed,
                lineup.seat,
                oldIndices,
                terminal.copy(elapsedMillis = 0),
                terminal.copy(elapsedMillis = 0),
            )

        withPair(pair) { historical ->
            val result =
                StrategyReplays.play(
                    diversified,
                    1,
                    StrategyReplaySide.CANDIDATE,
                    pair = historical,
                )

            assertThat(result.opponentIndices).isEqualTo(oldIndices)
            assertThat(result.opponentIndices).isNotEqualTo(lineup.opponentIndices)
            assertThat(result.game.scores).isEqualTo(terminal.scores)
            assertThat(result.historicalPairVerified).isTrue()
        }
    }

    @Test
    fun recordedPairFromAnotherSeedIsRejectedBeforeSimulation() {
        val pair =
            ArenaPair(
                recorded.index,
                recorded.configuration,
                recorded.seed + 1,
                recorded.seat,
                recorded.opponentIndices,
                recorded.game,
                recorded.game,
            )

        withPair(pair) { historical ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    historical.opponentIndices(specification, recorded.index)
                }

            assertThat(failure).hasMessageThat().contains("seed or controlled seat")
        }
    }

    @Test
    fun recordedTerminalDecisionMismatchCannotBeDeclaredVerified() {
        val pair =
            ArenaPair(
                recorded.index,
                recorded.configuration,
                recorded.seed,
                recorded.seat,
                recorded.opponentIndices,
                recorded.game.copy(decisions = recorded.game.decisions + 1),
                recorded.game,
            )

        withPair(pair) { historical ->
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    historical.verifyTerminal(recorded.game, StrategyReplaySide.CANDIDATE)
                }

            assertThat(failure).hasMessageThat().contains("decisions differ")
        }
    }

    @Test
    fun expiredReplayDeadlineRejectsSimulationBeforeAnyGameWork() {
        assertFailsWith<CancellationException> {
            StrategyReplays.play(
                specification,
                1,
                StrategyReplaySide.INCUMBENT,
                System.nanoTime() - 1,
            )
        }
    }

    @Test
    fun unknownSideCannotSelectAnUnintendedModel() {
        val failure =
            assertFailsWith<IllegalArgumentException> { StrategyReplaySide.parse("other") }

        assertThat(failure).hasMessageThat().contains("candidate or incumbent")
    }

    private fun withPair(pair: ArenaPair, action: (StrategyReplayPair) -> Unit) {
        val path = Files.createTempFile("aor-strategy-replay-pair-", ".json")
        try {
            ObjectMapper().writeValue(path.toFile(), pair)
            action(StrategyReplayPair.read(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    companion object {
        private val specification =
            ArenaSpecification(
                purpose = ArenaPurpose.SCREENING,
                configurations = listOf(TrainingMatrix.stratified.first()),
                candidate = BotModels.default,
                incumbent = BotModels.default,
                opponents = listOf(BotModels.default),
                pairsPerConfiguration = 3,
                seed = 617,
            )
        private val recorded by lazy {
            StrategyReplays.play(specification, 1, StrategyReplaySide.CANDIDATE)
        }
    }
}
