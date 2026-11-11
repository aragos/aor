package dev.aragos.aor.training

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Absolute replacement bounds for one sampled planning.* weight. [linkedWeights] maps additional
 * planning weight names to nonzero multipliers of that sample; links add no sampling dimensions.
 * The owning policy defines whether a weight contributes to ordinary scores or a separate head.
 * Zero disables the primary and all linked weights. Every resulting weight stays within the model
 * bounds, including when a multiplier is negative.
 */
data class StrategySearchParameter(
    val name: String,
    val min: Double,
    val max: Double,
    val linkedWeights: Map<String, Double> = emptyMap(),
) {
    init {
        require(name.startsWith("planning.") && name.length in 10..120) {
            "Search parameters must name planning.* weights."
        }
        require(min.isFinite() && max.isFinite() && min < max && min >= -1.0e6 && max <= 1.0e6) {
            "Search bounds must be finite, ordered, and within model weight limits."
        }
        require(min <= 0.0 && max >= 0.0) { "Search bounds must include the zero-weight baseline." }
        require(
            linkedWeights.keys.all { target ->
                target != name && target.startsWith("planning.") && target.length in 10..120
            }
        ) {
            "Linked weights must name other planning.* weights."
        }
        require(linkedWeights.values.all { it.isFinite() && it != 0.0 }) {
            "Linked weight multipliers must be finite and nonzero."
        }
        require(
            linkedWeights.values.all { multiplier ->
                listOf(min * multiplier, max * multiplier).all {
                    it.isFinite() && it in -1.0e6..1.0e6
                }
            }
        ) {
            "Linked weight bounds must remain within model weight limits."
        }
    }

    /**
     * Expand one sampled dimension; canonical key order also stabilizes recorded candidate values.
     */
    internal fun weights(value: Double): Map<String, Double> = buildMap {
        put(name, value)
        for ((target, multiplier) in linkedWeights.toSortedMap()) {
            put(target, if (value == 0.0) 0.0 else value * multiplier)
        }
    }

    companion object {
        internal fun read(node: JsonNode): StrategySearchParameter {
            require(
                node.isObject &&
                    node["name"]?.isTextual == true &&
                    node["min"]?.isNumber == true &&
                    node["max"]?.isNumber == true
            ) {
                "Each search parameter requires an object with a name and numerical min/max bounds."
            }
            val links = node["linkedWeights"]
            require(links == null || links.isObject) {
                "Search linkedWeights must be an explicit object of weight names and multipliers."
            }
            val multipliers =
                links
                    ?.fields()
                    ?.asSequence()
                    ?.associate { (target, multiplier) ->
                        require(multiplier.isNumber) {
                            "Each linked weight multiplier must be numerical."
                        }
                        target to multiplier.doubleValue()
                    }
                    .orEmpty()
            return StrategySearchParameter(
                node["name"].asText(),
                node["min"].doubleValue(),
                node["max"].doubleValue(),
                multipliers,
            )
        }
    }
}

/** Two selection banks, both disjoint from the later, independently run confirmation arena. */
enum class StrategySearchStage {
    SCREENING,
    SELECTION,
}

/**
 * Search either sampled [parameters] or an ordered [explicitCandidates] pool. An explicit pool
 * starts with the exact arena candidate as its unchanged anchor; its complete weights are included
 * in the resume identity. Source model paths resolve relative to the search JSON's directory.
 */
