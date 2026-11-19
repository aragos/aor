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
internal class CommodityRolloutObserver(
    override val seat: Int,
    private val timing: CommodityTimingConfig,
) : SimulationStateObserver {
    private data class Root(
        val state: GameState,
        val number: Int,
        val offeredPlay: String,
        val chosen: String,
        val projectedGrowth: Double,
    )

    private var root: Root? = null

    override fun chosen(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ) {
        if (root != null || state.stage != Stage.PLAY_CARDS) return
        if (observation.candidates.none { it.command == "pass" }) return
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
        if (selected == null) return
        root = Root(state, number, selected.key, command, selected.value)
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
            mapOf("play" to captured.offeredPlay, "hold" to "pass").mapValues { (_, action) ->
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
                        "The commodity continuation must replay exactly."
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
            "play" to captured.offeredPlay,
            "ordinary" to captured.chosen,
            "projectedGrowth" to captured.projectedGrowth,
            "tails" to tails,
        )
    }
}
