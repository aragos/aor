package dev.aragos.aor.training

import java.nio.file.Path

/**
 * Two unchanged raw-reference regressions; these are not supported mixed-ecology strength games.
 */
internal object CoordinatedWinHistorical {
    fun validate(subject: TokenPairedSubject, protocol: CoordinatedWinProtocol) {
        subject.verify()
        val node = PurchaseJson.read(subject.path)
        require(
            node["kind"]?.asText() == "coordinated-win-historical-admission-1" &&
                node["complete"]?.booleanValue() == true &&
                node["passed"]?.booleanValue() == true &&
                node["protocolSha256"]?.asText() == protocol.source.sha256 &&
                node["scopeSha256"]?.asText() == protocol.scopeSha256 &&
                node["ecologySha256"]?.asText() == protocol.ecologySha256 &&
                node["originalBundleSha256"]?.asText() == protocol.space["originalBundleSha256"] &&
                node["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                node["scientificTerminalExecutions"]?.asInt() == 2 &&
                node["attemptedGames"]?.asInt() == 2 &&
                node["completedGames"]?.asInt() == 2 &&
                node["zeroFailures"]?.booleanValue() == true
        ) {
            "Both exact raw-reference regressions must complete before any fresh supported game."
        }
        val rows = PurchaseJson.array(node["controls"], "historical controls")
        require(rows.size == 2) { "Exactly two fixed historical controls are required." }
        rows.forEachIndexed { index, row ->
            val declared = protocol.record["historicalControls"][index]
            require(
                row["globalIndex"] == declared["globalIndex"] &&
                    row["index"] == declared["index"] &&
                    row["side"] == declared["side"] &&
                    row["purpose"]?.asText() == "RAW_REFERENCE_REGRESSION_ONLY" &&
                    row["originalSourceSha256"] == declared["sourceSha256"] &&
                    row["originalPairSha256"] == declared["pairSha256"] &&
                    row["originalReplaySha256"] == declared["replaySha256"] &&
                    row["canonicalVerified"]?.booleanValue() == true &&
                    row["execution"]?.asText() == "ZERO_REFERENCE"
            ) {
                "Historical evidence must retain the exact declared global/local positions and original raw subjects."
            }
            CoordinatedEsWire.sha(row["replaySha256"], "historical reproduction")
        }
    }

    fun run(source: TokenPairedSubject, output: Path, deadline: Long) {
        val node = PurchaseJson.read(source.path)
        PurchaseJson.fields(
            node,
            setOf(
                "kind",
                "host",
                "protocol",
                "arena",
                "originalBundle",
                "warmStart",
                "originProof",
                "inheritedCalibration",
                "controls",
            ),
        )
        require(
            node["kind"]?.asText() == "coordinated-win-historical-source-1" &&
                node["host"]?.asText() == "DESKTOP"
        ) {
            "Only the explicit Desktop raw-reference historical source is admitted."
        }
        fun subject(name: String) = TokenPairedSubject.read(node.required(name), source.path.parent)
        val protocol = CoordinatedWinProtocol.read(subject("protocol"))
        protocol.warm(subject("warmStart"))
        val origin =
            CoordinatedWinOrigin.read(subject("originProof"), protocol, QualificationHost.DESKTOP)
        require(
            subject("arena").path == origin.scope.arenaSource.path &&
                subject("originalBundle").sha256 == origin.scope.original.source.sha256
        ) {
            "Historical execution retains the exact original raw arena and original parameter container."
        }
        CoordinatedWinCalibration.read(
            subject("inheritedCalibration"),
            protocol,
            origin.scope.original,
        )
        val controls = PurchaseJson.array(node["controls"], "historical controls")
        require(controls.size == 2) { "Exactly two original historical records must be supplied." }
        val artifacts = TrainingArtifacts(output)
        val attempts = CoordinatedEsAttempts(deadline)
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        try {
            val records = controls.mapIndexed { index, control ->
                val declared = protocol.record["historicalControls"][index]
                require(
                    control["globalIndex"] == declared["globalIndex"] &&
                        control["index"] == declared["index"] &&
                        control["side"] == declared["side"] &&
                        control["source"]["sha256"] == declared["sourceSha256"] &&
                        control["pair"]["sha256"] == declared["pairSha256"] &&
                        control["replay"]["sha256"] == declared["replaySha256"]
                ) {
                    "The historical source must bind the fixed raw-reference subjects before execution."
                }
                val oldDeclaration =
                    PurchaseJson.mapper.createObjectNode().also {
                        it.set<com.fasterxml.jackson.databind.JsonNode>(
                            "index",
                            declared["globalIndex"],
                        )
                        it.set<com.fasterxml.jackson.databind.JsonNode>("side", declared["side"])
                    }
                CoordinatedEsHistorical.executeControl(
                    source,
                    output,
                    origin.scope,
                    attempts,
                    index,
                    control,
                    oldDeclaration,
                ) + mapOf("purpose" to "RAW_REFERENCE_REGRESSION_ONLY")
            }
            source.verify()
            origin.pins.forEach { (path, hash) ->
                require(PurchaseJson.hash(Path.of(path)) == hash) {
                    "An original historical subject changed: $path."
                }
            }
            artifacts.write(
                "historical-admission.json",
                mapOf(
                    "kind" to "coordinated-win-historical-admission-1",
                    "passed" to true,
                    "complete" to true,
                    "protocolSha256" to protocol.source.sha256,
                    "scopeSha256" to protocol.scopeSha256,
                    "ecologySha256" to protocol.ecologySha256,
                    "originalBundleSha256" to origin.scope.original.source.sha256,
                    "warmStartSha256" to protocol.warmStartSha256,
                    "sourceSha256" to source.sha256,
                    "scientificTerminalExecutions" to 2,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                    "zeroFailures" to true,
                    "controls" to records,
                ),
            )
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf(
                    "sourceSha256" to source.sha256,
                    "attemptedGames" to attempts.attemptedGames,
                    "completedGames" to attempts.completedGames,
                    "failure" to failure.toString(),
                ),
            )
            throw failure
        }
    }
}
