package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import java.nio.file.Path
import kotlin.random.Random

/**
 * One prospective first eligible pass per origin's predeclared epoch; skipped games are not
 * replaced.
 */
internal data class ExpansionOpportunitySpecification(
    val arena: ArenaSpecification,
    val diagnosticRoots: Int = 128,
    val continuationReplicates: Int = 2,
) {
    init {
        require(arena.purpose == ArenaPurpose.SCREENING && arena.configurations.size == 1) {
            "Expansion diagnostics require exactly one SCREENING configuration."
        }
        require(arena.candidate == arena.incumbent && arena.tokenOrder == null) {
            "Expansion diagnostics require identical ordinary baselines and no token overlays."
        }
        require(diagnosticRoots in 1..128 && continuationReplicates == 2) {
            "Expansion diagnostics require 1–128 prospective origins and exactly two future streams."
        }
        requireNotNull(arena.frozenPurchase) {
                "Expansion diagnostics require a shared frozen Purchase environment."
            }
            .controllers(arena.configurations.single().playerCount, 0, side = null)
    }

    fun lineup(index: Int): StrategyArena.Lineup =
        StrategyArena.lineup(arena.copy(pairsPerConfiguration = diagnosticRoots), index)

    fun epoch(index: Int): Int {
        require(index in 0 until diagnosticRoots) {
            "Expansion origin index must belong to its bank."
        }
        return index % 3 + 1
    }

    fun identity(): String =
        listOf(
                arena.identity(),
                diagnosticRoots,
                continuationReplicates,
                TerritoryEncoder.EXPANSION_2.version,
                ExpansionOpportunityProposals.VERSION,
                "first-eligible-pass-in-predeclared-epoch-1",
            )
            .joinToString("\n")

    companion object {
        fun read(source: Path): ExpansionOpportunitySpecification {
            val node = PurchaseJson.read(source)
            return ExpansionOpportunitySpecification(
                ArenaSpecification.read(source),
                node["diagnosticRoots"]?.let { PurchaseJson.integer(it, "diagnosticRoots") } ?: 128,
                node["continuationReplicates"]?.let {
                    PurchaseJson.integer(it, "continuationReplicates")
                } ?: 2,
            )
        }
    }
}

/** Family-balanced public proposal sampling, without scores, successor worlds or outcome labels. */
internal object ExpansionOpportunityProposals {
    const val VERSION = "expansion-opportunity-public-families-1"
    private val families = listOf("place", "attack", "reduce", "buyCard")

    fun seed(originSeed: Int): Int = originSeed xor 0x5e1a93c7

    fun family(observation: TerritoryObservation, action: Int): String =
        if (action == observation.baselineIndex) "pass"
        else
            requireNotNull(
                families.singleOrNull { observation.actions[action].features["kind.$it"] == 1.0 }
            ) {
                "An offered expansion alternative must have exactly one known action family."
            }

    fun indices(observation: TerritoryObservation, originSeed: Int): List<Int> {
        require(
            observation.encoderVersion == TerritoryEncoder.EXPANSION_2.version &&
                observation.actions[observation.baselineIndex].command == "pass"
        ) {
            "Expansion opportunities require a version 2 observation with actual pass baseline."
        }
        val groups =
            observation.actions.indices
                .filter { it != observation.baselineIndex }
                .groupBy { family(observation, it) }
        val random = Random(seed(originSeed))
        return listOf(observation.baselineIndex) +
            groups.keys.sorted().shuffled(random).take(3).map { kind ->
                requireNotNull(groups[kind]) {
                        "A sampled expansion family retains offered actions."
                    }
                    .sortedBy { observation.actions[it].command }
                    .random(random)
            }
    }

    fun eligible(
        view: GameView,
        actor: PlayerId,
        observation: BotObservation,
        command: String,
    ): Boolean =
        view.stage == Stage.EXPANSION &&
            observation.decision == "expand" &&
            command == "pass" &&
            view.players.single { it.id == actor }.poolTokens > 0 &&
            observation.candidates.any { it.command != "pass" }
}

