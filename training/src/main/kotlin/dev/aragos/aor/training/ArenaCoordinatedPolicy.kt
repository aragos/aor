package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.IncumbentResidualSelector
import java.nio.file.Path

internal enum class CoordinatedEsExecution {
    ZERO_REFERENCE,
    CALIBRATED,
}

/** Sealed scale subject is separate from model materialization and from an unscaled probe. */
internal class CoordinatedEsCalibration
private constructor(
    val source: TokenPairedSubject,
    val record: JsonNode,
    val selectors: Map<CoordinatedEsHead, IncumbentResidualSelector>,
) {
    fun verify() = source.verify()

    companion object {
        fun read(
            source: TokenPairedSubject,
            original: CoordinatedEsBundle,
        ): CoordinatedEsCalibration {
            source.verify()
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "passed",
                    "complete",
                    "protocolSha256",
                    "originalBundleSha256",
                    "projectionSha256",
                    "panelSha256",
                    "probePopulationSha256",
                    "directionsSha256",
                    "prescribedMeanSha256",
                    "scales",
                    "heads",
                    "semanticFitnessDecoded",
                    "calibrationAttempts",
                ),
            )
            require(
                node["kind"]?.asText() == "coordinated-es-calibration-1" &&
                    node["passed"]?.booleanValue() == true &&
                    node["complete"]?.booleanValue() == true &&
                    node["semanticFitnessDecoded"]?.isBoolean == true &&
                    !node["semanticFitnessDecoded"].booleanValue() &&
                    PurchaseJson.integer(node["calibrationAttempts"], "calibration attempts") ==
                        1 &&
                    node["originalBundleSha256"]?.asText() == original.source.sha256
            ) {
                "Executable coordinated actors require the single completed outcome-blind original-bundle calibration."
            }
            listOf(
                    "protocolSha256",
                    "projectionSha256",
                    "panelSha256",
                    "probePopulationSha256",
                    "directionsSha256",
                    "prescribedMeanSha256",
                )
                .forEach { key ->
                    require(node[key]?.asText()?.matches(Regex("[0-9a-f]{64}")) == true) {
                        "Calibration $key must bind an exact raw subject."
                    }
                }
            val names = setOf("EXPANSION", "TOKEN")
            PurchaseJson.fields(node.required("scales"), names)
            PurchaseJson.fields(node.required("heads"), names)
            val selectors = names.associate { name ->
                val scale = node["scales"].required(name)
                require(scale.isNumber) { "Calibration scales must be JSON numbers." }
                CoordinatedEsHead.valueOf(name) to IncumbentResidualSelector(scale.doubleValue())
            }
            return CoordinatedEsCalibration(source, node, selectors).also { it.verify() }
        }
    }
}

