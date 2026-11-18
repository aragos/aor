package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotModel
import java.nio.file.Path

/** Optional evaluated-seat complete C/P/E bundle; the unchanged reference retains every rival. */
class ArenaCpeBundle
private constructor(
    private val roles: Map<ArenaSide, CpePairedBundle>,
    private val baseline: PurchaseBaselineInput,
) {
    internal fun validate(arena: ArenaSpecification) {
        require(
            arena.jointPolicy != null &&
                arena.tokenOrder != null &&
                arena.expansionImitation != null &&
                arena.expansionActor == null &&
                arena.tokenActor == null
        ) {
            "C/P/E evaluation requires complete G3/E/T references and no other learned overlay."
        }
        val expected = CpePairedDictionary.forConfiguration(arena.configurations.single())
        require(roles.isNotEmpty() && roles.values.all { it.dictionaries == expected }) {
            "Every evaluated C/P/E bundle must preserve the complete supported dictionaries."
        }
        val joint = PurchaseJson.mapper.valueToTree<JsonNode>(arena.jointPolicy.binding())
        require(joint["baseline"]["sha256"]?.asText() == baseline.sha256) {
            "C/P/E evaluation must retain the original frozen F0 purchase baseline."
        }
    }

    internal fun controllers(
        original: Map<Int, SimulationController>,
        seat: Int,
        side: ArenaSide?,
        ordinary: BotModel,
    ): Map<Int, SimulationController> {
        require(side != null) { "A complete C/P/E overlay requires an explicit evaluated side." }
        val bundle = roles[side] ?: return original
        val reference =
            requireNotNull(original[seat]) {
                "The complete G3/E/T reference must precede the C/P/E overlay."
            }
        return original +
            (seat to
                CpePairedController(
                    reference,
                    ordinary,
                    baseline.model,
                    bundle.models,
                    bundle.models,
                    CpeHead.CARD,
                ))
    }

    internal fun identity(): String =
        "$VERSION:${baseline.sha256}:" +
            roles.entries.joinToString(":") { (side, bundle) -> "$side=${bundle.source.sha256}" }

    internal fun binding(): Map<String, Any> =
        mapOf(
            "version" to VERSION,
            "reference" to "complete-joint-token-expansion-composition",
            "baseline" to
                mapOf(
                    "path" to baseline.path.toString(),
                    "sha256" to baseline.sha256,
                    "snapshot" to "cpe-purchase-baseline.json",
                ),
            "roles" to
                roles.entries.associate { (side, bundle) ->
                    side.name to
                        (bundle.source.metadata("cpe-${side.name.lowercase()}/bundle.json") +
                            mapOf(
                                "heads" to
                                    CpeHead.entries.associate { head ->
                                        head.name to
                                            bundle
                                                .subject(head)
                                                .metadata(
                                                    "cpe-${side.name.lowercase()}/${head.name.lowercase()}.json"
                                                )
                                    },
                                "identity" to bundle.identity(),
                            ))
                },
        )

    internal fun metadata(): Map<String, Any> = mapOf("cpeBundle" to binding())

    internal fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(baseline.path, baseline.sha256, "cpe-purchase-baseline.json")
        roles.forEach { (side, bundle) ->
            bundle.snapshot(artifacts, "cpe-${side.name.lowercase()}")
        }
    }

    companion object {
        const val VERSION = "cpe-bundle-arena-1"

        internal fun from(
            final: CpePairedBundle,
            original: CpePairedBundle,
            baseline: PurchaseBaselineInput,
        ): ArenaCpeBundle =
            ArenaCpeBundle(
                mapOf(ArenaSide.CANDIDATE to final, ArenaSide.INCUMBENT to original),
                baseline,
            )

        internal fun read(
            source: Path,
            node: JsonNode,
            configurations: List<TrainingConfiguration>,
        ): ArenaCpeBundle {
            PurchaseJson.fields(node, setOf("version", "baseline"), setOf("candidate", "incumbent"))
            require(node["version"]?.asText() == VERSION && configurations.size == 1) {
                "Declare the explicit single-configuration C/P/E arena overlay."
            }
            val dictionaries = CpePairedDictionary.forConfiguration(configurations.single())
            val baselineSubject =
                TokenPairedSubject.read(node["baseline"], source.toAbsolutePath().parent)
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
                            side to
                                CpePairedBundle.read(
                                    TokenPairedSubject.read(value, source.toAbsolutePath().parent),
                                    dictionaries,
                                )
                        }
                    }
                    .toMap()
            baselineSubject.verify()
            require(roles.isNotEmpty()) { "Declare at least one complete evaluated bundle." }
            return ArenaCpeBundle(roles, baseline)
        }
    }
}
