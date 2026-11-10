package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.BotTokenValue
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TokenObservationEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import java.nio.file.Path

/** Fresh, precommitted origin banks; validation is model selection, never final confirmation. */
internal data class TokenOrderSpecification(
    val arena: ArenaSpecification,
    val trainingRoots: Int = 96,
    val validationRoots: Int = 32,
    val continuationReplicates: Int = 2,
) {
    init {
        require(arena.purpose == ArenaPurpose.SCREENING && arena.configurations.size == 1) {
            "Token-order collection requires one SCREENING configuration."
        }
        require(arena.candidate == arena.incumbent && arena.tokenOrder == null) {
            "Token-order roots require identical ordinary baselines without a learned token overlay."
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
        require(continuationReplicates == 2) {
            "Token-order collection uses exactly two paired full-tail chance streams."
        }
    }

    val origins: Int
        get() = trainingRoots + validationRoots

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
        listOf(
                arena.identity(),
                trainingRoots,
                validationRoots,
                continuationReplicates,
                TerritoryEncoder.TOKEN_2.version,
                TokenOrderPolicy.PROPOSAL_VERSION,
                TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
            )
            .joinToString("\n")

    companion object {
        fun read(source: Path): TokenOrderSpecification {
            val node = PurchaseJson.read(source)
            fun count(name: String, default: Int): Int =
                node[name]?.let { PurchaseJson.integer(it, name) } ?: default
            return TokenOrderSpecification(
                ArenaSpecification.read(source),
                count("trainingRoots", 96),
                count("validationRoots", 32),
                count("continuationReplicates", 2),
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
    val observation: TerritoryObservation? = null,
    val proposedIndices: List<Int>? = null,
    val alternatives: List<TokenOrderAlternative>? = null,
)

/** Shared controller routing for every prefix/tail; sealed choices use one untouched state. */
internal class TokenOrderContinuation(
    private val models: List<BotModel>,
    private val purchase: Map<Int, SimulationController>,
    private val seed: Int,
    private val deadlineNanos: Long,
) {
    fun check(session: ExperimentSession) {
        checkTokenValueDeadline(deadlineNanos)
        check(session.decisions < 12000) { "A token-order continuation exceeded 12000 decisions." }
    }

    fun commands(
        session: ExperimentSession,
        observations: Map<PlayerId, BotObservation>,
    ): Map<PlayerId, String> =
        observations.entries
            .mapIndexed { actorIndex, (actor, observation) ->
                val seat = session.state.playerIds.indexOf(actor)
                val policySeed = seed xor (session.decisions * 65537 + seat * 8191)
                val controller = purchase[seat]?.takeIf { it.controls(observation.decision) }
                val command =
                    controller?.choose(
                        SimulationDecision(
                            number = session.decisions + actorIndex,
                            seat = seat,
                            seed = policySeed,
                            view = session.state.viewForPlayer(actor),
                            observation = observation,
                            batchStartNumber = session.decisions,
                        )
                    ) ?: BotPolicy.choose(observation, BotDifficulty.HARD, policySeed, models[seat])
                actor to command
            }
            .toMap()

    fun finish(
        session: ExperimentSession,
        seat: Int,
        configuration: String,
        chanceSeed: Long,
    ): TokenValueOutcome {
        while (session.state.stage != Stage.FINISHED) {
            check(session)
            session.apply(
                commands(session, session.actors().associateWith(session::observation)),
                deadlineNanos,
            )
        }
        val game =
            SimulatedGame(
                configuration,
                seed,
                session.decisions,
                session.state.publicView().scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0,
            )
        return TokenValueOutcome(
            game.reward(seat),
            game.winShare(seat),
            game.rankReward(seat),
            game.scores.map(PilotReplayScore::from),
            game.steps,
            chanceSeed,
        )
    }
}

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
        val purchase =
            requireNotNull(specification.arena.frozenPurchase) {
                    "A validated token-order specification retains its frozen Purchase environment."
                }
                .controllers(models.size, lineup.seat, side = null)
        val continuation = TokenOrderContinuation(models, purchase, lineup.seed, deadlineNanos)
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
                        requireNotNull(commands[actor]) {
                            "A selected sealed actor requires its baseline command."
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
                    if (proposed.size >= 2) {
                        val root = TokenValueFrozenRoot(session, actor, commands)
                        val alternatives = proposed.map { action ->
                            val chosen = observation.actions[action].command
                            TokenOrderAlternative(
                                action,
                                chosen,
                                List(specification.continuationReplicates) { replicate ->
                                    val chance =
                                        TokenValueCollector.continuationSeed(lineup.seed, replicate)
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
