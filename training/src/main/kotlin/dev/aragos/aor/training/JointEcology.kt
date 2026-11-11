package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Explicit joint-state research profile; old bundles retain their single-prior default. */
internal class JointEcology(
    val purchaseBaseline: PurchaseBaselineInput,
    private val phases: PurchaseEcology,
    private val transfer: ExperimentalRulesTransfer?,
) {
    val files: List<Path>
        get() = listOf(purchaseBaseline.path) + phases.inputPaths

    fun identity(): String =
        "$VERSION:${purchaseBaseline.sha256}:${phases.identity()}:${transfer?.metadata()}"

    fun metadata(): Map<String, Any> =
        mapOf(
            "jointStateChronology" to VERSION,
            "sharedPurchaseBaseline" to
                mapOf(
                    "path" to purchaseBaseline.path.toString(),
                    "sha256" to purchaseBaseline.sha256,
                    "snapshot" to "shared-purchase-baseline.json",
                ),
        ) + phases.metadata() + transfer?.metadata().orEmpty()

    fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(
            purchaseBaseline.path,
            purchaseBaseline.sha256,
            "shared-purchase-baseline.json",
        )
        phases.snapshot(artifacts)
    }

    fun controllers(
        input: JointCollectionInput,
        index: Int,
        actors: Map<Int, SimulationController>,
    ): Map<Int, SimulationController> =
        phases.controllers(
            input.configuration.playerCount,
            input.seat(index),
            input.opponentIndices(index),
            input.baseline.model,
            List(input.opponents.size) { input.baseline.model },
            actors,
        )

    companion object {
        const val VERSION = "joint-state-chronology-1"
        val FIELDS =
            setOf(
                "jointStateChronology",
                "sharedPurchaseBaseline",
                "experimentalRulesTransfer",
                "continuationProtocol",
                "expansionImitation",
                "tokenOrder",
            )

        fun read(
            source: Path,
            node: JsonNode,
            configuration: TrainingConfiguration,
            ordinary: PurchaseBaselineInput,
            opponentCount: Int,
            games: Int,
        ): JointEcology? {
            if (!node.has("jointStateChronology")) {
                require(FIELDS.none(node::has)) {
                    "Joint ecology requires explicit joint-state chronology opt-in."
                }
                return null
            }
            require(node["jointStateChronology"].asText() == VERSION) {
                "Unsupported joint-state chronology version."
            }
            val transfer = ExperimentalRulesTransfer.read(node["experimentalRulesTransfer"])
            val path =
                source.parent
                    .resolve(
                        PurchaseJson.text(
                            node.required("sharedPurchaseBaseline"),
                            "sharedPurchaseBaseline",
                        )
                    )
                    .normalize()
                    .toRealPath()
            val hash = PurchaseJson.hash(path)
            val prior =
                PurchaseBaselineInput(path, hash, TrainingArtifacts.readModel(path, transfer))
            require(PurchaseJson.hash(path) == hash) {
                "The shared purchase prior changed while reading."
            }
            val phases =
                requireNotNull(
                    PurchaseEcology.read(
                        source,
                        node,
                        configuration,
                        ordinary,
                        List(opponentCount) { ordinary },
                        games,
                        requireReplayIndices = false,
                    )
                ) {
                    "Joint ecology requires complete explicit expansion and token roles."
                }
            return JointEcology(prior, phases, transfer)
        }
    }
}
