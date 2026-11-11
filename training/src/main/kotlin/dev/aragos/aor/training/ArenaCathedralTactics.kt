package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotCathedralChoice
import dev.aragos.aor.ai.BotCathedralTactics
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy

/** Exact research contract recorded in runs, paired results and canonical replays. */
data class CathedralTacticsConfiguration(
    val version: String = BotCathedralTactics.VERSION,
    val candidateEnabled: Boolean,
    val nonpassRootLimit: Int = 16,
    val followupLimit: Int = 16,
    val victoriesAreLeaves: Boolean = true,
    val diceTriples: Int = 216,
)

/** Actor-visible diagnostics for one sampled replay decision, never a population estimate. */
data class CathedralTacticalDecision(
    val number: Int,
    val incumbent: String,
    val result: BotCathedralChoice,
)

/** Native research opt-in; the complete existing phase controller selects the incumbent first. */
class ArenaCathedralTactics private constructor(private val candidateEnabled: Boolean) {
    internal fun validate(ordinary: BotModel, expansion: ArenaExpansionImitation?) {
        if (!candidateEnabled) return
        require(expansion?.model(ArenaSide.CANDIDATE) != null) {
            "Cathedral research requires the exact candidate expansion overlay."
        }
        require(
            ordinary.weights.keys.none { name ->
                name.startsWith("planning.expansionModel.") ||
                    name.startsWith("planning.tokenOrderControl.") ||
                    name.startsWith("planning.militaryModel.")
            }
        ) {
            "Cathedral research requires native raw model roles, not browser policy gates."
        }
    }

    internal fun identity(): String = "${BotCathedralTactics.VERSION}:candidate=$candidateEnabled"

    internal val configuration: CathedralTacticsConfiguration
        get() = CathedralTacticsConfiguration(candidateEnabled = candidateEnabled)

    internal fun metadata(): Map<String, Any> = mapOf("cathedralTactics" to configuration)

    internal fun wrap(
        controllers: Map<Int, SimulationController>,
        controlledSeat: Int,
        side: ArenaSide?,
        ordinary: BotModel,
        expansion: ArenaExpansionImitation?,
        record: ((CathedralTacticalDecision) -> Unit)? = null,
    ): Map<Int, SimulationController> {
        if (!candidateEnabled || side != ArenaSide.CANDIDATE) return controllers
        val expansionModel =
            requireNotNull(expansion?.model(side)) {
                "Enabled Cathedral research requires its exact expansion model."
            }
        return controllers +
            (controlledSeat to
                CathedralTacticalController(
                    controllers[controlledSeat],
                    ordinary,
                    expansionModel,
                    enabled = true,
                    record = record,
                ))
    }

    companion object {
        internal fun read(node: JsonNode): ArenaCathedralTactics {
            PurchaseJson.fields(node, setOf("version", "candidateEnabled"))
            require(
                node["version"]?.isTextual == true &&
                    node["version"].asText() == BotCathedralTactics.VERSION
            ) {
                "Unsupported Cathedral tactical research version."
            }
            require(node["candidateEnabled"]?.isBoolean == true) {
                "The Cathedral candidate flag must be boolean."
            }
            return ArenaCathedralTactics(node["candidateEnabled"].booleanValue())
        }
    }
}

/** Wraps E/token/purchase/card controllers rather than reconstructing any qualified role. */
internal class CathedralTacticalController(
    private val previous: SimulationController?,
    private val ordinary: BotModel,
    private val expansion: BotModel,
    private val enabled: Boolean,
    private val record: ((CathedralTacticalDecision) -> Unit)? = null,
) : SimulationController {
    override fun controls(decision: String): Boolean =
        previous?.controls(decision) == true || enabled && decision == "expand"

    override fun choose(decision: SimulationDecision): String {
        val incumbent =
            previous?.takeIf { it.controls(decision.observation.decision) }?.choose(decision)
                ?: BotPolicy.choose(
                    decision.observation,
                    BotDifficulty.HARD,
                    decision.seed,
                    ordinary,
                )
        val result =
            BotCathedralTactics.choose(
                decision.view,
                decision.observation,
                incumbent,
                expansion,
                enabled,
            )
        if (decision.observation.decision == "expand") {
            record?.invoke(CathedralTacticalDecision(decision.number, incumbent, result))
        }
        return result.command
    }
}
