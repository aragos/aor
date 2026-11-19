package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotTokenCorrection
import dev.aragos.aor.ai.BotTokenValue
import java.nio.file.Path
import kotlin.math.abs
import kotlin.random.Random

const val TOKEN_VALUE_ORIGIN_SCHEMA_VERSION = 2
const val TOKEN_VALUE_MEAN_ZERO_TOLERANCE = 1.0e-12
const val TOKEN_VALUE_MAX_CONTINUATION_REPLICATES = 4

enum class TokenValueSelectionProtocol {
    RESERVOIR_V1,
    PRECOMMITTED_ORDINAL_V1,
}

/** Replacement fits an ordinary baseline; correction preserves its existing token-value head. */
enum class TokenValueLearningMode {
    REPLACEMENT,
    CORRECTION;

    fun withWeights(base: BotModel, coefficients: List<Double>): BotModel =
        when (this) {
            REPLACEMENT -> BotTokenValue.withWeights(base, coefficients)
            CORRECTION -> BotTokenCorrection.withWeights(base, coefficients)
        }

    companion object {
        internal fun read(value: JsonNode?): TokenValueLearningMode {
            if (value == null) return REPLACEMENT
            return requireNotNull(
                entries.singleOrNull { value.isTextual && it.name == value.asText() }
            ) {
                "Token-value learningMode must be REPLACEMENT or CORRECTION."
            }
        }
    }
}

/** Fixed before creating the game; ordinal counts controlled token decisions in this epoch. */
data class TokenValueRootRequest(val epoch: Int, val ordinal: Int) {
    init {
        require(epoch in 1..3 && ordinal in 1..(if (epoch == 3) 4 else 6)) {
            "A token root requires epoch one through three and an ordinal within its declared range."
        }
    }

    companion object {
        fun from(gameSeed: Int, bankIndex: Int): TokenValueRootRequest {
            require(bankIndex >= 0) { "A token root request requires a nonnegative origin index." }
            val epoch = bankIndex % 3 + 1
            val ordinal = Random(gameSeed xor 0x2e59c17).nextInt(1, if (epoch == 3) 5 else 7)
            return TokenValueRootRequest(epoch, ordinal)
        }
    }
}

enum class TokenValueSplit {
    TRAINING,
    VALIDATION,
}

enum class TokenValueOriginStatus {
    COLLECTED,
    SKIPPED,
}

enum class TokenValueSkipReason {
    NO_ELIGIBLE_TOKEN_ROOT,
    REQUESTED_ROOT_NOT_REACHED,
    PRECOMMITTED_ROOT_INELIGIBLE,
}

/**
 * Predeclared origin-game banks and ridge candidates; validation is selection, not confirmation.
 */
