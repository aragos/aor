package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Global family60 identity and the index inside its unchanged recorded source chunk. */
internal data class CoordinatedEsHistoricalPosition(val globalIndex: Int, val index: Int) {
    companion object {
        fun read(
            control: JsonNode,
            declaration: JsonNode,
            raw: ArenaSpecification,
            pair: StrategyReplayPair,
        ): CoordinatedEsHistoricalPosition {
            val position =
                CoordinatedEsHistoricalPosition(
                    PurchaseJson.integer(control["globalIndex"], "historical global index"),
                    PurchaseJson.integer(control["index"], "historical source-local index"),
                )
            require(
                control["globalIndex"] == declaration["index"] &&
                    control["side"] == declaration["side"] &&
                    position.globalIndex >= 0
            ) {
                "Historical global indices/sides must match the outcome-blind declarations."
            }
            require(
                raw.configurations.size == 1 &&
                    position.index == position.globalIndex % raw.pairsPerConfiguration
            ) {
                "The historical local index must preserve its original single-configuration chunk."
            }
            // The existing pair owner binds local range, seed, seat, roles and recorded rivals.
            pair.opponentIndices(raw, position.index)
            return position
        }
    }
}

/** Exactly the two declared historical games, evaluated through the new exact-zero role. */
internal object CoordinatedEsHistorical {
    fun run(source: TokenPairedSubject, output: Path, deadline: Long) {
        val node = PurchaseJson.read(source.path)
        PurchaseJson.fields(node, setOf("kind", "protocol", "arena", "originalBundle", "controls"))
        require(node["kind"]?.asText() == "coordinated-es-historical-source-1") {
            "The explicit historical zero-role source is required."
        }
        val protocol = TokenPairedSubject.read(node["protocol"], source.path.parent)
        val plan = CoordinatedEsWire.protocol(protocol)
        val scope =
            CoordinatedEsScope.read(
                TokenPairedSubject.read(node["arena"], source.path.parent),
                TokenPairedSubject.read(node["originalBundle"], source.path.parent),
            )
        require(
            plan["original"]["bundleSha256"]?.asText() == scope.original.source.sha256 &&
                plan["original"]["scopeSha256"]?.asText() == scope.sha256 &&
                plan["original"]["ecologySha256"]?.asText() == scope.ecologySha256
        ) {
            "Historical proof must bind the actual original role and fresh reference scope."
        }
        val declarations = PurchaseJson.array(plan["historicalControls"], "historical declarations")
        require(
            declarations.size == 2 &&
                declarations.map { it["index"]?.asInt() } == listOf(0, 4095) &&
                declarations.all {
                    it["family"]?.asInt() == 60 &&
                        it["bank"]?.asText() == "HOMOGENEOUS_HELDOUT" &&
                        it["side"]?.asText() == "candidate"
                }
        ) {
            "Only the two prospectively declared family60 controls are admitted."
        }
        val controls = PurchaseJson.array(node["controls"], "historical subjects")
        require(controls.size == 2) { "Historical proof requires exactly two original subjects." }
        val artifacts = TrainingArtifacts(output)
        val attempts = CoordinatedEsAttempts(deadline)
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        try {
            val records = controls.mapIndexed { ordinal, control ->
                PurchaseJson.fields(
                    control,
                    setOf("source", "pair", "replay", "globalIndex", "index", "side"),
                )
                val originalSource = TokenPairedSubject.read(control["source"], source.path.parent)
                val pair = TokenPairedSubject.read(control["pair"], source.path.parent)
                val canonical = TokenPairedSubject.read(control["replay"], source.path.parent)
                val raw = ArenaSpecification.read(originalSource.path)
                val originalPair = StrategyReplayPair.read(pair.path)
                val position =
                    CoordinatedEsHistoricalPosition.read(
                        control,
                        declarations[ordinal],
                        raw,
                        originalPair,
                    )
                val side = StrategyReplaySide.CANDIDATE
                ThreeRoleHistorical.validateReference(scope.arena, raw, side)
                val zero =
                    ArenaCoordinatedPolicy.zero(scope.original, scope.baseline, side.arenaSide)
                val replay = attempts.execute { gameDeadline ->
                    StrategyReplays.coordinatedHistorical(
                            raw,
                            zero,
                            position.index,
                            side,
                            gameDeadline,
                            originalPair,
                        )
                        .also {
                            CoordinatedEsAttempts.terminal(it.game)
                            ExpansionFinalScreen.verifyHistorical(
                                PurchaseJson.read(canonical.path),
                                ExpansionOnPolicyStore.wire(it),
                            )
                        }
                }
                listOf(originalSource, pair, canonical).forEach(TokenPairedSubject::verify)
                val name = "historical-$ordinal.json"
                artifacts.write(name, replay)
                mapOf(
                    "globalIndex" to position.globalIndex,
                    "index" to position.index,
                    "side" to side.commandValue,
                    "originalSourceSha256" to originalSource.sha256,
                    "originalPairSha256" to pair.sha256,
                    "originalReplaySha256" to canonical.sha256,
                    "replayFile" to name,
                    "replaySha256" to PurchaseJson.hash(output.resolve(name)),
                    "canonicalVerified" to true,
                    "execution" to "ZERO_REFERENCE",
                )
            }
            source.verify()
            protocol.verify()
            scope.original.verify()
            artifacts.write(
                "historical-admission.json",
                mapOf(
                    "kind" to "coordinated-es-historical-admission-1",
                    "complete" to true,
                    "passed" to true,
                    "protocolSha256" to protocol.sha256,
                    "scopeSha256" to scope.sha256,
                    "ecologySha256" to scope.ecologySha256,
                    "originalBundleSha256" to scope.original.source.sha256,
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
