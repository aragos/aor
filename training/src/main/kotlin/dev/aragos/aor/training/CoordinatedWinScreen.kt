package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * Sole final mean, exact admitted start, and supported G3 control on one independent mixed bank.
 */
internal class CoordinatedWinScreenInput
private constructor(
    override val source: TokenPairedSubject,
    val host: QualificationHost,
    val arenaSource: TokenPairedSubject,
    val admission: CoordinatedWinAdmission,
    val historical: TokenPairedSubject,
    val bank: CoordinatedEsBank,
    override val globalStart: Int,
    override val raw: ArenaSpecification,
) : ThreeRoleScreenInput {
    override val kind = "coordinated-win-screen-1"
    override val roles = listOf("FINAL", "START", "CONTROL")
    override val replayIndices = listOf(0, 682, 1365, 2047)
    override val indices = globalStart until globalStart + 128
    override val ownedReplays = replayIndices.filter { it in indices }
    private val specifications =
        mapOf("CONTROL" to raw) +
            mapOf("FINAL" to admission.finalBundle, "START" to admission.startBundle).mapValues {
                (_, bundle) ->
                raw.copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.supported(
                            bundle,
                            admission.origin.scope.original,
                            admission.origin.scope.baseline,
                            admission.inherited.calibration,
                        )
                )
            }
    override val admissionBindings
        get() =
            mapOf(
                "finalAdmissionSha256" to admission.source.sha256,
                "historicalAdmissionSha256" to historical.sha256,
            )

    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to kind,
            "scopeSha256" to admission.protocol.scopeSha256,
            "ecologySha256" to admission.protocol.ecologySha256,
            "protocolSha256" to admission.protocol.source.sha256,
            "seeds" to bank.seeds,
            "roles" to roles,
            "replayIndices" to replayIndices,
        ) + admission.bindings
    override val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    override val signature =
        trainingSignature("$kind:${source.sha256}:${admission.source.sha256}:$scienceSha256")
    private val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
    private val pins =
        admission.pins +
            CoordinatedWinEcology.pins(arenaSource, raw) +
            (source.path.toString() to source.sha256) +
            (historical.path.toString() to historical.sha256)

    override fun specification(role: String): ArenaSpecification =
        requireNotNull(specifications[role]) {
            "Only FINAL, START and CONTROL belong to this screen."
        }

    override fun expectedOpponents(index: Int): List<Int> = List(4) { (index / 4 + it) % 4 }

    override fun side(role: String): ArenaSide {
        require(role in roles) { "An explicit admitted screen role is required." }
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
            "Every admitted role/world and prescribed reproduction must complete exactly once."
        }
        return mapOf(
            "attemptedGames" to attempts.attemptedGames,
            "completedGames" to attempts.completedGames,
        )
    }

    override fun verify() {
        pins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "An immutable screen subject changed: $path."
            }
        }
    }

    override fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        mapOf(
                "study-source.json" to source,
                "arena-source.json" to arenaSource,
                "admission.json" to admission.source,
                "historical-admission.json" to historical,
                "origin-proof.json" to admission.origin.proof,
            )
            .forEach { (name, subject) ->
                artifacts.ensureFileSnapshot(subject.path, subject.sha256, name)
            }
    }

    override fun manifest(): Map<String, Any> =
        mapOf(
            "kind" to kind,
            "host" to host.name,
            "signature" to signature,
            "scienceSha256" to scienceSha256,
            "scienceIdentity" to scienceIdentity,
            "globalStart" to globalStart,
            "count" to 128,
            "source" to source.metadata("study-source.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
            "admission" to admission.source.metadata("admission.json"),
            "historicalAdmission" to historical.metadata("historical-admission.json"),
            "originProof" to admission.origin.proof.metadata("origin-proof.json"),
            "policyBindings" to specification("FINAL").overlayMetadata(),
            "inputPins" to pins,
            "ownedReplayIndices" to ownedReplays,
            "maximumTerminalExecutions" to 384 + ownedReplays.size * 3,
        )

    companion object {
        fun read(path: Path): CoordinatedWinScreenInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "host",
                    "originProof",
                    "arena",
                    "admission",
                    "historical",
                    "globalStart",
                    "count",
                    "bank",
                ),
            )
            require(node["kind"]?.asText() == "coordinated-win-screen-1") {
                "The new final/start/control screen source is required."
            }
            val host =
                QualificationHost.entries.singleOrNull { it.name == node["host"]?.asText() }
                    ?: error("An exact DESKTOP or MAC screen owner is required.")
            val start = PurchaseJson.integer(node["globalStart"], "screen start")
            require(
                start in 0..1920 &&
                    start % 128 == 0 &&
                    PurchaseJson.integer(node["count"], "screen count") == 128 &&
                    (host == QualificationHost.DESKTOP) == (start < 1280)
            ) {
                "Screen ranges remain the original ten Desktop and six Mac128-world shards."
            }
            fun subject(key: String) =
                TokenPairedSubject.read(node.required(key), source.path.parent)
            val admission =
                CoordinatedWinAdmission.read(subject("admission"), subject("originProof"), host)
            val historical = subject("historical")
            require(historical.sha256 == admission.named("historical").sha256) {
                "The final screen must retain its original two historical regressions."
            }
            val bank = CoordinatedEsBank.read(node["bank"], 2048)
            require(node["bank"] == admission.protocol.record["screenBank"]) {
                "The complete independent prospective final bank cannot change."
            }
            val arena = subject("arena")
            val raw = CoordinatedWinEcology.read(arena, admission.protocol, admission.origin, bank)
            return CoordinatedWinScreenInput(
                source,
                host,
                arena,
                admission,
                historical,
                bank,
                start,
                raw,
            )
        }
    }
}