data class TokenValueSpecification(
    val arena: ArenaSpecification,
    val trainingRoots: Int = 96,
    val validationRoots: Int = 32,
    val continuationReplicates: Int = 2,
    val regularization: List<Double> = listOf(0.001, 0.01, 0.1, 1.0),
    val learningMode: TokenValueLearningMode = TokenValueLearningMode.REPLACEMENT,
) {
    init {
        require(arena.frozenPurchase == null) {
            "Token-value collection does not support an arena frozenPurchase environment."
        }
        require(arena.jointPolicy == null) {
            "Joint actor roles are supported only by complete arena and strategy replay."
        }
        require(arena.tokenOrder == null) {
            "Token-value collection cannot apply a tokenOrder overlay."
        }
        require(arena.purpose == ArenaPurpose.SCREENING) {
            "A token-value pilot requires a SCREENING arena."
        }
        require(arena.candidate == arena.incumbent) {
            "Token-value collection requires identical candidate and incumbent baseline models."
        }
        require(arena.seed < 100_000_000) { "Token-value validation needs the next seed offset." }
        require(trainingRoots in 2..8192 && validationRoots in 2..8192) {
            "Token-value banks require two through 8192 origin games each."
        }
        require(
            trainingRoots % arena.configurations.size == 0 &&
                validationRoots % arena.configurations.size == 0
        ) {
            "Each token-value bank needs an equal positive number of origins per setup."
        }
        require(continuationReplicates in 1..TOKEN_VALUE_MAX_CONTINUATION_REPLICATES) {
            "A token-value pilot supports one through four paired continuation streams."
        }
        require(
            regularization.size in 1..16 &&
                regularization.distinct().size == regularization.size &&
                regularization.all { it.isFinite() && it > 0.0 }
        ) {
            "Token-value regularization requires one through sixteen distinct positive values."
        }
        require(
            learningMode == TokenValueLearningMode.CORRECTION ||
                arena.candidate.weights[BotTokenValue.GATE] != 1.0
        ) {
            "Replacement collection requires a baseline with its token-value head disabled."
        }
        require(arena.candidate.weights[BotTokenCorrection.GATE] != 1.0) {
            "Token-value collection requires a baseline with its correction head disabled."
        }
    }

    val origins: Int
        get() = trainingRoots + validationRoots

    fun split(index: Int): TokenValueSplit {
        require(index in 0 until origins) { "Token-value origin index is outside its banks." }
        return if (index < trainingRoots) TokenValueSplit.TRAINING else TokenValueSplit.VALIDATION
    }

    internal fun bankIndex(index: Int): Int =
        if (split(index) == TokenValueSplit.TRAINING) index else index - trainingRoots

    internal fun lineup(index: Int): StrategyArena.Lineup {
        val split = split(index)
        val bankSize = if (split == TokenValueSplit.TRAINING) trainingRoots else validationRoots
        val perConfiguration = bankSize / arena.configurations.size
        val arenaIndex = bankIndex(index)
        val lineup =
            StrategyArena.lineup(
                arena.copy(
                    seed = arena.seed + split.ordinal,
                    pairsPerConfiguration = perConfiguration,
                ),
                arenaIndex,
            )
        val configurationIndex = arenaIndex / perConfiguration
        return lineup.copy(
            seat = (lineup.seat + configurationIndex) % lineup.configuration.playerCount
        )
    }

    internal fun rootRequest(index: Int): TokenValueRootRequest =
        TokenValueRootRequest.from(lineup(index).seed, bankIndex(index))

    fun identity(): String =
        (listOf(
                arena.identity(),
                trainingRoots,
                validationRoots,
                continuationReplicates,
                regularization,
                BotTokenValue.FEATURE_VERSION,
                BotTokenValue.featureNames,
                TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
                TOKEN_VALUE_ORIGIN_SCHEMA_VERSION,
                TOKEN_VALUE_MEAN_ZERO_TOLERANCE,
            ) +
                listOfNotNull(
                    "balanced-configurations-seat-rotation-1"
                        .takeIf { arena.configurations.size > 1 }
                ))
            .joinToString("\n") +
            if (learningMode == TokenValueLearningMode.REPLACEMENT) {
                ""
            } else {
                "\nlearningMode=$learningMode"
            }

    companion object {
        fun read(source: Path): TokenValueSpecification {
            val node = ObjectMapper().readTree(source.toFile())
            fun integer(name: String, default: Int): Int {
                val value = node[name] ?: return default
                require(value.isIntegralNumber && value.canConvertToInt()) {
                    "Token-value $name must be an integer."
                }
                return value.intValue()
            }
            val ridge = node["regularization"]
            require(ridge == null || ridge.isArray && ridge.all(JsonNode::isNumber)) {
                "Token-value regularization must be an array of numbers."
            }
            return TokenValueSpecification(
                ArenaSpecification.read(source),
                integer("trainingRoots", 96),
                integer("validationRoots", 32),
                integer("continuationReplicates", 2),
                ridge?.map(JsonNode::doubleValue) ?: listOf(0.001, 0.01, 0.1, 1.0),
                TokenValueLearningMode.read(node["learningMode"]),
            )
        }
    }
}

/** Terminal public labels; scores retain cash/point changes even when rank reward is unchanged. */
data class TokenValueOutcome(
    val reward: Double,
    val winShare: Double,
    val rankReward: Double,
    val scores: List<PilotReplayScore>,
    val decisions: Int,
    val continuationSeed: Long,
) {
    init {
        require(listOf(reward, winShare, rankReward).all { it.isFinite() && it in 0.0..1.0 }) {
            "Token-value outcomes require finite terminal rewards between zero and one."
        }
        require(scores.size in 3..6 && decisions >= 0) {
            "Token-value outcomes require registered public scores and a decision count."
        }
    }
}

/**
 * Safe action-minus-baseline features and paired public terminal outcomes; never teacher argmax.
 */
