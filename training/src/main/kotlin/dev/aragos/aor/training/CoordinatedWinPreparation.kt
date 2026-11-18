package dev.aragos.aor.training

/**
 * Seventeen small immutable receipts bind every prepared direction; foreign input paths are
 * provenance.
 */
internal class CoordinatedWinPreparation
private constructor(
    val source: TokenPairedSubject,
    val directionHashes: Map<Int, String>,
    val pins: Map<String, String>,
) {
    companion object {
        fun read(
            source: TokenPairedSubject,
            protocol: CoordinatedWinProtocol,
        ): CoordinatedWinPreparation {
            source.verify()
            val value = PurchaseJson.read(source.path)
            require(
                value["kind"]?.asText() == "coordinated-win-numeric-complete-1" &&
                    value["verb"]?.asText() == "prepare" &&
                    value["passed"]?.booleanValue() == true &&
                    value["complete"]?.booleanValue() == true &&
                    value["protocolSha256"]?.asText() == protocol.source.sha256 &&
                    value["execution"] ==
                        ExpansionOnPolicyStore.wire(
                            mapOf("newGames" to 0, "actualFits" to 0, "optimizerSteps" to 0)
                        )
            ) {
                "Only the exact zero-game completed numerical preparation may supply directions."
            }
            val receipts =
                (1..16).associateWith { number ->
                    val name =
                        "directions/generation-${number.toString().padStart(2, '0')}-generation.json"
                    val path = source.path.parent.resolve(name).toRealPath()
                    require(path.startsWith(source.path.parent.toRealPath())) {
                        "Prepared receipts must remain inside their publication."
                    }
                    TokenPairedSubject(
                            path,
                            CoordinatedEsWire.sha(
                                value["artifacts"]?.get(name),
                                "prepared receipt",
                            ),
                            path.toString(),
                        )
                        .also(TokenPairedSubject::verify)
                }
            val hashes = receipts.mapValues { (number, subject) ->
                val row = PurchaseJson.read(subject.path)
                val table = "directions/generation-${number.toString().padStart(2, '0')}.json"
                val hash =
                    CoordinatedEsWire.sha(
                        value["artifacts"]?.get(table),
                        "prepared direction table",
                    )
                require(
                    row["kind"]?.asText() == "coordinated-es-direction-generation-1" &&
                        PurchaseJson.integer(row["generation"], "prepared generation") == number &&
                        row["seed"] == protocol.generation(number)["directionSeed"] &&
                        row["generator"]?.asText() == "numpy-PCG64" &&
                        row["distribution"]?.asText() == "independent-standard-normal" &&
                        row["shape"] == ExpansionOnPolicyStore.wire(listOf(16, 256)) &&
                        row["renormalized"]?.isBoolean == true &&
                        !row["renormalized"].booleanValue() &&
                        row["directionsSha256"]?.asText() == hash
                ) {
                    "Each preparation receipt must bind its exact declared stream and unnormalized16x256 table."
                }
                hash
            }
            return CoordinatedWinPreparation(
                source,
                hashes,
                receipts.values.associate { it.path.toString() to it.sha256 } +
                    (source.path.toString() to source.sha256),
            )
        }
    }
}
