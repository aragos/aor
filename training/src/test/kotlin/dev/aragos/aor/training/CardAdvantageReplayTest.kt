package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.HandCard
import dev.aragos.aor.Prompt
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.CardAdvantagePolicy
import kotlin.test.Test

class CardAdvantageReplayTest {
    @Test
    fun zeroAdvantageCompositionPreservesEveryExistingPurchaseControlledEntry() {
        assertThat(runs.advantage.trace.entries).isEqualTo(runs.prior.trace.entries)
        assertThat(runs.advantage.game.copy(elapsedMillis = 0))
            .isEqualTo(runs.prior.game.copy(elapsedMillis = 0))
    }

    @Test
    fun executorRegeneratesOriginalSealedBatchChoicesAndSeeds() {
        val positions = runs.advantage.trace.positions.associateBy { it.entries }
        var state = GameState(runs.advantage.trace.header)
        val checked = mutableListOf<String>()
        runs.advantage.trace.entries.forEachIndexed { index, entry ->
            val position = positions[index]
            if (position != null) {
                val actors =
                    when (val prompt = state.prompts().single()) {
                        is Prompt.SinglePlayer -> listOf(prompt.player)
                        is Prompt.MultiPlayer -> prompt.players
                        is Prompt.System -> error("A recorded decision must be ready for a player.")
                    }
                val actor = state.playerIds[3]
                val batchStart = position.number - actors.indexOf(actor)
                val executor =
                    CardAdvantageExecutor(runs.input, runs.input.origins.single(), Long.MAX_VALUE)
                val commands = executor.commands(state, batchStart)
                actors.forEachIndexed { offset, id ->
                    val original = runs.advantage.inputs.single { it.number == batchStart + offset }
                    assertThat(commands[id]).isEqualTo(original.command)
                    assertThat(original.decision.seed)
                        .isEqualTo(
                            CardAdvantageExecutor.policySeed(71, batchStart, original.decision.seat)
                        )
                    assertThat(original.decision.view).isEqualTo(state.viewForPlayer(id))
                    assertThat(original.decision.observation)
                        .isEqualTo(BotObservations.from(state, id))
                }
                checked.add(if (actors.size > 1) "sealed" else "ordinary")
            }
            state = state.step(TextCodec.parse(entry))
        }
        assertThat(checked).containsAtLeast("sealed", "ordinary")
    }

    @Test
    fun hiddenOpponentCardsAndDeckDoNotChangeSafeAdvantageInputs() {
        val captured = runs.advantage.trace.positions.first { it.decision == "play-cards" }
        val state =
            runs.advantage.trace.entries.take(captured.entries).fold(
                GameState(runs.advantage.trace.header)
            ) { current, entry ->
                current.step(TextCodec.parse(entry))
            }
        val actor = state.playerIds[3]
        val opponent = state.playerState(state.playerIds[0])
        val changed =
            state.copy(
                deck = state.deck.reversed(),
                players =
                    state.players +
                        (opponent.player.id to
                            opponent.copy(
                                hand =
                                    opponent.hand.map {
                                        HandCard(it.reference, CardData.definition("marco-polo"))
                                    }
                            )),
            )
        val executor =
            CardAdvantageExecutor(runs.input, runs.input.origins.single(), Long.MAX_VALUE)
        val original = executor.decision(state, actor, captured.number)
        val hiddenChanged = executor.decision(changed, actor, captured.number)
        val baseline = CardAdvantageController.recommendation(original, runs.input.environment)
        val first = CardAdvantageController.observation(original, baseline)
        val second = CardAdvantageController.observation(hiddenChanged, baseline)
        val model =
            requireNotNull(runs.input.continuation) { "The fixture has a zero advantage model." }
                .model

        assertThat(hiddenChanged.view).isEqualTo(original.view)
        assertThat(hiddenChanged.observation).isEqualTo(original.observation)
        assertThat(second).isEqualTo(first)
        assertThat(CardAdvantagePolicy.choose(first, model, original.seed))
            .isEqualTo(CardAdvantagePolicy.choose(second, model, hiddenChanged.seed))
    }

    private data class Position(val number: Int, val entries: Int, val decision: String)

    private data class Input(val number: Int, val decision: SimulationDecision, val command: String)

    private class Trace : SimulationTrace {
        override val seat = 3
        val positions = mutableListOf<Position>()
        lateinit var header: GameHeader
        lateinit var entries: List<String>

        override fun decision(
            number: Int,
            snapshotEntryCount: Int,
            commandEntryIndex: Int,
            view: GameView,
            observation: BotObservation,
            chosen: BotCandidate,
            model: BotModel,
        ) {
            positions.add(Position(number, snapshotEntryCount, observation.decision))
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    private data class Run(val game: SimulatedGame, val trace: Trace, val inputs: List<Input>)

    private data class Runs(
        val input: CardAdvantageCollectionInput,
        val prior: Run,
        val advantage: Run,
    )

    companion object {
        private val runs by lazy {
            CardAdvantageFixture(continuation = true).use { fixture ->
                val input = fixture.input
                val environment = input.environment
                fun play(advantage: Boolean): Run {
                    val trace = Trace()
                    val inputs = mutableListOf<Input>()
                    val controllers =
                        (0..3).associateWith {
                            val delegate: SimulationController =
                                if (advantage) {
                                    CardAdvantageController(environment, input.continuation?.model)
                                } else {
                                    CardActorController(
                                        environment.baseline.model,
                                        null,
                                        environment.purchase.model,
                                    )
                                }
                            SimulationController { decision ->
                                val command =
                                    if (delegate.controls(decision.observation.decision)) {
                                        requireNotNull(delegate.choose(decision)) {
                                            "A declared controller must choose."
                                        }
                                    } else
                                        CardAdvantageController.recommendation(
                                            decision,
                                            environment,
                                        )
                                inputs.add(Input(decision.number, decision, command))
                                command
                            }
                        }
                    val game =
                        Simulation()
                            .play(
                                environment.configuration,
                                71,
                                List(4) {
                                    Competitor(environment.baseline.model, BotDifficulty.HARD)
                                },
                                verifyReplay = true,
                                trace = trace,
                                controllers = controllers,
                            )
                    return Run(game, trace, inputs)
                }
                Runs(input, play(false), play(true))
            }
        }
    }
}
