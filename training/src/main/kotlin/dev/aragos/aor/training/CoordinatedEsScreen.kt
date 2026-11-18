package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** The sole final mean against raw G3, using complete fixed2048-world two-role slices. */
internal class CoordinatedEsScreenInput
private constructor(
    override val source: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val admission: CoordinatedEsAdmission,
    val historical: TokenPairedSubject,
    val bank: CoordinatedEsBank,
    override val globalStart: Int,
) : ThreeRoleScreenInput {
    override val kind = "coordinated-es-screen-1"
    override val roles = listOf("FINAL", "RAW_G3")
    override val replayIndices = listOf(0, 682, 1365, 2047)
    override val indices = globalStart until globalStart + 128
    override val ownedReplays = replayIndices.filter { it in indices }
    override val raw =
        admission.scope.arena.copy(pairsPerConfiguration = bank.count, seed = bank.nativeOffset)
    private val final =
        raw.copy(
            coordinatedEs =
                ArenaCoordinatedPolicy.calibrated(
                    admission.finalBundle,
                    admission.scope.original,
                    admission.scope.baseline,
                    admission.calibration,
                )
        )
    override val admissionBindings
        get() =
            mapOf(
                "finalAdmissionSha256" to admission.source.sha256,
                "historicalAdmissionSha256" to historical.sha256,
            )

    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to kind,
            "scopeSha256" to admission.scope.sha256,
            "ecologySha256" to admission.scope.ecologySha256,
            "protocolSha256" to admission.protocol.sha256,
            "originalBundleSha256" to admission.scope.original.source.sha256,
            "finalBundleSha256" to admission.finalBundle.source.sha256,
            "calibrationSha256" to admission.calibration.source.sha256,
            "seeds" to bank.seeds,
            "roles" to roles,
            "replayIndices" to replayIndices,
        )
    override val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    override val signature =
        trainingSignature("$kind:${source.sha256}:${admission.source.sha256}:$scienceSha256")
    private val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)

    override fun specification(role: String): ArenaSpecification =
        when (role) {
            "FINAL" -> final
            "RAW_G3" -> raw
            else -> error("Only FINAL and RAW_G3 belong to the sole final paired comparison.")
        }

    override fun side(role: String): ArenaSide {
        require(role in roles) { "An exact final screen role is required." }
        return ArenaSide.CANDIDATE
    }

    override fun executeGame(deadline: Long, operation: (Long) -> ArenaGame): ArenaGame =
        attempts.execute(deadline) { operation(it).also(CoordinatedEsAttempts::terminal) }

    override fun executeReplay(
        deadline: Long,
        operation: (Long) -> StrategyReplay,
    ): StrategyReplay =
        attempts.execute(deadline) {
            operation(it).also { replay -> CoordinatedEsAttempts.terminal(replay.game) }
        }

    override fun executionReceipt(expected: Int?): Map<String, Int> {
        require(
            expected == null ||
                attempts.attemptedGames == expected && attempts.completedGames == expected
        ) {
            "Every final role/world and prescribed replay must complete exactly once."
        }
        return mapOf(
            "attemptedGames" to attempts.attemptedGames,
            "completedGames" to attempts.completedGames,
        )
    }

    override fun verify() {
        source.verify()
        arenaSource.verify()
        historical.verify()
        admission.verify()
    }

    override fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        artifacts.ensureFileSnapshot(source.path, source.sha256, "study-source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena-source.json")
        artifacts.ensureFileSnapshot(
            admission.source.path,
            admission.source.sha256,
            "admission.json",
        )
        artifacts.ensureFileSnapshot(
            historical.path,
            historical.sha256,
            "historical-admission.json",
        )
    }

    override fun manifest(): Map<String, Any> =
        mapOf(
            "kind" to kind,
            "signature" to signature,
            "scienceSha256" to scienceSha256,
            "scienceIdentity" to scienceIdentity,
            "globalStart" to globalStart,
            "count" to 128,
            "source" to source.metadata("study-source.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
            "admission" to admission.source.metadata("admission.json"),
            "historicalAdmission" to historical.metadata("historical-admission.json"),
            "policyBindings" to final.overlayMetadata(),
            "inputPins" to admission.pins,
            "ownedReplayIndices" to ownedReplays,
            "maximumTerminalExecutions" to 256 + ownedReplays.size * 2,
        )

    companion object {
        fun read(path: Path): CoordinatedEsScreenInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf("kind", "arena", "admission", "historical", "globalStart", "count", "bank"),
            )
            require(node["kind"]?.asText() == "coordinated-es-screen-1") {
                "The distinct mean16 screen source is required."
            }
            val start = PurchaseJson.integer(node["globalStart"], "screen start")
            require(
                start in 0..1920 &&
                    start % 128 == 0 &&
                    PurchaseJson.integer(node["count"], "screen count") == 128
            ) {
                "Final shards retain one of sixteen fixed128-world ranges."
            }
            val arena = TokenPairedSubject.read(node["arena"], source.path.parent)
            val admission =
                CoordinatedEsAdmission.read(
                    TokenPairedSubject.read(node["admission"], source.path.parent),
                    arena,
                )
            val historical = TokenPairedSubject.read(node["historical"], source.path.parent)
            require(historical.sha256 == admission.historical.sha256) {
                "The final screen must retain its original historical gate."
            }
            val bank = CoordinatedEsBank.read(node["bank"], 2048)
            require(node["bank"] == PurchaseJson.read(admission.protocol.path)["screenBank"]) {
                "Final games must use the complete prospective fresh protocol screen bank."
            }
            return CoordinatedEsScreenInput(source, arena, admission, historical, bank, start)
                .also { it.verify() }
        }
    }
}