data class StrategySearchSpecification(
    val arena: ArenaSpecification,
    val parameters: List<StrategySearchParameter> = emptyList(),
    val candidates: Int = 24,
    val screeningPairs: Int = 16,
    val selectionPairs: Int = 64,
    val finalists: Int = 4,
    val explicitCandidates: List<BotModel>? = null,
) {
    init {
        require(arena.experimentalRulesTransfer == null) {
            "Experimental rules transfer supports arena, token-order and purchase collection, not ordinary model search."
        }
        require(arena.expansionImitation == null && arena.cathedralTactics == null) {
            "Ordinary weight search does not support native expansion or Cathedral research overlays."
        }
        require(arena.tokenOrder == null) {
            "Ordinary model search cannot assign token-order actor roles."
        }
        require(arena.purpose == ArenaPurpose.SCREENING) {
            "Parameter search is selection data and requires SCREENING purpose."
        }
        require(arena.seed <= 99_999_998) { "Search seed must leave room for separate seed banks." }
        if (explicitCandidates == null) {
            require(
                parameters.size in 1..32 &&
                    parameters.map { it.name }.distinct().size == parameters.size
            ) {
                "Search requires one through thirty-two distinct planning parameters."
            }
        } else {
            require(parameters.isEmpty()) {
                "Explicit candidate models and sampled parameters are mutually exclusive."
            }
            require(explicitCandidates.size == candidates) {
                "The explicit model count must match the declared candidate count."
            }
            require(explicitCandidates.firstOrNull() == arena.candidate) {
                "Explicit candidate zero must exactly match the arena candidate anchor."
            }
        }
        val targets = parameters.flatMap { listOf(it.name) + it.linkedWeights.keys }
        require(targets.distinct().size == targets.size) {
            "Each primary or linked weight must be controlled by exactly one search parameter."
        }
        require(candidates in 2..256 && finalists in 1..candidates) {
            "Search requires 2–256 candidates and at least one finalist, up to the candidate count."
        }
        require(screeningPairs in 1..10000 && selectionPairs in screeningPairs..10000) {
            "Search banks require 1–10000 pairs, with selection at least as large as screening."
        }
        require(
            arena.configurations.size.toLong() *
                (candidates * screeningPairs.toLong() + finalists * selectionPairs.toLong()) <=
                100000
        ) {
            "Search is limited to 100000 candidate pairs across both stages."
        }
    }

    /** Initial seed offsets differ by one; the arena's 8191 stride makes these banks disjoint. */
    fun bank(stage: StrategySearchStage): ArenaSpecification =
        arena.copy(
            purpose = ArenaPurpose.SCREENING,
            pairsPerConfiguration =
                if (stage == StrategySearchStage.SCREENING) screeningPairs else selectionPairs,
            seed = arena.seed + stage.ordinal,
        )

    fun identity(): String {
        val legacy =
            listOf(
                    arena.identity(),
                    parameters.map { it.copy(linkedWeights = it.linkedWeights.toSortedMap()) },
                    candidates,
                    screeningPairs,
                    selectionPairs,
                    finalists,
                )
                .joinToString("\n")
        return if (explicitCandidates == null) {
            legacy
        } else {
            "$legacy\nexplicitCandidates=${explicitCandidates.map { it.weights.toSortedMap() }}"
        }
    }

    companion object {
        fun read(source: Path): StrategySearchSpecification {
            val node =
                ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(source.toFile())
            require(node.has("parameters") != node.has("candidateModels")) {
                "Search source requires exactly one of parameters or candidateModels."
            }
            val parameters = node["parameters"]
            require(parameters == null || parameters.isArray) {
                "Search parameters must be an array."
            }
            val models = node["candidateModels"]
            val explicitCandidates = models?.let { references ->
                require(references.isArray && references.size() in 2..256) {
                    "Explicit candidateModels must be an array of two through 256 model paths."
                }
                references.map { value ->
                    require(value.isTextual && value.asText().isNotBlank()) {
                        "Explicit candidate model references must be nonempty paths."
                    }
                    TrainingArtifacts.readModel(
                        source.toAbsolutePath().parent.resolve(value.asText()).normalize()
                    )
                }
            }
            fun integer(name: String, default: Int): Int {
                val value = node[name] ?: return default
                require(value.isIntegralNumber && value.canConvertToInt()) {
                    "Search $name must be an integer."
                }
                return value.intValue()
            }
            return StrategySearchSpecification(
                arena = ArenaSpecification.read(source),
                parameters = parameters?.map(StrategySearchParameter.Companion::read).orEmpty(),
                candidates = integer("candidates", explicitCandidates?.size ?: 24),
                screeningPairs = integer("screeningPairs", 16),
                selectionPairs = integer("selectionPairs", 64),
                finalists = integer("finalists", 4),
                explicitCandidates = explicitCandidates,
            )
        }
    }
}

/** Stable candidate identity is also used in individual model and pair checkpoint names. */
data class StrategySearchCandidate(
    val id: Int,
    val label: String,
    val values: Map<String, Double>,
    val model: BotModel,
)