data class TokenValueAlternative(
    val command: String,
    val features: List<Double>,
    val outcomes: List<TokenValueOutcome>,
) {
    init {
        require(command.matches(Regex("buy-tokens -?[0-9]+"))) {
            "Token-value alternatives require signed token-purchase commands."
        }
        require(
            features.size == BotTokenValue.featureNames.size &&
                features.all {
                    it.isFinite() &&
                        it in -2 * BotTokenValue.FEATURE_LIMIT..2 * BotTokenValue.FEATURE_LIMIT
                }
        ) {
            "Token-value alternatives require complete bounded shared feature differences."
        }
        require(outcomes.size in 1..TOKEN_VALUE_MAX_CONTINUATION_REPLICATES) {
            "Token-value alternatives require one through four outcomes."
        }
    }
}

/**
 * One complete origin checkpoint. No authority state, deck, opponent hand or sealed opponent action
 * is serialized. A skipped origin keeps its seed/index and has no fitted row. Schema one preserves
 * legacy reservoir/fallback provenance. Schema two precommits epoch and ordinal before simulation;
 * [originDecisions] is the prefix length at a collected root, or full-game length for a skipped
 * request. [learningMode] binds the incumbent used for both features and continuations; its absence
 * in old checkpoints means replacement. Replicates and alternatives never become independent game
 * counts.
 */
