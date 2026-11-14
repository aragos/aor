package dev.aragos.aor.training

/**
 * An execution envelope references the unchanged old protocol/seal; it never relabels that seal.
 */
internal class CoordinatedWinCalibration
private constructor(
    val source: TokenPairedSubject,
    val calibration: CoordinatedEsCalibration,
    val pins: Map<String, String>,
) {
    companion object {
        fun read(
            source: TokenPairedSubject,
            protocol: CoordinatedWinProtocol,
            original: CoordinatedEsBundle,
        ): CoordinatedWinCalibration {
            source.verify()
            val value = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                value,
                setOf(
                    "kind",
                    "protocolSha256",
                    "parameterSpace",
                    "warmStartSha256",
                    "originProtocol",
                    "originCalibration",
                    "recipe",
                    "scales",
                    "recalibrated",
                ),
            )
            require(
                value["kind"]?.asText() == "coordinated-es-inherited-calibration-1" &&
                    value["protocolSha256"]?.asText() == protocol.source.sha256 &&
                    value["parameterSpace"] == ExpansionOnPolicyStore.wire(protocol.space) &&
                    value["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                    value["recipe"] == protocol.record["calibration"]["recipe"] &&
                    value["scales"] == protocol.record["calibration"]["scales"] &&
                    value["recalibrated"]?.isBoolean == true &&
                    !value["recalibrated"].booleanValue()
            ) {
                "Only the explicit unchanged-calibration envelope for this warm continuation may execute."
            }
            fun inherited(name: String): TokenPairedSubject {
                val item = value.required(name)
                PurchaseJson.fields(item, setOf("path", "sha256"))
                val path =
                    java.nio.file.Path.of(
                        PurchaseJson.text(item["path"], "inherited relative path")
                    )
                require(
                    !path.isAbsolute &&
                        path.normalize() == path &&
                        source.path.parent
                            .resolve(path)
                            .toRealPath()
                            .startsWith(source.path.parent.toRealPath())
                ) {
                    "Inherited original subjects must remain inside their exact portable envelope directory."
                }
                return TokenPairedSubject.read(item, source.path.parent)
            }
            val oldProtocol = inherited("originProtocol")
            val oldSeal = inherited("originCalibration")
            require(
                oldProtocol.sha256 ==
                    protocol.record["calibration"]["originProtocolSha256"].asText() &&
                    oldSeal.sha256 ==
                        protocol.record["calibration"]["originCalibrationSha256"].asText()
            ) {
                "The inherited envelope must retain the exact original raw protocol and calibration bytes."
            }
            val oldPlan = PurchaseJson.read(oldProtocol.path)
            val calibration = CoordinatedEsCalibration.read(oldSeal, original)
            calibration.version.validateProtocol(oldPlan)
            require(
                calibration.record["protocolSha256"]?.asText() == oldProtocol.sha256 &&
                    calibration.record["projectionSha256"]?.asText() ==
                        protocol.space["projectionSha256"] &&
                    calibration.record["scales"] == value["scales"] &&
                    oldPlan["calibration"] == value["recipe"]
            ) {
                "Inherited scales, version and original projection must remain byte-admitted and exact."
            }
            return CoordinatedWinCalibration(
                source,
                calibration,
                listOf(source, oldProtocol, oldSeal).associate { it.path.toString() to it.sha256 },
            )
        }
    }
}