object CoordinatedEsScreenMain {
    private val delegate =
        ThreeRoleScreenMain(
            prefix = "coordinated-es",
            roles = listOf("FINAL", "RAW_G3"),
            rawRole = "RAW_G3",
            rawName = "RawG3",
            maximumSeconds = 86400,
            readInput = CoordinatedEsScreenInput::read,
            historical = { source, output, deadline ->
                CoordinatedEsHistorical.run(CoordinatedEsWire.source(source), output, deadline)
            },
            worldCount = 2048,
            replayIndices = listOf(0, 682, 1365, 2047),
            scienceDigest = CpePairedDigest::json,
            report = ::report,
            requireExecutionCounts = true,
        )
    val modes = delegate.modes

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)

    /**
     * Uses the original independent paired-world interval owner, with no candidate/iteration
     * selection.
     */
    internal fun report(worlds: List<JsonNode>): Map<String, Any> {
        require(worlds.map { it["index"].asInt() } == (0..2047).toList()) {
            "All2048 original worlds are required before effects open."
        }
        val contrasts =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                pilotInterval(
                    worlds.map { world ->
                        val seat = world["seat"].asInt()
                        fun value(role: String): Double =
                            if (metric == "chaos") {
                                if (world["roles"][role]["scores"][seat]["total"].isNull) {
                                    1.0
                                } else {
                                    0.0
                                }
                            } else {
                                world["roles"][role][metric][seat].doubleValue()
                            }
                        value("FINAL") - value("RAW_G3")
                    }
                )
            }
        return mapOf(
            "kind" to "coordinated-es-screen-report-1",
            "worlds" to 2048,
            "finalMinusRawG3" to contrasts,
            "developmentGatePassed" to
                (listOf("wins", "rewards").all {
                    (contrasts[it]?.lower95 ?: Double.NEGATIVE_INFINITY) > 0.0
                }),
            "intervalMethod" to "Normal 95% across independent paired worlds",
            "intervalMultiplier" to 1.96,
            "qualification" to false,
            "activated" to false,
            "automaticSuccessor" to false,
        )
    }
}
