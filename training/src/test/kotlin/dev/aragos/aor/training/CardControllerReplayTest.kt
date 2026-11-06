package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import kotlin.test.Test

class CardControllerReplayTest {
    @Test
    fun baselineCardCompositionPreservesEveryCanonicalPurchaseGameCommand() {
        assertThat(runs.composed.trace.entries).isEqualTo(runs.purchase.trace.entries)
        assertThat(runs.composed.game.copy(elapsedMillis = 0))
            .isEqualTo(runs.purchase.game.copy(elapsedMillis = 0))
    }

    @Test
    fun cardAndFrozenPurchaseInputsRegenerateFromEveryRecordedPrefix() {
        val run = runs.composed
        val positions = run.trace.positions.associate { it.number to it.entryCount }
        val wanted =
            run.inputs.associateBy {
                requireNotNull(positions[it.number]) {
                    "A controlled input requires its recorded prefix."
                }
            }
        val records = run.cards.associateBy(CardDecisionRecord::number)
        var state = GameState(run.trace.header)
        val seen = mutableListOf<Int>()
        run.trace.entries.forEachIndexed { count, text ->
            val input = wanted[count]
            if (input != null) {
                val actor = PlayerId(input.observation.playerId)
                val view = state.viewForPlayer(actor)
                val observation = BotObservations.from(state, actor)
                val baseline =
                    BotPolicy.choose(observation, BotDifficulty.HARD, input.seed, runs.baseline)
                assertThat(input.view).isEqualTo(view)
                assertThat(input.observation).isEqualTo(observation)
                if (observation.decision == "play-cards") {
                    val encoded = CardObservationEncoder.from(view, actor, observation, baseline)
                    assertThat(encoded)
                        .isEqualTo(
                            requireNotNull(records[input.number]) {
                                    "A card input requires its recorded row."
                                }
                                .observation
                        )
                    assertThat(encoded.actions.map { it.command })
                        .containsExactlyElementsIn(observation.candidates.map { it.command })
                } else {
                    val encoded =
                        PurchaseObservationEncoder.from(view, actor, observation, baseline)
                    val choice =
                        PurchasePolicy.choose(
                            encoded,
                            runs.purchaseModel,
                            PurchasePolicy.actorSeed(input.seed),
                        )
                    assertThat(run.trace.positions.single { it.number == input.number }.command)
                        .isEqualTo(choice.command)
                    assertThat(run.audit.single { it.number == input.number })
                        .isEqualTo(CardPurchaseAudit.from(input, choice.command))
                }
                seen.add(input.number)
            }
            state = state.step(TextCodec.parse(text))
        }
        assertThat(seen).containsExactlyElementsIn(run.inputs.map { it.number }).inOrder()
        assertThat(run.inputs.map { it.observation.decision })
            .containsAtLeast("play-cards", "purchase-shipping", "purchase-advances")
    }

    private data class Position(val number: Int, val entryCount: Int, val command: String)

    private class Trace : SimulationTrace {
        override val seat = 0
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
            positions.add(Position(number, snapshotEntryCount, chosen.command))
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    private data class Run(
        val game: SimulatedGame,
        val trace: Trace,
        val inputs: List<SimulationDecision>,
        val cards: List<CardDecisionRecord>,
        val audit: List<CardPurchaseAudit>,
    )

    private data class Runs(
        val purchase: Run,
        val composed: Run,
        val baseline: BotModel,
        val purchaseModel: dev.aragos.aor.ai.PurchasePolicyModel,
    )

    companion object {
        private val runs by lazy {
            CardCollectionFixture().use { fixture ->
                val input = fixture.input
                val competitors =
                    List(input.configuration.playerCount) {
                        Competitor(input.baseline.model, BotDifficulty.HARD)
                    }
                fun play(cards: Boolean): Run {
                    val trace = Trace()
                    val inputs = mutableListOf<SimulationDecision>()
                    val records = mutableListOf<CardDecisionRecord>()
                    val audit = mutableListOf<CardPurchaseAudit>()
                    val controllers =
                        competitors.indices.associateWith { seat ->
                            val delegate: SimulationController =
                                if (cards) {
                                    CardActorController(
                                        input.baseline.model,
                                        null,
                                        input.frozenPurchasePolicy.model,
                                        audit = if (seat == 0) ({ audit.add(it) }) else null,
                                        record = if (seat == 0) ({ records.add(it) }) else null,
                                    )
                                } else
                                    PurchaseActorController(
                                        input.baseline.model,
                                        input.frozenPurchasePolicy.model,
                                    )
                            object : SimulationController {
                                override fun controls(decision: String) =
                                    delegate.controls(decision)

                                override fun choose(decision: SimulationDecision): String? {
                                    if (seat == 0) inputs.add(decision)
                                    return delegate.choose(decision)
                                }
                            }
                        }
                    val game =
                        Simulation()
                            .play(
                                input.configuration,
                                input.seeds[0],
                                competitors,
                                verifyReplay = true,
                                trace = trace,
                                controllers = controllers,
                            )
                    return Run(game, trace, inputs, records, audit)
                }
                Runs(
                    play(false),
                    play(true),
                    input.baseline.model,
                    input.frozenPurchasePolicy.model,
                )
            }
        }
    }
}