object CoordinatedWinScreenMain {
    private val delegate =
        ThreeRoleScreenMain(
            prefix = "coordinated-win",
            roles = listOf("FINAL", "START", "CONTROL"),
            rawRole = "CONTROL",
            rawName = "Control",
            maximumSeconds = 86400,
            readInput = CoordinatedWinScreenInput::read,
            historical = { source, output, deadline ->
                CoordinatedWinHistorical.run(CoordinatedEsWire.source(source), output, deadline)
            },
            worldCount = 2048,
            replayIndices = listOf(0, 682, 1365, 2047),
            scienceDigest = CpePairedDigest::json,
            report = ::report,
            requireExecutionCounts = true,
            expectedOpponents = { _, index -> List(4) { (index / 4 + it) % 4 } },
        )
    val modes = delegate.modes

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)

    internal fun report(worlds: List<JsonNode>): Map<String, Any> {
        require(worlds.map { it["index"].asInt() } == (0..2047).toList()) {
            "All2048 complete independent paired worlds are required before effects open."
        }
        fun contrast(left: String, right: String) =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                pilotInterval(
                    worlds.map { world ->
                        val seat = world["seat"].asInt()
                        fun value(role: String): Double {
                            val game = world["roles"][role]
                            if (metric != "chaos") return game[metric][seat].doubleValue()
                            val score =
                                game["scores"].single { it["player"].asText() == "p${seat + 1}" }
                            return if (score["total"].isNull) 1.0 else 0.0
                        }
                        value(left) - value(right)
                    }
                )
            }
        val start = contrast("FINAL", "START")
        val control = contrast("FINAL", "CONTROL")
        val learned = (start["wins"]?.lower95 ?: Double.NEGATIVE_INFINITY) > 0.0
        return mapOf(
            "kind" to "coordinated-win-screen-report-1",
            "worlds" to 2048,
            "finalMinusStart" to start,
            "finalMinusControl" to control,
            "startMinusControl" to contrast("START", "CONTROL"),
            "learningGatePassed" to learned,
            "developmentGatePassed" to
                (learned && (control["wins"]?.lower95 ?: Double.NEGATIVE_INFINITY) > 0.0),
            "intervalMethod" to "Normal 95% across independent paired worlds",
            "intervalMultiplier" to 1.96,
            "qualification" to false,
            "activated" to false,
            "automaticSuccessor" to false,
        )
    }
}
