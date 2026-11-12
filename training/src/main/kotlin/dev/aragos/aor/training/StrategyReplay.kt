package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

enum class StrategyReplaySide(val commandValue: String) {
    CANDIDATE("candidate"),
    INCUMBENT("incumbent");

    internal val arenaSide: ArenaSide
        get() =
            when (this) {
                CANDIDATE -> ArenaSide.CANDIDATE
                INCUMBENT -> ArenaSide.INCUMBENT
            }

    fun model(specification: ArenaSpecification): BotModel =
        when (this) {
            CANDIDATE -> specification.candidate
            INCUMBENT -> specification.incumbent
        }

    companion object {
        fun parse(value: String): StrategyReplaySide =
            requireNotNull(
                entries.singleOrNull {
                    it.commandValue == value
                }
            ) {
                "Replay side must be candidate or incumbent."
            }
    }
}

/**
 * Exact ordinary full-game inspection for one frozen arena slot. [entries] contain canonical
 * authority outcomes, including private draws, solely for offline replay. Decision snapshots and
 * policy inputs contain only the selected seat's ordinary view and hand. Timing includes tracing
 * and replay verification and is not an ordinary simulation benchmark.
 */
data class StrategyReplay(
    val index: Int,
    val configuration: String,
    val seed: Int,
    val seat: Int,
    val side: StrategyReplaySide,
    val opponentIndices: List<Int>,
    val header: GameHeader,
    val entries: List<String>,
    val decisions: List<StrategyReplayDecision>,
    val featureActivity: Map<String, StrategyFeatureActivity>,
    val game: ArenaGame,
    val replayVerified: Boolean,
    val historicalPairVerified: Boolean,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val jointPolicy: Map<String, Any?>? = null,
)

/**
 * A recorded arena pair can preserve a historical lineup after the lineup scheduler changes. It
 * supplies no policy inputs. Its recorded terminal results must match the traced simulation.
 */
class StrategyReplayPair private constructor(private val record: JsonNode) {
    fun identity(): String = record.toString()

    fun opponentIndices(specification: ArenaSpecification, index: Int): List<Int> {
        val lineup = StrategyArena.lineup(specification, index)
        require(integer("index") == index) { "Recorded arena pair index does not match --index." }
        require(record["configuration"]?.asText() == lineup.configuration.id) {
            "Recorded arena pair configuration does not match the specification."
        }
        require(integer("seed") == lineup.seed && integer("seat") == lineup.seat) {
            "Recorded arena pair seed or controlled seat does not match the specification."
        }
        val expectedJoint =
            specification.jointPolicy?.binding()?.let {
                PurchaseJson.mapper.valueToTree<JsonNode>(it)
            }
        require(record["jointPolicy"]?.takeUnless(JsonNode::isNull) == expectedJoint) {
            "Recorded arena pair joint actor roles differ from the replay specification."
        }
        val opponents = record.required("opponentIndices")
        require(opponents.isArray && opponents.size() == lineup.configuration.playerCount) {
            "Recorded arena pair must identify an opponent slot for every seat."
        }
        return opponents.map { opponent ->
            require(
                opponent.isIntegralNumber &&
                    opponent.canConvertToInt() &&
                    opponent.intValue() in specification.opponents.indices
            ) {
                "Recorded arena opponent index is outside the specification's frozen pool."
            }
            opponent.intValue()
        }
    }

    fun verifyTerminal(game: ArenaGame, side: StrategyReplaySide) {
        val expected = record.required(side.commandValue)
        val actual = ObjectMapper().valueToTree<JsonNode>(game)
        for (field in listOf("wins", "rewards", "ranks", "scores", "decisions")) {
            require(expected.required(field) == actual.required(field)) {
                "Strategy replay $field differ from the recorded ${side.commandValue} result."
            }
        }
    }

    private fun integer(name: String): Int {
        val value = record.required(name)
        require(value.isIntegralNumber && value.canConvertToInt()) {
            "Recorded arena pair $name must be an integer."
        }
        return value.intValue()
    }

    companion object {
        fun read(source: Path): StrategyReplayPair {
            val record =
                requireNotNull(ObjectMapper().readTree(source.toFile())) {
                    "A recorded arena pair must contain a JSON object."
                }
            require(record.isObject) { "A recorded arena pair must be a JSON object." }
            return StrategyReplayPair(record)
        }
    }
}