/** Optional complete four-head role, applied only after every unchanged reference overlay. */
class ArenaCoordinatedPolicy
private constructor(
    private val roles: Map<ArenaSide, CoordinatedEsBundle>,
    internal val original: CoordinatedEsBundle,
    private val baseline: PurchaseBaselineInput,
    private val execution: CoordinatedEsExecution,
    private val calibration: CoordinatedEsCalibration?,
) {
    private val selectors =
        calibration?.selectors
            ?: mapOf(
                CoordinatedEsHead.EXPANSION to IncumbentResidualSelector(1.0),
                CoordinatedEsHead.TOKEN to IncumbentResidualSelector(1.0),
            )

    init {
        require(roles.isNotEmpty()) {
            "A coordinated arena requires at least one explicit evaluated role."
        }
        require((execution == CoordinatedEsExecution.CALIBRATED) == (calibration != null)) {
            "Only a calibrated role may carry executable sealed scales."
        }
        require(
            execution != CoordinatedEsExecution.ZERO_REFERENCE ||
                roles.values.all { it.source.sha256 == original.source.sha256 }
        ) {
            "An uncalibrated historical zero role must use the exact complete original bundle."
        }
        roles.values.forEach { it.models.validateFrozenValues(original.models) }
    }

    internal fun validate(arena: ArenaSpecification) {
        require(
            arena.jointPolicy != null &&
                arena.tokenOrder != null &&
                arena.expansionImitation != null &&
                arena.expansionActor == null &&
                arena.tokenActor == null &&
                arena.cpeBundle == null
        ) {
            "The new complete role requires unchanged G3/E/T references and no competing learned overlay."
        }
        val expected = CoordinatedEsBundle.dictionaries(arena.configurations.single())
        require(
            original.dictionaries == expected && roles.values.all { it.dictionaries == expected }
        ) {
            "Every coordinated role requires the full original dictionaries."
        }
        original.validateOriginal(arena, baseline)
    }

    internal fun controllers(
        previous: Map<Int, SimulationController>,
        seat: Int,
        side: ArenaSide?,
        ordinary: BotModel,
    ): Map<Int, SimulationController> {
        require(side != null) { "A coordinated policy requires its explicit evaluated side." }
        val bundle = roles[side] ?: return previous
        val reference =
            requireNotNull(previous[seat]) { "The full G3/E/T reference must be composed first." }
        return previous +
            (seat to
                CoordinatedEsController(
                    reference,
                    ordinary,
                    baseline.model,
                    bundle.models,
                    original.models,
                    selectors,
                ))
    }

    internal fun identity(): String =
        "$VERSION:$execution:${original.source.sha256}:${baseline.sha256}:" +
            "${calibration?.source?.sha256}:" +
            roles.entries.joinToString(":") { (side, bundle) -> "$side=${bundle.source.sha256}" }

    internal fun binding(): Map<String, Any> =
        mapOf(
            "version" to VERSION,
            "execution" to execution,
            "reference" to "complete-joint-token-expansion-composition",
            "originalBundle" to
                (original.source.metadata("coordinated-original/bundle.json") +
                    mapOf("heads" to original.identity())),
            "baseline" to
                mapOf(
                    "path" to baseline.path.toString(),
                    "sha256" to baseline.sha256,
                    "snapshot" to "coordinated-purchase-baseline.json",
                ),
            "selectors" to
                selectors.entries.associate { (head, selector) ->
                    head.name to
                        mapOf(
                            "kind" to IncumbentResidualSelector.VERSION,
                            "margin" to selector.margin,
                            "scale" to selector.scale,
                        )
                },
            "roles" to
                roles.entries.associate { (side, bundle) ->
                    side.name to
                        (bundle.source.metadata(
                            "coordinated-${side.name.lowercase()}/bundle.json"
                        ) + mapOf("heads" to bundle.identity()))
                },
        ) +
            (calibration?.let {
                mapOf("calibration" to it.source.metadata("coordinated-calibration.json"))
            } ?: emptyMap())

    internal fun metadata(): Map<String, Any> = mapOf("coordinatedEs" to binding())

    internal fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(
            baseline.path,
            baseline.sha256,
            "coordinated-purchase-baseline.json",
        )
        original.snapshot(artifacts, "coordinated-original")
        roles.forEach { (side, bundle) ->
            bundle.snapshot(artifacts, "coordinated-${side.name.lowercase()}")
        }
        calibration?.source?.let {
            artifacts.ensureFileSnapshot(it.path, it.sha256, "coordinated-calibration.json")
        }
    }

    companion object {
        const val VERSION = "coordinated-es-arena-1"

        internal fun zero(
            original: CoordinatedEsBundle,
            baseline: PurchaseBaselineInput,
            side: ArenaSide = ArenaSide.CANDIDATE,
        ) =
            ArenaCoordinatedPolicy(
                mapOf(side to original),
                original,
                baseline,
                CoordinatedEsExecution.ZERO_REFERENCE,
                null,
            )

        internal fun calibrated(
            current: CoordinatedEsBundle,
            original: CoordinatedEsBundle,
            baseline: PurchaseBaselineInput,
            calibration: CoordinatedEsCalibration,
        ) =
            ArenaCoordinatedPolicy(
                mapOf(ArenaSide.CANDIDATE to current),
                original,
                baseline,
                CoordinatedEsExecution.CALIBRATED,
                calibration,
            )

        internal fun read(
            source: Path,
            node: JsonNode,
            configurations: List<TrainingConfiguration>,
        ): ArenaCoordinatedPolicy {
            PurchaseJson.fields(
                node,
                setOf("version", "execution", "originalBundle", "baseline", "calibration"),
                setOf("candidate", "incumbent"),
            )
            require(node["version"]?.asText() == VERSION && configurations.size == 1) {
                "Declare the exact single-configuration coordinated role."
            }
            val dictionaries = CoordinatedEsBundle.dictionaries(configurations.single())
            val original =
                CoordinatedEsBundle.readOriginal(
                    TokenPairedSubject.read(node.required("originalBundle"), source.parent),
                    dictionaries,
                )
            original.requireFrozenContainers()
            val baselineSubject = TokenPairedSubject.read(node.required("baseline"), source.parent)
            val baseline =
                PurchaseBaselineInput(
                    baselineSubject.path,
                    baselineSubject.sha256,
                    TrainingArtifacts.readModel(baselineSubject.path),
                )
            val roles =
                ArenaSide.entries
                    .mapNotNull { side ->
                        node[side.name.lowercase()]?.let { value ->
                            val subject = TokenPairedSubject.read(value, source.parent)
                            side to
                                if (subject.sha256 == original.source.sha256) original
                                else CoordinatedEsBundle.read(subject, dictionaries)
                        }
                    }
                    .toMap()
            val execution =
                CoordinatedEsExecution.valueOf(PurchaseJson.text(node["execution"], "execution"))
            val calibration =
                node.required("calibration").takeUnless(JsonNode::isNull)?.let {
                    CoordinatedEsCalibration.read(
                        TokenPairedSubject.read(it, source.parent),
                        original,
                    )
                }
            baselineSubject.verify()
            return ArenaCoordinatedPolicy(roles, original, baseline, execution, calibration)
        }
    }
}