object StrategySearch {
    /**
     * Zero, midpoint and in-range current-vector anchors plus a seeded Latin hypercube. The current
     * vector is included when the candidate budget has room and its linked weights satisfy every
     * declared relation exactly. Linked weights are expanded after sampling; unlinked
     * specifications retain their original candidate vectors and random draws.
     *
     * Explicit pools retain supplied order and weights. Their values report only effective changes
     * from the initial model, including zero for a removed nonzero coefficient.
     */
    fun candidates(specification: StrategySearchSpecification): List<StrategySearchCandidate> {
        specification.explicitCandidates?.let { models ->
            val initial = specification.arena.candidate.weights
            return models.mapIndexed { index, model ->
                val changed =
                    (initial.keys + model.weights.keys)
                        .sorted()
                        .filter { (initial[it] ?: 0.0) != (model.weights[it] ?: 0.0) }
                        .associateWith { model.weights[it] ?: 0.0 }
                StrategySearchCandidate(
                    id = index,
                    label = if (index == 0) "current" else "sample-$index",
                    values = changed,
                    model = model,
                )
            }
        }
        val parameters = specification.parameters.sortedBy { it.name }
        val zero = parameters.associate { it.name to 0.0 }
        val center = parameters.associate { it.name to (it.min + it.max) / 2.0 }
        val initial = parameters.associate {
            it.name to (specification.arena.candidate.weights[it.name] ?: 0.0)
        }
        val anchors =
            ((if (center == zero) listOf("zero-and-center" to zero)
                else listOf("zero" to zero, "center" to center)) +
                    listOfNotNull(
                        ("current" to initial).takeIf {
                            parameters.all { parameter ->
                                checkNotNull(initial[parameter.name]) {
                                    "Every current search dimension requires a value."
                                } in parameter.min..parameter.max &&
                                    parameter.linkedWeights.all { (target, multiplier) ->
                                        (specification.arena.candidate.weights[target] ?: 0.0) ==
                                            (initial[parameter.name] ?: 0.0) * multiplier
                                    }
                            }
                        }
                    ))
                .distinctBy { it.second }
                .take(specification.candidates)
        val remaining = specification.candidates - anchors.size
        val random = Random(specification.arena.seed)
        val dimensions = parameters.associate { parameter ->
            parameter.name to
                (0 until remaining).shuffled(random).map { stratum ->
                    parameter.min +
                        (stratum + random.nextDouble()) / remaining *
                            (parameter.max - parameter.min)
                }
        }
        return (anchors +
                List(remaining) { sample ->
                    "sample-${sample + 1}" to
                        parameters.associate { parameter ->
                            parameter.name to
                                checkNotNull(dimensions[parameter.name]) {
                                    "Every search parameter requires a sampled dimension."
                                }[sample]
                        }
                })
            .mapIndexed { index, (label, values) ->
                val expanded = buildMap {
                    for (parameter in parameters) {
                        val value =
                            checkNotNull(values[parameter.name]) {
                                "Every sampled parameter requires a primary value."
                            }
                        putAll(parameter.weights(value))
                    }
                }
                StrategySearchCandidate(
                    index,
                    label,
                    expanded,
                    BotModel(
                        specification.arena.candidate.schemaVersion,
                        specification.arena.candidate.weights + expanded,
                    ),
                )
            }
    }
}

private data class StrategySearchResult(
    val candidate: StrategySearchCandidate,
    val pairs: List<JsonNode>,
) {
    val overall = StrategyArena.report(pairs)

    fun winDifference(): Double = (overall["winShareDifference"] as PilotInterval).mean

    fun rewardDifference(): Double = (overall["rewardDifference"] as PilotInterval).mean

    fun summary(): Map<String, Any> =
        mapOf(
            "candidateId" to candidate.id,
            "label" to candidate.label,
            "parameters" to candidate.values,
            "model" to "candidate-${candidate.id}.json",
            "overall" to overall,
            "byConfiguration" to
                pairs
                    .groupBy { it.required("configuration").asText() }
                    .mapValues { (_, group) -> StrategyArena.report(group) },
        )
}

