package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Entry
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test

class ExpansionPairedContinuationTest {
    @Test
    fun reconstructedRootUsesSameAbsoluteSeedAndEChoiceAsUninterruptedPrefix() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val initial = expansionPairedState()
            val actor = initial.playerIds.first()
            val uninterrupted = ExperimentSession(initial, 71L, recordEntries = true)
            val offered =
                uninterrupted
                    .observation(actor)
                    .candidates
                    .first { it.command.startsWith("place ") }
                    .command
            uninterrupted.apply(mapOf(actor to offered))
            val reconstructed = ExperimentSession(uninterrupted.state, 71L, recordEntries = true)
            val seen = mutableListOf<SimulationDecision>()
            fun normal(offset: Int): FrozenPolicyContinuation {
                val actual = input.controllers(0)
                val recording = actual.mapValues { (_, owner) ->
                    object : SimulationController {
                        override fun controls(decision: String) = owner.controls(decision)

                        override fun choose(decision: SimulationDecision): String? {
                            seen += decision
                            return owner.choose(decision)
                        }
                    }
                }
                return FrozenPolicyContinuation(
                    List(4) { input.arena.candidate },
                    recording,
                    901,
                    Long.MAX_VALUE,
                    offset,
                )
            }

            val originalCommands =
                normal(0)
                    .commands(
                        uninterrupted,
                        uninterrupted.actors().associateWith(uninterrupted::observation),
                    )
            val forkCommands =
                normal(uninterrupted.decisions)
                    .commands(
                        reconstructed,
                        reconstructed.actors().associateWith(reconstructed::observation),
                    )

            assertThat(forkCommands).isEqualTo(originalCommands)
            assertThat(seen.map { it.number }.distinct()).containsExactly(uninterrupted.decisions)
            assertThat(seen.map { it.seed }.distinct())
                .containsExactly(simulationPolicySeed(901, uninterrupted.decisions, 0))
        }
    }

    @Test
    fun expansionOverlayReplansTheActualPostInterventionObservation() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val session = ExperimentSession(expansionPairedState(), 71L, recordEntries = true)
            val actor = session.state.playerIds.first()
            val offered =
                session
                    .observation(actor)
                    .candidates
                    .first { it.command.startsWith("place ") }
                    .command
            session.apply(mapOf(actor to offered))
            val observation = session.observation(actor)
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { input.arena.candidate },
                    input.controllers(0),
                    901,
                    Long.MAX_VALUE,
                )
            val expansionModel =
                TrainingArtifacts.readModel(fixture.files.directory.resolve("expansion.json"))

            val commands = continuation.commands(session, mapOf(actor to observation))

            assertThat(commands[actor])
                .isEqualTo(
                    BotPolicy.choose(
                        observation,
                        BotDifficulty.HARD,
                        simulationPolicySeed(901, session.decisions, 0),
                        expansionModel,
                    )
                )
        }
    }

    @Test
    fun aRecordedFirstActionReplaysWithoutCountingAuthorityEntriesAsDecisions() {
        val initial = expansionPairedState()
        val actor = initial.playerIds.first()
        val session = ExperimentSession(initial, 71L, recordEntries = true)
        val offered =
            session.observation(actor).candidates.first { it.command.startsWith("place ") }.command

        session.apply(mapOf(actor to offered))

        assertThat(
                session.entries.fold(initial) { state, entry ->
                    state.step(TextCodec.parse(entry.toText()))
                }
            )
            .isEqualTo(session.state)
        assertThat(session.entries.count { it is Entry.Action }).isEqualTo(session.decisions)
    }
}
