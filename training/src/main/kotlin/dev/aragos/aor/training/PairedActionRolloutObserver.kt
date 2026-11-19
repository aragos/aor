package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Command
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.CommodityTimingConfig
import dev.aragos.aor.ai.CommodityTimingPolicy
import kotlin.random.Random

/** Offline paired continuations from the same simulated deal and permitted actor observation. */
internal class PairedActionRolloutObserver(
    override val seat: Int,
    private val timing: CommodityTimingConfig? = null,
    private val compareTerritory: Boolean = false,
    private val traceLookahead: Boolean = false,
    private val territoryEpoch: Int? = null,
    private val compareChance: Boolean = false,
    private val shortTrainingFutures: Int = 0,
    private val resampledDrawStudy: Boolean = false,
    private val reliefUnlock: Boolean = false,
    private val commodityExpansion: Boolean = false,
    private val compareRivalDenial: Boolean = false,
    private val reserveExpansionCard: Boolean = false,
) : SimulationStateObserver {
    private data class Root(
        val state: GameState,
        val number: Int,
        val chosen: String,
        val actions: Map<String, String>,
        val context: Map<String, Any>,
        val commodityPlan: CommodityExpansionPlan? = null,
    )

    private var root: Root? = null
    val evidenceKey: String
        get() =
            when {
                commodityExpansion -> "commodityExpansionRollouts"
                timing != null -> "commodityRollouts"
                compareTerritory -> "cardTerritoryRollouts"
                reliefUnlock -> "reliefUnlockRollouts"
                compareRivalDenial -> "rivalMarketDenialRollouts"
                reserveExpansionCard -> "reserveExpansionCardRollouts"
                else -> "expansionCardRollouts"
            }

    override fun chosen(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ) {
        if (root != null) return
        root =
            when {
                timing != null -> commodityRoot(state, number, observation, command, timing)
                compareTerritory -> cardTerritoryRoot(state, number, observation, command)
                reliefUnlock -> reliefRoot(state, number, observation, command)
                compareRivalDenial -> denialRoot(state, number, observation, command)
                reserveExpansionCard -> reserveRoot(state, number, observation, command)
                else -> expansionCardRoot(state, number, observation, command)
            }
    }

    private fun commodityRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
        timing: CommodityTimingConfig,
    ): Root? {
        if (state.stage != Stage.PLAY_CARDS) return null
        if (observation.candidates.none { it.command == "pass" }) return null
        val actor = state.playerIds[seat]
        val view = state.viewForDecision(actor)
        val choices =
            CommodityTimingPolicy.opportunities(
                view,
                actor,
                observation,
                timing,
            )
        val selected =
            if (command == "pass") {
                choices.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))
            } else choices.entries.singleOrNull { it.key == command }
        if (selected == null) return null
        val plan =
            if (commodityExpansion) CommodityExpansionPlan.capture(view, actor, selected.key)
            else null
        val ordinaryArms = mapOf("play" to selected.key, "hold" to "pass")
        return Root(
            state,
            number,
            command,
            if (plan == null) ordinaryArms
            else ordinaryArms + mapOf("hold-and-expand" to "pass", "hold-expand-play" to "pass"),
            mapOf("play" to selected.key, "projectedGrowth" to selected.value) +
                (if (plan == null) emptyMap() else mapOf("currentPayout" to plan.currentPayout)),
            plan,
        )
    }

    private fun expansionCardRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ): Root? {
        if (state.stage != Stage.EXPANSION || command !in setOf("pass", "buy-expansion-card"))
            return null
        val actions = mapOf("buy" to "buy-expansion-card", "pass" to "pass")
        if (!actions.values.all { choice -> observation.candidates.any { it.command == choice } })
            return null
        val actor = state.playerIds[seat]
        val view = state.viewForDecision(actor)
        val price =
            requireNotNull(view.expansionCardCost) { "An offered expansion card needs its price." }
        val markets =
            view.expansionOffers.count { offer ->
                offer.establishesMarker && offer.blockers.isEmpty() && !offer.uncertain
            }
        return Root(
            state,
            number,
            command,
            actions,
            mapOf(
                "price" to price,
                "pool" to observation.availableTokens,
                "handSize" to view.hand.size,
                "completePeacefulMarkets" to markets,
                "finalTurn" to view.endingTriggered,
            ),
        )
    }

    private fun denialRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ): Root? {
        if (
            observation.decision != "expand" ||
                (state.turnNumber ?: 0) < 4 ||
                !command.startsWith("attack ")
        )
            return null
        val player = state.playerIds[seat]
        val nominated =
            RivalMarketDenial.from(
                state.viewForDecision(player),
                player,
                observation,
                command,
            ) ?: return null
        return Root(state, number, command, nominated.actions, nominated.facts)
    }

    private fun reserveRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ): Root? {
        if (observation.decision != "expand") return null
        if (!command.startsWith("place ") && !command.startsWith("attack ")) return null
        val actor = state.playerIds[seat]
        val reserve =
            ExpansionCardReserve.from(state.viewForDecision(actor), observation, command)
                ?: return null
        return Root(
            state,
            number,
            command,
            mapOf(
                "ordinary" to command,
                "reserve-then-ordinary" to reserve.alternative,
                "reserve-and-buy" to reserve.alternative,
                "buy-first" to BoardCommand.BuyExpansionCard.toText(),
            ),
            reserve.facts,
        )
    }

    private fun cardTerritoryRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ): Root? {
        if (state.stage != Stage.EXPANSION) return null
        if (territoryEpoch != null && state.currentEpoch.number != territoryEpoch) return null
        if (observation.candidates.none { it.command == "buy-expansion-card" }) return null
        val actor = state.playerIds[seat]
        val move = TextCodec.parseAction(command, state, actor).command
        val destination =
            when (move) {
                is BoardCommand.Place -> move.province
                is BoardCommand.Attack -> move.province
                else -> return null
            }
        val view = state.viewForDecision(actor)
        val offer =
            view.expansionOffers.singleOrNull { it.destination == destination } ?: return null
        val cost = offer.requiredTokens
        if (!offer.establishesMarker || move is BoardCommand.Place && move.tokens != cost)
            return null
        val price =
            requireNotNull(view.expansionCardCost) { "An offered expansion card needs its price." }
        if (price + cost <= observation.availableTokens) return null
        return Root(
            state,
            number,
            command,
            mapOf("buy" to "buy-expansion-card", "territory" to command),
            mapOf(
                "price" to price,
                "territoryTokens" to cost,
                "pool" to observation.availableTokens,
                "handSize" to view.hand.size,
                "attack" to (move is BoardCommand.Attack),
                "recordedChance" to (offer.competitionWinProbability ?: 1.0),
                "finalTurn" to view.endingTriggered,
            ) +
                if (shortTrainingFutures > 0) {
                    val market = observation.candidates.single { it.command == command }
                    val card = observation.candidates.single { it.command == "buy-expansion-card" }
                    val features =
                        observation.context.mapKeys { (name, _) -> "state." + name } +
                            market.features.mapKeys { (name, _) -> "market." + name } +
                            card.features.mapKeys { (name, _) -> "card." + name }
                    mapOf("rootTrainingFeatures" to features)
                } else emptyMap(),
        )
    }

    private fun reliefRoot(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ): Root? {
        if (observation.decision != "purchase-advances" || command != "pass") return null
        val actor = state.playerIds[seat]
        val owner = state.playerState(actor)
        val misery = owner.misery.value ?: return null
        if (misery < 50 || owner.advances.tierCount != 0) return null
        val view = state.viewForDecision(actor)
        if (view.reliefRatingAvailable == 0) return null
        val turn = requireNotNull(state.turnNumber) { "An advance purchase needs an active turn." }
        val alternative =
            observation.candidates
                .asSequence()
                .filter { it.family == "advance" }
                .filter { candidate ->
                    val buy = TextCodec.parseCommand(candidate.command) as? TurnCommand.BuyAdvance
                    buy != null &&
                        owner.advances.acquire(AdvanceId.fromLetter(buy.letter), turn).tierCount ==
                            1
                }
                .minWithOrNull(compareBy({ it.cashCost }, { it.command })) ?: return null
        return Root(
            state,
            number,
            command,
            mapOf(
                "pass" to "pass",
                "purchase" to alternative.command,
                "purchase-and-relieve" to alternative.command,
            ),
            mapOf(
                "misery" to misery,
                "availableRating" to view.reliefRatingAvailable,
                "price" to alternative.cashCost,
                "cash" to owner.cash,
                "buy" to alternative.command,
            ),
        )
    }

    fun evidence(
        configuration: String,
        seed: Int,
        models: List<BotModel>,
        controllers: () -> Map<Int, SimulationController>,
        deadline: Long,
    ): Map<String, Any> {
        val captured =
            root
                ?: return mapOf("status" to "no-eligible-root") +
                    (territoryEpoch?.let { mapOf("requestedEpoch" to it) } ?: emptyMap())
        val actor = captured.state.playerIds[seat]
        val originTurn =
            requireNotNull(captured.state.turnNumber) {
                "A rollout requires an active origin turn."
            }
        val rootFacts =
            mapOf(
                "status" to "paired",
                "decision" to captured.number,
                "turn" to originTurn,
                "epoch" to captured.state.currentEpoch.number,
                "ordinary" to captured.chosen,
            ) + captured.context
        if (resampledDrawStudy) {
            return rootFacts +
                mapOf(
                    "resampledTrainingTails" to
                        shortFutures(
                            captured,
                            actor,
                            originTurn,
                            models,
                            controllers,
                            seed,
                            deadline,
                        )
                )
        }
        fun outcomes(chanceMode: ExperimentChanceMode) =
            captured.actions.mapValues { (arm, action) ->
                (0..1).map { replicate ->
                    val chanceSeed = CardAdvantageExecutor.chanceSeed(seed, replicate)
                    val session =
                        ExperimentSession(
                            captured.state,
                            chanceSeed,
                            recordEntries = true,
                            chanceMode = chanceMode,
                        )
                    session.apply(mapOf(actor to action), deadline)
                    val checkpoints =
                        if (traceLookahead && chanceMode == ExperimentChanceMode.SEQUENTIAL) {
                            mutableMapOf<Int, Map<String, Any>>()
                        } else null
                    val record: ((GameState, Int) -> Unit)? = checkpoints?.let { values ->
                        { state, decisions ->
                            val turn = state.turnNumber ?: originTurn
                            val ahead = turn - originTurn
                            if (ahead in CHECKPOINT_TURNS) {
                                values[ahead] = checkpoint(state, actor, decisions)
                            }
                        }
                    }
                    record?.invoke(session.state, session.decisions)
                    val originalControllers = controllers()
                    val commodityController =
                        captured.commodityPlan
                            ?.takeIf { arm == "hold-and-expand" || arm == "hold-expand-play" }
                            ?.controller(
                                requireNotNull(originalControllers[seat]) {
                                    "A commodity plan needs the actor's reference policy."
                                },
                                actor,
                                originTurn,
                                redeem = arm == "hold-expand-play",
                            )
                    val continuation =
                        FrozenPolicyContinuation(
                            models,
                            when {
                                arm == "purchase-and-relieve" ->
                                    preferRelief(originalControllers, originTurn)
                                arm == "reserve-and-buy" ->
                                    preferExpansionCard(originalControllers, originTurn)
                                commodityController != null ->
                                    originalControllers + (seat to commodityController)
                                else -> originalControllers
                            },
                            seed,
                            deadline,
                            decisionOffset = captured.number,
                        )
                    val terminal =
                        continuation.finish(session, seat, configuration, chanceSeed, record)
                    mapOf(
                        "terminal" to terminal,
                        "canonicalSha256" to session.verifiedContinuation(captured.state, deadline),
                    ) +
                        (commodityController?.let {
                            mapOf(
                                "plannedMarkets" to it.selectedMarkets,
                                "plannedCardPlay" to it.scheduledPlay,
                            )
                        } ?: emptyMap()) +
                        (if (reserveExpansionCard) {
                            val cardReceived =
                                session.entries
                                    .asSequence()
                                    .filterIsInstance<Entry.Action>()
                                    .takeWhile { it.command !is Command.BuyTokens }
                                    .any { action ->
                                        action.actor == actor &&
                                            action.command == BoardCommand.BuyExpansionCard
                                    }
                            mapOf("cardBoughtThisTurn" to cardReceived)
                        } else emptyMap()) +
                        (if (reliefUnlock) {
                            val spaces =
                                session.entries
                                    .asSequence()
                                    .filterIsInstance<Entry.Action>()
                                    .takeWhile { it.command !is Command.BuyTokens }
                                    .filter { it.actor == actor }
                                    .mapNotNull { it.command as? TurnCommand.RelieveMisery }
                                    .sumOf { it.spaces }
                            mapOf("immediateReliefSpaces" to spaces)
                        } else emptyMap()) +
                        if (checkpoints != null) {
                            val atEnd = checkpoint(session.state, actor, session.decisions)
                            mapOf(
                                "checkpoints" to
                                    CHECKPOINT_TURNS.associateWith { horizon ->
                                        checkpoints[horizon] ?: atEnd
                                    }
                            )
                        } else emptyMap()
                }
            }
        return rootFacts +
            mapOf("tails" to outcomes(ExperimentChanceMode.SEQUENTIAL)) +
            (if (compareChance) {
                mapOf("alignedChanceTails" to outcomes(ExperimentChanceMode.BY_TURN_AND_EVENT))
            } else emptyMap()) +
            if (shortTrainingFutures > 0) {
                mapOf(
                    "shortTrainingTails" to
                        shortFutures(
                            captured,
                            actor,
                            originTurn,
                            models,
                            controllers,
                            seed,
                            deadline,
                        )
                )
            } else emptyMap()
    }

    private fun preferExpansionCard(
        original: Map<Int, SimulationController>,
        turn: Int,
    ): Map<Int, SimulationController> {
        val reference =
            checkNotNull(original[seat]) {
                "An expansion-card sequence requires its ordinary continuation."
            }
        val wrapper =
            object : SimulationController by reference {
                private var pending = true

                override fun choose(decision: SimulationDecision): String? {
                    if (
                        pending &&
                            decision.view.turnNumber == turn &&
                            decision.observation.decision == "expand"
                    ) {
                        pending = false
                        val card = BoardCommand.BuyExpansionCard.toText()
                        if (decision.observation.candidates.any { it.command == card }) return card
                    }
                    return reference.choose(decision)
                }
            }
        return original + (seat to wrapper)
    }

    private fun preferRelief(
        policies: Map<Int, SimulationController>,
        turn: Int,
    ): Map<Int, SimulationController> {
        val ordinary =
            requireNotNull(policies[seat]) { "Relief needs the actor's reference policy." }
        val controller =
            object : SimulationController by ordinary {
                override fun controls(decision: String): Boolean =
                    decision == "purchase-relief" || ordinary.controls(decision)

                override fun choose(decision: SimulationDecision): String? {
                    if (
                        decision.observation.decision == "purchase-relief" &&
                            decision.view.turnNumber == turn
                    ) {
                        val spaces =
                            decision.view.reliefOffers
                                .filter { it.affordable }
                                .maxOfOrNull { it.spaces }
                        if (spaces != null) return TurnCommand.RelieveMisery(spaces).toText()
                    }
                    return ordinary
                        .takeIf { it.controls(decision.observation.decision) }
                        ?.choose(decision)
                }
            }
        return policies + (seat to controller)
    }

    private fun shortFutures(
        captured: Root,
        actor: PlayerId,
        originTurn: Int,
        models: List<BotModel>,
        controllers: () -> Map<Int, SimulationController>,
        seed: Int,
        deadline: Long,
    ): Map<String, List<Map<String, Any>>> {
        val visible = if (resampledDrawStudy) captured.state.viewForPlayer(actor) else null
        val replicas =
            List(shortTrainingFutures) { replicate ->
                val shortSeed = shortChanceSeed(seed, replicate)
                val start =
                    if (visible == null) captured.state
                    else {
                        val unseen =
                            captured.state.deck.shuffled(Random(shortSeed xor 0x35E4CA1517L))
                        captured.state.copy(deck = unseen).also { hypothetical ->
                            check(hypothetical.viewForPlayer(actor) == visible) {
                                "Permuting hidden draws must preserve the complete acting-seat view."
                            }
                        }
                    }
                shortSeed to start
            }
        return captured.actions.mapValues { (_, action) ->
            replicas.map { (shortSeed, start) ->
                val session = ExperimentSession(start, shortSeed, recordEntries = true)
                session.apply(mapOf(actor to action), deadline)
                FrozenPolicyContinuation(
                        models,
                        controllers(),
                        seed,
                        deadline,
                        decisionOffset = captured.number,
                    )
                    .reachTurn(session, originTurn + 3)
                mapOf(
                    "checkpoint" to checkpoint(session.state, actor, session.decisions),
                    "canonicalSha256" to session.verifiedContinuation(start, deadline),
                    "seed" to shortSeed,
                ) +
                    if (resampledDrawStudy) {
                        mapOf("drawOrderChanged" to (start.deck != captured.state.deck))
                    } else emptyMap()
            }
        }
    }

    /** Separate positive range from the signed seeds used by the full terminal futures. */
    private fun shortChanceSeed(seed: Int, replicate: Int): Long =
        0x1F70_0000_0000_0000L + ((seed.toLong() and 0xFFFF_FFFFL) shl 4) + replicate

    private fun checkpoint(state: GameState, actor: PlayerId, decisions: Int): Map<String, Any> {
        val view = state.publicView()
        val own = view.players.single { it.id == actor }
        val score = view.scores.single { it.player == actor }
        val rival =
            view.scores.filter { it.player != actor }.mapNotNull { it.total }.maxOrNull() ?: 0
        val markets = view.players.filter { it.id != actor }.maxOf { it.boardMarkers }
        return mapOf(
            "decisions" to decisions,
            "cash" to score.cash,
            "advances" to score.advancePoints,
            "misery" to (score.misery ?: 1000),
            "chaos" to (score.total == null),
            "scoreMargin" to ((score.total ?: -1000) - rival),
            "markers" to own.boardMarkers,
            "markerMargin" to (own.boardMarkers - markets),
            "handCount" to own.handCount,
            "finished" to (state.stage == Stage.FINISHED),
            "finishedWinShare" to if (score.winner) 1.0 / view.scores.count { it.winner } else 0.0,
        )
    }

    private companion object {
        val CHECKPOINT_TURNS = listOf(1, 3)
    }
}
