package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Materialized actors are loaded once per shard only after the executable calibration seal. */
internal class CoordinatedEsPopulation
private constructor(
    val source: TokenPairedSubject,
    val members: Map<String, CoordinatedEsBundle>,
) {
    companion object {
        fun read(
            source: TokenPairedSubject,
            scope: CoordinatedEsScope,
            protocol: TokenPairedSubject,
            projection: TokenPairedSubject,
            center: TokenPairedSubject,
            directions: TokenPairedSubject,
            calibration: CoordinatedEsCalibration,
            generation: Int,
            bank: CoordinatedEsBank,
        ): CoordinatedEsPopulation {
            source.verify()
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "scope",
                    "generation",
                    "protocolSha256",
                    "materializationSha256",
                    "centerSha256",
                    "directionsSha256",
                    "calibrationSha256",
                    "worldBankSha256",
                    "worldIds",
                    "members",
                ),
            )
            require(
                node["kind"]?.asText() == "coordinated-es-population-1" &&
                    node["scope"] ==
                        PurchaseJson.mapper.valueToTree<JsonNode>(
                            CoordinatedEsWire.scope(scope, projection)
                        ) &&
                    node["generation"]?.asInt() == generation &&
                    node["protocolSha256"]?.asText() == protocol.sha256 &&
                    node["centerSha256"]?.asText() == center.sha256 &&
                    node["directionsSha256"]?.asText() == directions.sha256 &&
                    node["calibrationSha256"]?.asText() == calibration.source.sha256 &&
                    node["worldBankSha256"]?.asText() == bank.sha256 &&
                    node["worldIds"] == PurchaseJson.mapper.valueToTree<JsonNode>((0..255).toList())
            ) {
                "An executable population must bind the exact sealed calibration, center, directions and common worlds."
            }
            val unsealed =
                node.deepCopy<ObjectNode>().also {
                    it.remove(
                        listOf("protocolSha256", "materializationSha256", "calibrationSha256")
                    )
                    it.put("kind", "coordinated-es-materialization-1")
                }
            require(CpePairedDigest.json(unsealed) == node["materializationSha256"]?.asText()) {
                "An executable population must preserve its exact outcome-free materialization."
            }
            if (generation == 1) {
                require(
                    calibration.record["probePopulationSha256"] == node["materializationSha256"] &&
                        calibration.record["directionsSha256"] == node["directionsSha256"]
                ) {
                    "The first executable population must be the sole original calibration probe."
                }
            }
            val records = PurchaseJson.array(node["members"], "population members")
            require(records.size == 32) {
                "An executable population requires all32 original signed mutants."
            }
            // Validate every descriptor identity before decoding any mutant tensor.
            val subjects = records.mapIndexed { index, member ->
                PurchaseJson.fields(
                    member,
                    setOf("direction", "sign", "path", "coordinatesSha256", "bundleSha256"),
                )
                val role = role(index)
                val path = "members/$role/bundle.json"
                require(
                    PurchaseJson.integer(member["direction"], "direction") == index / 2 &&
                        PurchaseJson.integer(member["sign"], "sign") ==
                            if (index % 2 == 0) 1 else -1
                ) {
                    "Population order is PLUS00, MINUS00 through PLUS15, MINUS15."
                }
                require(member["path"]?.asText() == path) {
                    "Member bundles retain their exact portable relative path."
                }
                CoordinatedEsWire.sha(member["coordinatesSha256"], "materialized coordinates")
                val readable = source.path.parent.resolve(path).toRealPath()
                require(readable.startsWith(source.path.parent.toRealPath())) {
                    "Member bundles must remain inside the immutable publication."
                }
                role to
                    TokenPairedSubject(
                            readable,
                            CoordinatedEsWire.sha(member["bundleSha256"], "member bundle"),
                            readable.toString(),
                        )
                        .also(TokenPairedSubject::verify)
            }
            val members = subjects.associate { (role, subject) ->
                role to
                    CoordinatedEsBundle.read(subject, scope.dictionaries).also {
                        it.models.validateFrozenValues(scope.original.models)
                    }
            }
            source.verify()
            return CoordinatedEsPopulation(source, members)
        }

        fun role(index: Int): String {
            require(index in 0..31) { "Mutant index must be in0..31." }
            return "${if (index % 2 == 0) "PLUS" else "MINUS"}-${(index / 2).toString().padStart(2, '0')}"
        }
    }
}
