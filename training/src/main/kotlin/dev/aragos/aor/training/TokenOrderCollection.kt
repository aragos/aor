package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotTokenValue
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TokenObservationEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import dev.aragos.aor.ai.TokenOrderReference
import java.nio.file.Path

/** Explicit continuation semantics; omission retains the historical purchase-only collector. */
internal enum class TokenOrderContinuationProtocol(
    val replicates: Int,
    val reference: TokenOrderReference = TokenOrderReference.ORDINARY,
) {
    ARENA_OVERLAYS_1(2),
    ARENA_OVERLAYS_EIGHT_TAILS_1(8),
    ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1(8, TokenOrderReference.FIXED_16_TO_15),
}

/** Fresh, precommitted origin banks; validation is model selection, never final confirmation. */
internal data class TokenOrderSpecification(
    val arena: ArenaSpecification,
    val trainingRoots: Int = 96,
    val validationRoots: Int = 32,
    val continuationReplicates: Int = 2,
    val continuationProtocol: TokenOrderContinuationProtocol? = null,
    /** At most eight complete origins, fixed before collection, may be reproduced for auditing. */
    val replayOrigins: List<Int> = emptyList(),
) {
    init {
        require(arena.purpose == ArenaPurpose.SCREENING && arena.configurations.size == 1) {
            "Token-order collection requires one SCREENING configuration."
        }
        require(arena.candidate == arena.incumbent) {
            "Token-order roots require identical ordinary candidate and incumbent baselines."
        }
        if (continuationProtocol == null) {
            require(arena.tokenOrder == null && arena.expansionImitation == null) {
                "Legacy token-order roots require ordinary baselines without a learned token overlay or expansion imitation."
            }
            require(replayOrigins.isEmpty()) {
                "Origin reproduction requires the explicit arena continuation protocol."
            }
        } else {
            require(arena.tokenOrder != null || reference == TokenOrderReference.ORDINARY) {
                "Fixed-reference collection requires its explicit candidate fixed-T controller."
            }
            arena.tokenOrder?.validateCollectionContinuation(reference)
            requireNotNull(arena.expansionImitation) {
                    "Arena token continuation requires an explicit expansion model."
                }
                .validateCollectionContinuation()
            require(arena.candidate.weights["planning.tokenOrderControl.fixed16To15"] != 1.0) {
                "Token collection must retain its ordinary head without the fixed token gate."
            }
            require(
                replayOrigins.size in 1..8 &&
                    replayOrigins.distinct() == replayOrigins &&
                    replayOrigins.all { it in 0 until origins }
            ) {
                "Arena token continuation requires one through eight distinct prospective replay origins."
            }
        }
        val purchase =
            requireNotNull(arena.frozenPurchase) {
                "Token-order collection requires an explicit shared frozenPurchase environment."
            }
        purchase.controllers(arena.configurations.single().playerCount, 0, side = null)
        require(arena.seed < 100_000_000) { "Token-order validation needs the next seed offset." }
        require(trainingRoots in 2..8192 && validationRoots in 2..8192) {
            "Token-order banks require two through 8192 origin games each."
        }
        require(continuationReplicates == (continuationProtocol?.replicates ?: 2)) {
            "Token-order chance-stream count must match its explicit continuation protocol."
        }
    }

    val origins: Int
        get() = trainingRoots + validationRoots

    val reference: TokenOrderReference
        get() = continuationProtocol?.reference ?: TokenOrderReference.ORDINARY

    /** Eight-tail opt-in extends the same seed prefix without changing legacy value collectors. */
    fun continuationSeed(originSeed: Int, replicate: Int): Long {
        require(replicate in 0 until continuationReplicates) {
            "A token-order continuation must belong to its declared stream prefix."
        }
        return if (continuationProtocol?.replicates == 8) {
            (originSeed.toLong() shl 3) xor replicate.toLong() xor 0x6f20a5319dL
        } else {
            TokenValueCollector.continuationSeed(originSeed, replicate)
        }
    }

    fun split(index: Int): TokenValueSplit {
        require(index in 0 until origins) {
            "A token-order origin must belong to its declared bank."
        }
        return if (index < trainingRoots) TokenValueSplit.TRAINING else TokenValueSplit.VALIDATION
    }

    fun lineup(index: Int): StrategyArena.Lineup {
        val validation = split(index) == TokenValueSplit.VALIDATION
        return StrategyArena.lineup(
            arena.copy(
                seed = arena.seed + if (validation) 1 else 0,
                pairsPerConfiguration = if (validation) validationRoots else trainingRoots,
            ),
            if (validation) index - trainingRoots else index,
        )
    }

    fun request(index: Int): TokenValueRootRequest =
        TokenValueRootRequest.from(
            lineup(index).seed,
            if (split(index) == TokenValueSplit.TRAINING) index else index - trainingRoots,
        )

    fun identity(): String =
        (listOf(
                arena.identity(),
                trainingRoots,
                validationRoots,
                continuationReplicates,
                TerritoryEncoder.TOKEN_2.version,
                TokenOrderPolicy.PROPOSAL_VERSION,
                TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
            ) + listOfNotNull(continuationProtocol?.let { "$it:$replayOrigins" }))
            .joinToString("\n")

    /** The same phase-disjoint controllers are reused for the origin prefix and every tail. */
    fun controllers(lineup: StrategyArena.Lineup): Map<Int, SimulationController> {
        if (continuationProtocol == null) {
            return requireNotNull(arena.frozenPurchase) {
                    "A validated token collector retains its shared purchase environment."
                }
                .controllers(lineup.configuration.playerCount, lineup.seat, side = null)
        }
        return arena
            .controllers(
                playerCount = lineup.configuration.playerCount,
                controlledSeat = lineup.seat,
                side = ArenaSide.CANDIDATE,
                ordinary = arena.candidate,
                opponentIndices = lineup.opponentIndices,
            )
            .also { controllers ->
                val controlled = controllers[lineup.seat]?.controls("buy-tokens") == true
                check(controlled == (reference == TokenOrderReference.FIXED_16_TO_15)) {
                    "The controlled token continuation must match its explicit ordinary or fixed-T reference."
                }
            }
    }

    companion object {
        fun read(source: Path): TokenOrderSpecification {
            val node = PurchaseJson.read(source)
            require(node.has("continuationProtocol") || !node.has("replayOrigins")) {
                "Declared replay origins require the explicit arena continuation protocol."
            }
            fun count(name: String, default: Int): Int =
                node[name]?.let { PurchaseJson.integer(it, name) } ?: default
            return TokenOrderSpecification(
                ArenaSpecification.read(source),
                count("trainingRoots", 96),
                count("validationRoots", 32),
                count("continuationReplicates", 2),
                node["continuationProtocol"]?.let { value ->
                    val name = PurchaseJson.text(value, "continuationProtocol")
                    requireNotNull(
                        TokenOrderContinuationProtocol.entries.find { it.name == name }
                    ) {
                        "Unknown token continuation protocol: $name."
                    }
                },
                node["replayOrigins"]?.let { values ->
                    PurchaseJson.array(values, "replayOrigins").map {
                        PurchaseJson.integer(it, "replay origin")
                    }
                } ?: emptyList(),
            )
        }
    }
}