internal enum class ExpansionOpportunityStatus {
    COLLECTED,
    SKIPPED,
}

internal enum class ExpansionOpportunitySkip {
    REQUESTED_EPOCH_NOT_REACHED,
    NO_ELIGIBLE_PASS,
}

/**
 * Synthetic full canonical histories are diagnostic evidence, never observation features or labels.
 */
internal data class ExpansionOpportunityOutcome(
    val terminal: TokenValueOutcome,
    val replayEntries: List<String>,
    val replaySha256: String,
    val replayVerified: Boolean = true,
)

internal data class ExpansionOpportunityAlternative(
    val index: Int,
    val command: String,
    val family: String,
    val outcomes: List<ExpansionOpportunityOutcome>,
    val pairedRewardDifferences: List<Double>,
    val pairedWinShareDifferences: List<Double>,
    val meanRewardDifference: Double,
    val meanWinShareDifference: Double,
)

/**
 * Origin is the statistical unit; alternative means remain separate and never choose a best action.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ExpansionOpportunityOrigin(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val seat: Int,
    val opponentIndices: List<Int>,
    val requestedEpoch: Int,
    val proposalSeed: Int,
    val originDecisions: Int,
    val status: ExpansionOpportunityStatus,
    val skipReason: ExpansionOpportunitySkip? = null,
    val actualEpoch: Int? = null,
    val turn: Int? = null,
    val rootDecision: Int? = null,
    val poolTokens: Int? = null,
    val baselineCommand: String? = null,
    val observation: TerritoryObservation? = null,
    val proposedIndices: List<Int>? = null,
    val header: GameHeader? = null,
    val prefixEntries: List<String>? = null,
    val prefixSha256: String? = null,
    val alternatives: List<ExpansionOpportunityAlternative>? = null,
    val meanAlternativeRewardDifference: Double? = null,
    val meanAlternativeWinShareDifference: Double? = null,
)

/** Full-game prefix selection followed by at most eight complete tails from one unchanged root. */
internal class ExpansionOpportunityCollector(
    private val specification: ExpansionOpportunitySpecification,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): ExpansionOpportunityOrigin {
        checkTokenValueDeadline(deadlineNanos)
        val lineup = specification.lineup(index)
        val epoch = specification.epoch(index)
        val models =
            lineup.opponentIndices.mapIndexed { seat, opponent ->
                if (seat == lineup.seat) specification.arena.candidate
                else specification.arena.opponents[opponent]
            }
        val purchase =
            requireNotNull(specification.arena.frozenPurchase) {
                    "A validated expansion diagnostic retains its shared Purchase environment."
                }
                .controllers(models.size, lineup.seat, side = null)
        val continuation = FrozenPolicyContinuation(models, purchase, lineup.seed, deadlineNanos)
        val session =
            ExperimentSession.start(
                lineup.configuration,
                lineup.seed.toLong(),
                recordEntries = true,
            )
        val actor = session.state.playerIds[lineup.seat]
        var reached = false
        while (
            session.state.stage != Stage.FINISHED && session.state.currentEpoch.number <= epoch
        ) {
            continuation.check(session)
            reached = reached || session.state.currentEpoch.number == epoch
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            val observation = observations[actor]
            val view = if (observation != null) session.state.viewForDecision(actor) else null
            if (
                session.state.currentEpoch.number == epoch &&
                    observation != null &&
                    view != null &&
                    ExpansionOpportunityProposals.eligible(
                        view,
                        actor,
                        observation,
                        commands[actor] ?: "",
                    )
            ) {
                return branch(index, lineup, session, actor, commands, observation, continuation)
            }
            session.apply(commands, deadlineNanos)
        }
        return identity(index, lineup, session)
            .copy(
                status = ExpansionOpportunityStatus.SKIPPED,
                skipReason =
                    if (reached) ExpansionOpportunitySkip.NO_ELIGIBLE_PASS
                    else ExpansionOpportunitySkip.REQUESTED_EPOCH_NOT_REACHED,
            )
    }

    private fun identity(index: Int, lineup: StrategyArena.Lineup, session: ExperimentSession) =
        ExpansionOpportunityOrigin(
            signature = signature,
            index = index,
            configuration = lineup.configuration.id,
            gameSeed = lineup.seed,
            seat = lineup.seat,
            opponentIndices = lineup.opponentIndices,
            requestedEpoch = specification.epoch(index),
            proposalSeed = ExpansionOpportunityProposals.seed(lineup.seed),
            originDecisions = session.decisions,
            status = ExpansionOpportunityStatus.COLLECTED,
        )

    private fun branch(
        index: Int,
        lineup: StrategyArena.Lineup,
        session: ExperimentSession,
        actor: PlayerId,
        commands: Map<PlayerId, String>,
        ordinary: BotObservation,
        continuation: FrozenPolicyContinuation,
    ): ExpansionOpportunityOrigin {
        val rootState = session.state
        val view = rootState.viewForDecision(actor)
        val observation =
            ExpansionObservationEncoder.from(
                view,
                actor,
                ordinary,
                "pass",
                TerritoryEncoder.EXPANSION_2,
            )
        val proposed = ExpansionOpportunityProposals.indices(observation, lineup.seed)
        check(proposed.size in 2..4) {
            "A captured expansion opportunity must retain baseline plus at most three alternatives."
        }
        val prefix = session.entries.map(Entry::toText)
        val outcomes = proposed.map { action ->
            List(specification.continuationReplicates) { replicate ->
                val chance = TokenValueCollector.continuationSeed(lineup.seed, replicate)
                val branch = session.fork(chance)
                branch.apply(
                    commands + (actor to observation.actions[action].command),
                    deadlineNanos,
                )
                val terminal =
                    continuation.finish(branch, lineup.seat, lineup.configuration.id, chance)
                val canonical = branch.entries.map(Entry::toText)
                check(canonical.take(prefix.size) == prefix) {
                    "Every expansion tail must retain the exact canonical prefix."
                }
                val replay =
                    canonical.fold(GameState(rootState.header)) { state, entry ->
                        checkTokenValueDeadline(deadlineNanos)
                        state.step(TextCodec.parse(entry))
                    }
                check(replay == branch.state) {
                    "Expansion canonical prefix and tail must reproduce the complete terminal state."
                }
                ExpansionOpportunityOutcome(
                    terminal,
                    canonical,
                    CardAdvantageExecutor.hash(canonical),
                )
            }
        }
        check(session.state == rootState && session.entries.map(Entry::toText) == prefix) {
            "Expansion continuations must not mutate their shared root or canonical prefix."
        }
        val baseline = outcomes.first()
        val alternatives = proposed.mapIndexed { position, action ->
            val paired = outcomes[position].zip(baseline)
            val rewards = paired.map { (value, reference) ->
                value.terminal.reward - reference.terminal.reward
            }
            val wins = paired.map { (value, reference) ->
                value.terminal.winShare - reference.terminal.winShare
            }
            ExpansionOpportunityAlternative(
                action,
                observation.actions[action].command,
                ExpansionOpportunityProposals.family(observation, action),
                outcomes[position],
                rewards,
                wins,
                rewards.average(),
                wins.average(),
            )
        }
        return identity(index, lineup, session)
            .copy(
                actualEpoch = rootState.currentEpoch.number,
                turn = rootState.turnNumber,
                rootDecision = session.decisions,
                poolTokens = view.players.single { it.id == actor }.poolTokens,
                baselineCommand = "pass",
                observation = observation,
                proposedIndices = proposed,
                header = rootState.header,
                prefixEntries = prefix,
                prefixSha256 = CardAdvantageExecutor.hash(prefix),
                alternatives = alternatives,
                meanAlternativeRewardDifference =
                    alternatives.drop(1).map { it.meanRewardDifference }.average(),
                meanAlternativeWinShareDifference =
                    alternatives.drop(1).map { it.meanWinShareDifference }.average(),
            )
    }
}
