package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode

/** Complete current mutants share one inherited selector and one original parameter origin. */
internal class CoordinatedWinPopulation
private constructor(
    val source: TokenPairedSubject,
    val members: Map<String, CoordinatedEsBundle>,
) {
    companion object {
        fun read(
            source: TokenPairedSubject,
            protocol: CoordinatedWinProtocol,
            origin: CoordinatedWinOrigin,
            center: TokenPairedSubject,
            directions: TokenPairedSubject,
            calibration: CoordinatedWinCalibration,
            generation: Int,
            bank: CoordinatedEsBank,
        ): CoordinatedWinPopulation {
            source.verify()
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "parameterSpace",
                    "warmStartSha256",
                    "step",
                    "centerSha256",
                    "directionsSha256",
                    "worldBankSha256",
                    "worldIds",
                    "members",
                    "protocolSha256",
                    "materializationSha256",
                    "inheritedCalibrationSha256",
                ),
            )
            require(
                node["kind"]?.asText() == "coordinated-es-continuation-population-1" &&
                    node["parameterSpace"] == ExpansionOnPolicyStore.wire(protocol.space) &&
                    node["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                    PurchaseJson.integer(node["step"], "population step") == generation &&
                    node["protocolSha256"]?.asText() == protocol.source.sha256 &&
                    node["centerSha256"]?.asText() == center.sha256 &&
                    node["directionsSha256"]?.asText() == directions.sha256 &&
                    node["inheritedCalibrationSha256"]?.asText() == calibration.source.sha256 &&
                    node["worldBankSha256"]?.asText() == bank.sha256 &&
                    node["worldIds"] == ExpansionOnPolicyStore.wire((0..255).toList())
            ) {
                "Executable mutants must bind the exact current center, prepared directions, inherited selector and common worlds."
            }
            val unsealed =
                node.deepCopy<ObjectNode>().also {
                    it.remove(
                        listOf(
                            "protocolSha256",
                            "materializationSha256",
                            "inheritedCalibrationSha256",
                        )
                    )
                    it.put("kind", "coordinated-es-continuation-materialization-1")
                }
            require(CpePairedDigest.json(unsealed) == node["materializationSha256"]?.asText()) {
                "The executable population cannot substitute its outcome-free materialization."
            }
            return CoordinatedWinPopulation(
                source,
                CoordinatedEsPopulation.readMembers(
                    source,
                    node,
                    origin.scope.original,
                    origin.scope.dictionaries,
                ),
            )
        }
    }
}