internal data class TokenOrderAlternative(
    val index: Int,
    val command: String,
    val outcomes: List<TokenValueOutcome>,
)

/** No authority world is serialized. Skips have no observations, proposals or terminal labels. */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class TokenOrderOrigin(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val split: TokenValueSplit,
    val configuration: String,
    val gameSeed: Int,
    val seat: Int,
    val opponentIndices: List<Int>,
    val requestedEpoch: Int,
    val requestedOrdinal: Int,
    val originDecisions: Int,
    val status: TokenValueOriginStatus,
    val skipReason: TokenValueSkipReason? = null,
    val actualEpoch: Int? = null,
    val turn: Int? = null,
    val rootDecision: Int? = null,
    val baselineCommand: String? = null,
    /**
     * Only residual roots declare this; observation.baselineIndex remains the actual ordinary bid.
     */
    val referenceIndex: Int? = null,
    val observation: TerritoryObservation? = null,
    val proposedIndices: List<Int>? = null,
    val alternatives: List<TokenOrderAlternative>? = null,
)

/** One prospective root per fresh game, followed by paired complete-game continuations. */
internal class TokenOrderCollector(
    private val specification: TokenOrderSpecification,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): TokenOrderOrigin {
        val lineup = specification.lineup(index)
        val request = specification.request(index)
        val models =
            lineup.opponentIndices.mapIndexed { seat, opponent ->
                if (seat == lineup.seat) specification.arena.candidate
                else specification.arena.opponents[opponent]
            }
        val continuation =
            FrozenPolicyContinuation(
                models,
                specification.controllers(lineup),
                lineup.seed,
                deadlineNanos,
            )
        val session = ExperimentSession.start(lineup.configuration, lineup.seed.toLong())
        val actor = session.state.playerIds[lineup.seat]
        fun provenance(status: TokenValueOriginStatus, skip: TokenValueSkipReason? = null) =
            TokenOrderOrigin(
                signature = signature,
                index = index,
                split = specification.split(index),
                configuration = lineup.configuration.id,
                gameSeed = lineup.seed,
                seat = lineup.seat,
                opponentIndices = lineup.opponentIndices,
                requestedEpoch = request.epoch,
                requestedOrdinal = request.ordinal,
                originDecisions = session.decisions,
                status = status,
                skipReason = skip,
            )
        var ordinal = 0
        var skip = TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED
        while (session.state.stage != Stage.FINISHED) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            val ordinary = observations[actor]
            if (
                session.state.stage == Stage.TOKEN_PURCHASE &&
                    session.state.currentEpoch.number == request.epoch &&
                    ordinary != null &&
                    ++ordinal == request.ordinal
            ) {
                if (BotTokenValue.available(ordinary)) {
                    val command =
                        if (specification.reference == TokenOrderReference.FIXED_16_TO_15) {
                            continuation.ordinaryCommand(session, lineup.seat, ordinary)
                        } else {
                            requireNotNull(commands[actor]) {
                                "A selected sealed actor requires its baseline command."
                            }
                        }
                    val observation =
                        TokenObservationEncoder.from(
                            session.state.viewForDecision(actor),
                            actor,
                            ordinary,
                            command,
                            TerritoryEncoder.TOKEN_2,
                        )
                    val proposed = TokenOrderPolicy.proposedIndices(ordinary, observation)
                    val reference = specification.reference.index(observation)
                    check(observation.actions[reference].command == commands[actor]) {
                        "The collected reference must equal the frozen continuation controller's choice."
                    }
                    if (proposed.size >= 2) {
                        val root = TokenValueFrozenRoot(session, actor, commands)
                        val alternatives = proposed.map { action ->
                            val chosen = observation.actions[action].command
                            TokenOrderAlternative(
                                action,
                                chosen,
                                List(specification.continuationReplicates) { replicate ->
                                    val chance =
                                        specification.continuationSeed(lineup.seed, replicate)
                                    continuation.finish(
                                        root.branch(chosen, chance, deadlineNanos),
                                        lineup.seat,
                                        lineup.configuration.id,
                                        chance,
                                    )
                                },
                            )
                        }
                        return provenance(TokenValueOriginStatus.COLLECTED)
                            .copy(
                                actualEpoch = request.epoch,
                                turn = session.state.turnNumber,
                                rootDecision = session.decisions,
                                baselineCommand = command,
                                referenceIndex =
                                    reference.takeIf {
                                        specification.reference ==
                                            TokenOrderReference.FIXED_16_TO_15
                                    },
                                observation = observation,
                                proposedIndices = proposed,
                                alternatives = alternatives,
                            )
                    }
                }
                skip = TokenValueSkipReason.PRECOMMITTED_ROOT_INELIGIBLE
            }
            session.apply(commands, deadlineNanos)
        }
        return provenance(TokenValueOriginStatus.SKIPPED, skip)
    }
}