/** One bounded worker pool; incumbent banks finish before candidate pair jobs are submitted. */
internal class StrategySearchRunner(
    private val specification: StrategySearchSpecification,
    private val artifacts: TrainingArtifacts,
    private val workers: Tournaments,
    private val deadlineNanos: Long,
    private val metadata: Map<String, Any> = emptyMap(),
    private val playGame: (ArenaSpecification, Int, BotModel, Long) -> ArenaGame =
        StrategyArena::game,
) {
    fun run(): StrategySearchCandidate {
        val candidates = StrategySearch.candidates(specification)
        candidates.forEach { artifacts.model(it.model, "candidate-${it.id}.json") }
        val screening = stage(StrategySearchStage.SCREENING, candidates)
        artifacts.write("search.json", report(screening, emptyList()))
        val selection =
            stage(
                StrategySearchStage.SELECTION,
                screening.take(specification.finalists).map { it.candidate },
            )
        val selected = selection.first().candidate
        artifacts.model(selected.model, "candidate.json")
        artifacts.write("search.json", report(screening, selection))
        return selected
    }

    private fun stage(
        stage: StrategySearchStage,
        candidates: List<StrategySearchCandidate>,
    ): List<StrategySearchResult> {
        val bank = specification.bank(stage)
        val prefix = stage.name.lowercase()
        val total = bank.configurations.size * bank.pairsPerConfiguration
        artifacts.write("state.json", metadata + mapOf("phase" to "$prefix-incumbents"))
        val incumbents =
            workers
                .run(
                    List(total) { index ->
                        {
                            val name = "$prefix-incumbent-$index.json"
                            val node =
                                artifacts.read(name)
                                    ?: run {
                                        artifacts.write(
                                            name,
                                            playGame(bank, index, bank.incumbent, deadlineNanos),
                                        )
                                        checkNotNull(artifacts.read(name)) {
                                            "Completed incumbent games must be readable."
                                        }
                                    }
                            index to readGame(node)
                        }
                    }
                )
                .toMap()
        artifacts.write("state.json", metadata + mapOf("phase" to "$prefix-candidates"))
        var completed = 0
        val results =
            workers
                .run(
                    candidates.flatMap { candidate ->
                        List(total) { index ->
                            {
                                val name = "$prefix-candidate-${candidate.id}-pair-$index.json"
                                val pair =
                                    artifacts.read(name)
                                        ?: run {
                                            val incumbent =
                                                checkNotNull(incumbents[index]) {
                                                    "Each search pair requires its frozen incumbent game."
                                                }
                                            val samePolicy =
                                                candidate.model.weights.filterValues {
                                                    it != 0.0
                                                } ==
                                                    bank.incumbent.weights.filterValues {
                                                        it != 0.0
                                                    }
                                            val game =
                                                if (samePolicy) incumbent
                                                else
                                                    playGame(
                                                        bank,
                                                        index,
                                                        candidate.model,
                                                        deadlineNanos,
                                                    )
                                            artifacts.write(
                                                name,
                                                StrategyArena.pair(bank, index, game, incumbent),
                                            )
                                            checkNotNull(artifacts.read(name)) {
                                                "Completed search pairs must be readable."
                                            }
                                        }
                                candidate.id to pair
                            }
                        }
                    }
                ) {
                    completed++
                    if (completed % 16 == 0 || completed == candidates.size * total) {
                        println(
                            "Search $prefix $completed/${candidates.size * total} candidate pairs"
                        )
                    }
                }
                .groupBy({ it.first }, { it.second })
        return candidates
            .map { candidate ->
                StrategySearchResult(
                    candidate,
                    checkNotNull(results[candidate.id]) {
                            "Each search candidate requires completed pairs."
                        }
                        .sortedBy { it.required("index").intValue() },
                )
            }
            .sortedWith(
                compareByDescending<StrategySearchResult> { it.winDifference() }
                    .thenByDescending { it.rewardDifference() }
                    .thenBy { it.candidate.id }
            )
    }

    private fun report(
        screening: List<StrategySearchResult>,
        selection: List<StrategySearchResult>,
    ): Map<String, Any> =
        metadata +
            mapOf(
                "complete" to selection.isNotEmpty(),
                "promoted" to false,
                "humanStrengthVerified" to false,
                "sourceParameters" to specification.parameters,
                "candidates" to specification.candidates,
                "finalists" to specification.finalists,
                "ranking" to
                    "Paired win-share difference, then paired reward difference, then candidate ID",
                "selectionOnly" to true,
                "intervalInterpretation" to
                    "Descriptive normal intervals over paired seeds; selection invalidates winner significance. Independent confirmation is required.",
                "banks" to
                    StrategySearchStage.entries.associate { stage ->
                        val bank = specification.bank(stage)
                        stage.name.lowercase() to
                            mapOf(
                                "purpose" to ArenaPurpose.SCREENING.name,
                                "seedOffset" to bank.seed,
                                "pairsPerConfiguration" to bank.pairsPerConfiguration,
                                "seeds" to
                                    List(bank.configurations.size * bank.pairsPerConfiguration) {
                                        StrategyArena.seed(bank.seed, it)
                                    },
                            )
                    },
                "screening" to screening.map { it.summary() },
                "selection" to selection.map { it.summary() },
                "selectedCandidateId" to (selection.firstOrNull()?.candidate?.id ?: -1),
                "confirmation" to "Not run; candidate.json is an unqualified search proposal.",
            )

    private fun readGame(node: JsonNode): ArenaGame =
        ArenaGame(
            wins = node.required("wins").map { it.doubleValue() },
            rewards = node.required("rewards").map { it.doubleValue() },
            ranks = node.required("ranks").map { it.doubleValue() },
            scores =
                node.required("scores").map { score ->
                    PilotReplayScore(
                        player = score.required("player").asText(),
                        advancePoints = score.required("advancePoints").intValue(),
                        cash = score.required("cash").intValue(),
                        misery = score.required("misery").takeUnless { it.isNull }?.intValue(),
                        total = score.required("total").takeUnless { it.isNull }?.intValue(),
                        winner = score.required("winner").booleanValue(),
                    )
                },
            decisions = node.required("decisions").intValue(),
            elapsedMillis = node.required("elapsedMillis").longValue(),
        )
}

