package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * Desktop-only original recorded controls; their compact gate is portable without old host paths.
 */
internal object TokenPairedHistorical {
    fun run(source: Path, output: Path, deadline: Long) {
        val sourceHash = PurchaseJson.hash(source)
        val node = PurchaseJson.read(source)
        PurchaseJson.fields(node, setOf("kind", "arena", "admission", "controls"))
        require(node["kind"]?.asText() == "token-paired-historical-source-1") {
            "Use the separate historical proof source."
        }
        val admission =
            TokenPairedAdmission.read(
                TokenPairedSubject.read(node.required("admission"), source.parent)
            )
        val raw =
            TokenPairedScreenInput.readRawArena(
                TokenPairedSubject.read(node.required("arena"), source.parent),
                admission,
                screen = false,
            )
        val controls = PurchaseJson.array(node["controls"], "controls")
        require(controls.size == 2) {
            "Exactly the two prospective raw-G3 historical controls are permitted."
        }
        val artifacts = TrainingArtifacts(output)
        val records = controls.mapIndexed { ordinal, control ->
            PurchaseJson.fields(control, setOf("source", "pair", "replay", "index", "side"))
            val arenaSource = TokenPairedSubject.read(control.required("source"), source.parent)
            val pair = TokenPairedSubject.read(control.required("pair"), source.parent)
            val expected = TokenPairedSubject.read(control.required("replay"), source.parent)
            val index = PurchaseJson.integer(control["index"], "historical index")
            val side =
                StrategyReplaySide.entries.singleOrNull {
                    it.commandValue == control["side"]?.asText()
                } ?: error("Historical control requires its exact candidate/incumbent side.")
            val arena = ArenaSpecification.read(arenaSource.path)
            require(
                arena.tokenActor == null &&
                    arena.expansionActor == null &&
                    arena.jointPolicy != null
            ) {
                "Historical controls must retain their unmodified raw-G3 factory."
            }
            val old = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
            val current = PurchaseJson.mapper.valueToTree<JsonNode>(raw.overlayMetadata())
            val role =
                if (side == StrategyReplaySide.CANDIDATE) "candidateBundle" else "incumbentBundle"
            require(
                listOf("card", "purchase").all {
                    old["jointPolicy"][role][it]["sha256"] ==
                        current["jointPolicy"]["sharedBundle"][it]["sha256"]
                } &&
                    old["jointPolicy"]["baseline"]["sha256"] ==
                        current["jointPolicy"]["baseline"]["sha256"] &&
                    old["expansionImitation"]["${side.commandValue}Model"]["sha256"] ==
                        current["expansionImitation"]["candidateModel"]["sha256"] &&
                    old["tokenOrder"]["${side.commandValue}Selector"] ==
                        current["tokenOrder"]["candidateSelector"] &&
                    side.model(arena) == raw.candidate
            ) {
                "Historical controlled models, F prior and E/T roles must match the raw study control."
            }
            val replay =
                StrategyReplays.play(
                    arena,
                    index,
                    side,
                    deadline,
                    StrategyReplayPair.read(pair.path),
                )
            ExpansionFinalScreen.verifyHistorical(
                PurchaseJson.read(expected.path),
                ExpansionOnPolicyStore.wire(replay),
            )
            listOf(arenaSource, pair, expected).forEach(TokenPairedSubject::verify)
            val name = "historical-$ordinal.json"
            artifacts.write(name, replay)
            mapOf(
                "index" to index,
                "side" to side.commandValue,
                "originalSourceSha256" to arenaSource.sha256,
                "originalPairSha256" to pair.sha256,
                "originalReplaySha256" to expected.sha256,
                "replayFile" to name,
                "replaySha256" to PurchaseJson.hash(output.resolve(name)),
                "canonicalVerified" to true,
            )
        }
        require(PurchaseJson.hash(source) == sourceHash) {
            "Historical source changed during its fixed controls."
        }
        admission.verify()
        artifacts.write(
            "historical-admission.json",
            mapOf(
                "kind" to "token-paired-historical-admission-1",
                "complete" to true,
                "controls" to 2,
                "zeroFailures" to true,
                "protocolSha256" to admission.protocol.sha256,
                "scopeSha256" to admission.scopeSha256,
                "ecologySha256" to admission.ecologySha256,
                "sourceSha256" to sourceHash,
                "scientificTerminalExecutions" to 2,
                "records" to records,
            ),
        )
    }
}