data class TokenValueOrigin(
    val index: Int,
    val split: TokenValueSplit,
    val configuration: String,
    val gameSeed: Int,
    val seat: Int,
    val opponentIndices: List<Int>,
    val requestedEpoch: Int,
    val originDecisions: Int,
    val status: TokenValueOriginStatus,
    val featureVersion: String = BotTokenValue.FEATURE_VERSION,
    val actualEpoch: Int? = null,
    val turn: Int? = null,
    val rootDecision: Int? = null,
    val baselineCommand: String? = null,
    val alternatives: List<TokenValueAlternative> = emptyList(),
    val skipReason: TokenValueSkipReason? = null,
    val schemaVersion: Int = TOKEN_VALUE_ORIGIN_SCHEMA_VERSION,
    val selectionProtocol: TokenValueSelectionProtocol =
        TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
    val requestedOrdinal: Int? = null,
    val learningMode: TokenValueLearningMode = TokenValueLearningMode.REPLACEMENT,
) {
    init {
        require(index >= 0 && requestedEpoch in 1..3 && originDecisions >= 0) {
            "Token-value provenance requires an origin index, requested epoch and decision count."
        }
        require(featureVersion == BotTokenValue.FEATURE_VERSION) {
            "Token-value checkpoints require the current shared feature version."
        }
        require(opponentIndices.size in 3..6 && seat in opponentIndices.indices) {
            "Token-value provenance requires a registered seat and complete public lineup."
        }
        require(
            schemaVersion == 1 &&
                selectionProtocol == TokenValueSelectionProtocol.RESERVOIR_V1 &&
                requestedOrdinal == null ||
                schemaVersion == TOKEN_VALUE_ORIGIN_SCHEMA_VERSION &&
                    selectionProtocol == TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1 &&
                    requestedOrdinal != null &&
                    requestedOrdinal in 1..(if (requestedEpoch == 3) 4 else 6)
        ) {
            "Token-value origin schema must match its root-selection protocol and requested ordinal."
        }
        if (status == TokenValueOriginStatus.SKIPPED) {
            require(
                skipReason != null &&
                    alternatives.isEmpty() &&
                    baselineCommand == null &&
                    actualEpoch == null &&
                    turn == null &&
                    rootDecision == null
            ) {
                "Skipped token-value origins require a reason and no selected-root data."
            }
            require(
                selectionProtocol == TokenValueSelectionProtocol.RESERVOIR_V1 ||
                    skipReason != TokenValueSkipReason.NO_ELIGIBLE_TOKEN_ROOT
            ) {
                "Precommitted token-value skips must identify an unreached or ineligible target."
            }
        } else {
            require(
                skipReason == null &&
                    actualEpoch != null &&
                    actualEpoch in 1..3 &&
                    turn != null &&
                    turn > 0 &&
                    rootDecision != null &&
                    rootDecision >= 0
            ) {
                "Collected token-value origins require an actual epoch, turn and root decision."
            }
            require(
                selectionProtocol == TokenValueSelectionProtocol.RESERVOIR_V1 ||
                    actualEpoch == requestedEpoch && originDecisions == rootDecision
            ) {
                "A precommitted root must stop its backbone at the requested epoch and decision."
            }
            require(
                alternatives.size in 2..6 &&
                    alternatives.map { it.command }.distinct().size == alternatives.size
            ) {
                "Collected token-value roots require two through six distinct alternatives."
            }
            val baseline = alternatives.singleOrNull { it.command == baselineCommand }
            require(baseline != null && baseline.features.all { it == 0.0 }) {
                "A token-value root requires an explicit zero-difference baseline alternative."
            }
            require(alternatives.all { it.outcomes.size == baseline.outcomes.size }) {
                "All token-value alternatives must share the baseline continuation count."
            }
            require(
                alternatives.all { alternative ->
                    alternative.outcomes.all { it.scores.size == opponentIndices.size }
                }
            ) {
                "Every token-value continuation must retain all registered public scores."
            }
            val seeds = baseline.outcomes.map(TokenValueOutcome::continuationSeed)
            require(
                seeds.distinct().size == seeds.size &&
                    alternatives.all {
                        it.outcomes.map(TokenValueOutcome::continuationSeed) == seeds
                    }
            ) {
                "Token-value alternatives must pair the same distinct continuation seeds in order."
            }
        }
    }

    fun baseline(): TokenValueAlternative =
        checkNotNull(alternatives.singleOrNull { it.command == baselineCommand }) {
            "Only collected token-value roots have a baseline."
        }

    /**
     * Mean paired reward difference; sub-tolerance roundoff becomes zero, raw outcomes are
     * retained.
     */
    fun delta(alternative: TokenValueAlternative): Double =
        alternative.outcomes
            .zip(baseline().outcomes) { after, before -> after.reward - before.reward }
            .average()
            .let { if (abs(it) < TOKEN_VALUE_MEAN_ZERO_TOLERANCE) 0.0 else it }

    companion object {
        fun read(node: JsonNode): TokenValueOrigin {
            fun nullableInt(name: String): Int? =
                node[name]?.takeUnless(JsonNode::isNull)?.intValue()
            fun outcome(value: JsonNode): TokenValueOutcome =
                TokenValueOutcome(
                    value.required("reward").doubleValue(),
                    value.required("winShare").doubleValue(),
                    value.required("rankReward").doubleValue(),
                    value.required("scores").map { score ->
                        PilotReplayScore(
                            score.required("player").asText(),
                            score.required("advancePoints").intValue(),
                            score.required("cash").intValue(),
                            score.required("misery").takeUnless(JsonNode::isNull)?.intValue(),
                            score.required("total").takeUnless(JsonNode::isNull)?.intValue(),
                            score.required("winner").booleanValue(),
                        )
                    },
                    value.required("decisions").intValue(),
                    value.required("continuationSeed").longValue(),
                )
            return TokenValueOrigin(
                index = node.required("index").intValue(),
                split = TokenValueSplit.valueOf(node.required("split").asText()),
                configuration = node.required("configuration").asText(),
                gameSeed = node.required("gameSeed").intValue(),
                seat = node.required("seat").intValue(),
                opponentIndices = node.required("opponentIndices").map(JsonNode::intValue),
                requestedEpoch = node.required("requestedEpoch").intValue(),
                originDecisions = node.required("originDecisions").intValue(),
                status = TokenValueOriginStatus.valueOf(node.required("status").asText()),
                featureVersion = node.required("featureVersion").asText(),
                actualEpoch = nullableInt("actualEpoch"),
                turn = nullableInt("turn"),
                rootDecision = nullableInt("rootDecision"),
                baselineCommand = node["baselineCommand"]?.takeUnless(JsonNode::isNull)?.asText(),
                alternatives =
                    node.required("alternatives").map { alternative ->
                        TokenValueAlternative(
                            alternative.required("command").asText(),
                            alternative.required("features").map(JsonNode::doubleValue),
                            alternative.required("outcomes").map(::outcome),
                        )
                    },
                skipReason =
                    node["skipReason"]
                        ?.takeUnless(JsonNode::isNull)
                        ?.asText()
                        ?.let(TokenValueSkipReason::valueOf),
                schemaVersion = node["schemaVersion"]?.intValue() ?: 1,
                selectionProtocol =
                    node["selectionProtocol"]?.asText()?.let(TokenValueSelectionProtocol::valueOf)
                        ?: TokenValueSelectionProtocol.RESERVOIR_V1,
                requestedOrdinal = nullableInt("requestedOrdinal"),
                learningMode = TokenValueLearningMode.read(node["learningMode"]),
            )
        }
    }
}
