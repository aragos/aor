package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** The original two raw-G3 recorded controls; no new policy or outcome-selected indices. */
internal object ThreeRoleHistorical {
    fun records(
        source: Path,
        node: JsonNode,
        raw: ArenaSpecification,
        output: Path,
        deadline: Long,
    ): List<Map<String, Any>> {
        val controls = PurchaseJson.array(node["controls"], "controls")
        require(controls.size == 2) {
            "Exactly the two prospective raw-G3 historical controls are permitted."
        }
        val artifacts = TrainingArtifacts(output)
        return controls.mapIndexed { ordinal, control ->
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
                arena.coordinatedEs == null &&
                    arena.cpeBundle == null &&
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
    }
}