/** Offline bounded search produces a candidate, never a production model or a qualification. */
object StrategySearchMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for strategy search." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                }
        ) {
            "Search options may not repeat and must be mode, source, output, workers or hours."
        }
        require(fields["--mode"] == "strategy-search") { "Strategy-search mode must be explicit." }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) { "Strategy search requires --source PATH." }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) { "Strategy search requires --output PATH." }
                )
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) {
            "Search output must not contain its source specification."
        }
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val workerCount = fields["--workers"]?.toInt() ?: ((cpus * .8).toInt() - 2).coerceAtLeast(1)
        val hours = fields["--hours"]?.toDouble() ?: 3.0
        require(hours.isFinite() && hours > 0 && hours <= 72) {
            "Search hours must be positive and at most 72."
        }
        val specification = StrategySearchSpecification.read(source)
        val signature =
            trainingSignature(
                "strategy-search-1:$source\n${Files.readString(source)}\n${specification.identity()}"
            )
        val artifacts = TrainingArtifacts(output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == "strategy-search" &&
                    previous["signature"]?.asText() == signature
        ) {
            "Search source, code, models or settings changed; use a new output directory."
        }
        if (previous == null) {
            Files.list(output).use { paths ->
                require(
                    paths.allMatch {
                        it.fileName.toString() in
                            setOf("supervisor.json", ".supervisor.lock", "trainer.log")
                    }
                ) {
                    "Search output already contains artifacts from another run."
                }
            }
        }
        val identity = mapOf("kind" to "strategy-search", "signature" to signature)
        artifacts.write("state.json", identity + mapOf("phase" to "initialized"))
        artifacts.model(specification.arena.candidate, "initial.json")
        artifacts.model(specification.arena.incumbent, "incumbent.json")
        specification.arena.opponents.forEachIndexed { index, model ->
            artifacts.model(model, "opponent-$index.json")
        }
        specification.arena.frozenPurchase?.snapshot(artifacts)
        val startPath = output.resolve("started-at.txt")
        val started =
            if (Files.exists(startPath)) Instant.parse(Files.readString(startPath).trim())
            else Instant.now().also { Files.writeString(startPath, it.toString()) }
        val remaining =
            minOf(
                Duration.between(Instant.now(), started.plus(Duration.ofHours(72))).toNanos(),
                (hours * 3_600_000_000_000).toLong(),
            )
        require(remaining > 0) { "The search has exhausted its three-day allowance." }
        val deadline = System.nanoTime() + remaining
        val metadata =
            identity +
                specification.arena.frozenPurchase?.metadata().orEmpty() +
                mapOf(
                    "source" to source.toString(),
                    "configurations" to specification.arena.configurations.map { it.id },
                    "workers" to workerCount,
                    "opponentModels" to specification.arena.opponents.size,
                    "difficulty" to "HARD",
                    "startedAt" to started.toString(),
                )
        artifacts.write("run.json", metadata)
        val selected =
            Tournaments(workerCount, deadline).use { workers ->
                StrategySearchRunner(specification, artifacts, workers, deadline, metadata).run()
            }
        artifacts.write(
            "state.json",
            metadata + mapOf("phase" to "complete", "selectedCandidateId" to selected.id),
        )
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println("Search complete: candidate ${selected.id}; independent confirmation required.")
    }
}
