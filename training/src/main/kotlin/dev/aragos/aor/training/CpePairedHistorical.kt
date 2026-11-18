package dev.aragos.aor.training

import java.nio.file.Path

/**
 * Desktop-only original recorded controls; their compact gate is portable without old host paths.
 */
internal object CpePairedHistorical {
    fun run(source: Path, output: Path, deadline: Long) {
        val sourceHash = PurchaseJson.hash(source)
        val node = PurchaseJson.read(source)
        PurchaseJson.fields(node, setOf("kind", "arena", "admission", "controls"))
        require(node["kind"]?.asText() == "cpe-paired-historical-source-1") {
            "Use the separate historical proof source."
        }
        val admission =
            CpePairedAdmission.read(
                TokenPairedSubject.read(node.required("admission"), source.parent)
            )
        val raw =
            CpePairedScreenInput.readRawArena(
                TokenPairedSubject.read(node.required("arena"), source.parent),
                admission,
                screen = false,
            )
        val artifacts = TrainingArtifacts(output)
        val records = ThreeRoleHistorical.records(source, node, raw, output, deadline)
        require(PurchaseJson.hash(source) == sourceHash) {
            "Historical source changed during its fixed controls."
        }
        admission.verify()
        artifacts.write(
            "historical-admission.json",
            mapOf(
                "kind" to "cpe-paired-historical-admission-1",
                "complete" to true,
                "controls" to 2,
                "zeroFailures" to true,
                "protocolSha256" to admission.protocol.sha256,
                "bundleScopeSha256" to admission.bundleScopeSha256,
                "ecologySha256" to admission.ecologySha256,
                "sourceSha256" to sourceHash,
                "scientificTerminalExecutions" to 2,
                "records" to records,
            ),
        )
    }
}
