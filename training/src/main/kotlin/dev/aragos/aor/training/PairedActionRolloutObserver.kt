package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.CommodityTimingConfig
import dev.aragos.aor.ai.CommodityTimingPolicy

/** Offline paired continuations from the same simulated deal and permitted actor observation. */
internal class PairedActionRolloutObserver(
    override val seat: Int,
    private val timing: CommodityTimingConfig? = null,
) : SimulationStateObserver {
    private data class Root(
        val state: GameState,
        val number: Int,
        val chosen: String,
        val actions: Map<String, String>,
        val context: Map<String, Any>,
    )

    private var root: Root? = null
    val evidenceKey: String
        get() = if (timing != null) "commodityRollouts" else "expansionCardRollouts"

    override fun chosen(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ) {
        if (root != null) return
        root =
            if (timing != null) commodityRoot(state, number, observation, command, timing)
            else expansionCardRoot(state, number, observation, command)
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
        val choices =
            CommodityTimingPolicy.opportunities(
                state.viewForDecision(actor),
                actor,
                observation,
                timing,
            )
        val selected =
            if (command == "pass") {
                choices.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))
            } else choices.entries.singleOrNull { it.key == command }
        if (selected == null) return null
        return Root(
            state,
            number,
            command,
            mapOf("play" to selected.key, "hold" to "pass"),
            mapOf("play" to selected.key, "projectedGrowth" to selected.value),
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

    fun evidence(
        configuration: String,
        seed: Int,
        models: List<BotModel>,
        controllers: () -> Map<Int, SimulationController>,
        deadline: Long,
    ): Map<String, Any> {
        val captured = root ?: return mapOf("status" to "no-eligible-root")
        val actor = captured.state.playerIds[seat]
        val tails =
            captured.actions.mapValues { (_, action) ->
                (0..1).map { replicate ->
                    val chanceSeed = CardAdvantageExecutor.chanceSeed(seed, replicate)
                    val session =
                        ExperimentSession(captured.state, chanceSeed, recordEntries = true)
                    session.apply(mapOf(actor to action), deadline)
                    val continuation =
                        FrozenPolicyContinuation(
                            models,
                            controllers(),
                            seed,
                            deadline,
                            decisionOffset = captured.number,
                        )
                    val terminal = continuation.finish(session, seat, configuration, chanceSeed)
                    val canonical = session.entries.map(Entry::toText)
                    val replayed =
                        canonical.fold(captured.state) { state, entry ->
                            checkTokenValueDeadline(deadline)
                            state.step(TextCodec.parse(entry))
                        }
                    check(replayed == session.state) {
                        "The paired continuation must replay exactly."
                    }
                    mapOf(
                        "terminal" to terminal,
                        "canonicalSha256" to CpePairedDigest.json(canonical),
                    )
                }
            }
        return mapOf(
            "status" to "paired",
            "decision" to captured.number,
            "turn" to
                requireNotNull(captured.state.turnNumber) { "A card root needs its live turn." },
            "epoch" to captured.state.currentEpoch.number,
            "ordinary" to captured.chosen,
            "tails" to tails,
        ) + captured.context
    }
}