/** Entry offsets preserve the pre-batch snapshot used for simultaneous sealed choices. */
data class StrategyReplayDecision(
    val snapshotEntryCount: Int,
    val commandEntryIndex: Int,
    val snapshot: PilotReplayDecision,
    val chosen: StrategyReplayCandidate,
    val alternatives: List<StrategyReplayCandidate>,
    val candidateCount: Int,
    /** Exact ordinary token choice, including token heads, before any experimental overlay. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val ordinaryTokenBaseline: String? = null,
)

/**
 * [score] is the immediate linear evaluation. HARD may prefer a different first move after its
 * bounded multi-action planning, and an optional frozen purchase actor selects independently of
 * these seat-model scores. The chosen move need not have the largest immediate score.
 */
data class StrategyReplayCandidate(
    val command: String,
    val family: String,
    val score: Double,
    val cashCost: Int,
    val tokenCost: Int,
    val features: Map<String, Double>,
) {
    companion object {
        fun from(candidate: BotCandidate, model: BotModel): StrategyReplayCandidate =
            StrategyReplayCandidate(
                command = candidate.command,
                family = candidate.family,
                score = BotPolicy.score(candidate, model),
                cashCost = candidate.cashCost,
                tokenCost = candidate.tokenCost,
                features = candidate.features.toSortedMap(),
            )
    }
}

/** Nonzero feature counts for the controlled seat, including features with a zero model weight. */
data class StrategyFeatureActivity(
    val availableDecisions: Int = 0,
    val candidateOccurrences: Int = 0,
    val selectedDecisions: Int = 0,
    val selectedValueSum: Double = 0.0,
)

/** The ordinary simulator owns every transition and random outcome, including while tracing. */
object StrategyReplays {
    fun play(
        specification: ArenaSpecification,
        index: Int,
        side: StrategyReplaySide,
        deadlineNanos: Long = Long.MAX_VALUE,
        pair: StrategyReplayPair? = null,
    ): StrategyReplay {
        val lineup = StrategyArena.lineup(specification, index)
        val opponentIndices = pair?.opponentIndices(specification, index) ?: lineup.opponentIndices
        val recording = StrategyRecording(lineup.seat, lineup.seed)
        val game =
            Simulation()
                .play(
                    configuration = lineup.configuration,
                    seed = lineup.seed,
                    competitors =
                        opponentIndices.mapIndexed { player, opponent ->
                            Competitor(
                                model =
                                    if (player == lineup.seat) {
                                        side.model(specification)
                                    } else {
                                        specification.opponents[opponent]
                                    },
                                difficulty = BotDifficulty.HARD,
                            )
                        },
                    deadlineNanos = deadlineNanos,
                    trace = recording,
                    controllers =
                        specification.controllers(
                            lineup.configuration.playerCount,
                            lineup.seat,
                            side.arenaSide,
                            side.model(specification),
                            opponentIndices,
                        ),
                )
        val terminal = ArenaGame.from(game)
        pair?.verifyTerminal(terminal, side)
        return StrategyReplay(
            index = index,
            configuration = lineup.configuration.id,
            seed = lineup.seed,
            seat = lineup.seat,
            side = side,
            opponentIndices = opponentIndices,
            header =
                checkNotNull(recording.header) { "A completed trace requires its replay header." },
            entries =
                checkNotNull(recording.entries) { "A completed trace requires verified entries." },
            decisions = recording.decisions.toList(),
            featureActivity = recording.featureActivity.toSortedMap(),
            game = terminal,
            replayVerified = true,
            historicalPairVerified = pair != null,
            jointPolicy = specification.jointPolicy?.binding(),
        )
    }
}

private class StrategyRecording(override val seat: Int, private val seed: Int) : SimulationTrace {
    var header: GameHeader? = null
        private set

    var entries: List<String>? = null
        private set

    val decisions = mutableListOf<StrategyReplayDecision>()
    val featureActivity = mutableMapOf<String, StrategyFeatureActivity>()

    override fun decision(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
    ) {
        val occurrences =
            buildMap<String, Int> {
                for (candidate in observation.candidates) {
                    for ((name, value) in candidate.features) {
                        if (value != 0.0) put(name, (this[name] ?: 0) + 1)
                    }
                }
            }
        for ((name, count) in occurrences) {
            val before = featureActivity[name] ?: StrategyFeatureActivity()
            val selected = chosen.features[name] ?: 0.0
            featureActivity[name] =
                StrategyFeatureActivity(
                    availableDecisions = before.availableDecisions + 1,
                    candidateOccurrences = before.candidateOccurrences + count,
                    selectedDecisions = before.selectedDecisions + if (selected == 0.0) 0 else 1,
                    selectedValueSum = before.selectedValueSum + selected,
                )
        }
        val alternatives =
            observation.candidates
                .filter { it.command != chosen.command }
                .map { it to BotPolicy.score(it, model) }
                .sortedWith(
                    compareByDescending<Pair<BotCandidate, Double>> { it.second }
                        .thenBy { it.first.command }
                )
                .take(5)
                .map { StrategyReplayCandidate.from(it.first, model) }
        val ordinaryTokenBaseline =
            if (observation.decision == "buy-tokens") {
                val batchStart = number - (commandEntryIndex - snapshotEntryCount)
                BotPolicy.choose(
                    observation,
                    BotDifficulty.HARD,
                    simulationPolicySeed(seed, batchStart, seat),
                    model,
                )
            } else null
        decisions +=
            StrategyReplayDecision(
                snapshotEntryCount = snapshotEntryCount,
                commandEntryIndex = commandEntryIndex,
                snapshot =
                    PilotReplayDecision.from(number, view, observation.decision, chosen.command),
                chosen = StrategyReplayCandidate.from(chosen, model),
                alternatives = alternatives,
                candidateCount = observation.candidates.size,
                ordinaryTokenBaseline = ordinaryTokenBaseline,
            )
    }

    override fun verified(header: GameHeader, entries: List<String>) {
        this.header = header
        this.entries = entries
    }
}

/** A single-threaded, at-most-one-hour diagnostic; it neither selects nor promotes a policy. */
object StrategyReplayMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for strategy replay." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in
                        setOf(
                            "--mode",
                            "--source",
                            "--output",
                            "--index",
                            "--side",
                            "--pair",
                            "--hours",
                            "--workers",
                        )
                }
        ) {
            "Replay options may not repeat and must be mode, source, output, index, side, pair, hours or workers."
        }
        require(fields["--mode"] == "strategy-replay") { "Strategy-replay mode must be explicit." }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) { "Strategy replay requires --source PATH." }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) { "Strategy replay requires --output PATH." }
                )
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) {
            "Replay output must not contain its source specification."
        }
        val index =
            requireNotNull(fields["--index"]?.toIntOrNull()) {
                "Strategy replay requires an integer --index."
            }
        val side =
            StrategyReplaySide.parse(
                requireNotNull(fields["--side"]) {
                    "Strategy replay requires --side candidate|incumbent."
                }
            )
        val pairPath = fields["--pair"]?.let { Path.of(it).toRealPath() }
        require(pairPath == null || !pairPath.startsWith(output)) {
            "Replay output must not contain its recorded pair source."
        }
        val pair = pairPath?.let(StrategyReplayPair::read)
        val hours = fields["--hours"]?.toDouble() ?: 1.0
        require(hours.isFinite() && hours > 0 && hours <= 1.0) {
            "Strategy replay hours must be positive and at most one."
        }
        require((fields["--workers"]?.toInt() ?: 1) == 1) {
            "Strategy replay uses exactly one worker."
        }
        val specification = ArenaSpecification.read(source)
        val lineup = StrategyArena.lineup(specification, index)
        pair?.opponentIndices(specification, index)
        val signature =
            trainingSignature(
                "strategy-replay-1:${specification.identity()}:$index:$side:${pair?.identity()}"
            )
        val artifacts = TrainingArtifacts(output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == "strategy-replay" &&
                    previous["signature"]?.asText() == signature
        ) {
            "Replay code, models or settings changed; use a new output directory."
        }
        if (previous == null) {
            Files.list(output).use { paths ->
                require(
                    paths.allMatch {
                        it.fileName.toString() in
                            setOf("supervisor.json", ".supervisor.lock", "trainer.log")
                    }
                ) {
                    "Replay output already contains artifacts from another run."
                }
            }
        }
        if (specification.experimentalRulesTransfer != null) {
            specification.snapshotModels(artifacts)
            val selected = output.resolve("${side.commandValue}.json")
            artifacts.ensureFileSnapshot(selected, PurchaseJson.hash(selected), "model.json")
        }
        specification.snapshotOverlays(artifacts)
        val identity =
            mapOf("kind" to "strategy-replay", "signature" to signature) +
                specification.overlayMetadata()
        if (artifacts.read("replay.json")?.required("replayVerified")?.booleanValue() == true) {
            artifacts.write("state.json", identity + mapOf("phase" to "complete"))
            if (artifacts.read("finished.json") == null) {
                artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
            }
            println("Strategy replay already complete and verified: $output")
            return
        }
        artifacts.write("state.json", identity + mapOf("phase" to "recording"))
        artifacts.write(
            "run.json",
            identity +
                mapOf(
                    "source" to source.toString(),
                    "index" to index,
                    "configuration" to lineup.configuration.id,
                    "seed" to lineup.seed,
                    "seat" to lineup.seat,
                    "side" to side.commandValue,
                    "recordedPair" to (pairPath?.toString() ?: "none"),
                    "startedAt" to Instant.now().toString(),
                    "workers" to 1,
                    "scoreMeaning" to
                        if (specification.jointPolicy != null) {
                            "Immediate seat-model scores; sampled joint card/purchase actors select independently, with separate token/expansion overlays."
                        } else if (specification.frozenPurchase == null) {
                            "Immediate linear scores; HARD may choose a first move using bounded multi-action planning."
                        } else {
                            "Immediate seat-model linear scores; the frozen purchase actor selects independently, and HARD may use bounded multi-action planning elsewhere."
                        },
                    "scope" to
                        "Post-hoc inspection only; no fitting or promotion. Authority entries are separate from policy inputs.",
                ),
        )
        if (specification.experimentalRulesTransfer == null) {
            artifacts.model(side.model(specification), "model.json")
            specification.opponents.forEachIndexed { opponent, model ->
                artifacts.model(model, "opponent-$opponent.json")
            }
        }
        val deadline = System.nanoTime() + (hours * 3_600_000_000_000).toLong()
        val replay = StrategyReplays.play(specification, index, side, deadline, pair)
        artifacts.write("replay.json", replay)
        artifacts.write("state.json", identity + mapOf("phase" to "complete"))
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println(
            "Strategy replay complete: $index/${side.commandValue}, ${replay.decisions.size} controlled-seat decisions; canonical replay verified."
        )
    }
}
