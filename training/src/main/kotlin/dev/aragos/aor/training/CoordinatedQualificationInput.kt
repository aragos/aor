package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Explicit new supported ecology; the original83 admission is always checked in its own arena. */
internal class CoordinatedQualificationInput
private constructor(
    override val source: TokenPairedSubject,
    val host: QualificationHost,
    val protocol: CoordinatedQualificationProtocol,
    val originArena: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val admission: CoordinatedQualificationOrigin,
    val confirmation: TokenPairedSubject?,
    val bankName: String,
    evaluation: ArenaSpecification,
    override val globalStart: Int,
) : ThreeRoleScreenInput {
    override val kind = "coordinated-qualification-screen-1"
    override val roles = listOf("CANDIDATE", "CONTROL")
    override val replayIndices = CoordinatedQualificationProtocol.REPLAYS
    override val indices = globalStart until globalStart + 128
    override val ownedReplays = replayIndices.filter { it in indices }
    override val raw =
        evaluation.copy(
            coordinatedEs =
                ArenaCoordinatedPolicy.supported(
                    null,
                    admission.scope.original,
                    admission.scope.baseline,
                    null,
                )
        )
    private val candidate =
        evaluation.copy(
            coordinatedEs =
                ArenaCoordinatedPolicy.supported(
                    admission.finalBundle,
                    admission.scope.original,
                    admission.scope.baseline,
                    admission.calibration,
                )
        )
    override val finalAdmissionSha256
        get() = admission.source.sha256

    override val historicalAdmissionSha256
        get() = admission.historical.sha256

    private val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
    private val gatePins = protocol.confirmation(confirmation, bankName)
    private val bank =
        requireNotNull(protocol.banks[bankName]) { "The fixed qualification bank is required." }
    val ecologyIdentity =
        TokenPairedEcology.identity(raw, protocol.record["references"]["cardM8Sha256"].asText())
    val ecologySha256 = CpePairedDigest.json(ecologyIdentity)
    val inputPins = buildMap {
        putAll(admission.pins)
        putAll(gatePins)
        fun pin(subject: TokenPairedSubject) {
            subject.verify()
            val previous = put(subject.path.toString(), subject.sha256)
            require(previous == null || previous == subject.sha256) {
                "A qualification input path cannot acquire conflicting immutable identities."
            }
        }
        listOf(source, protocol.source, originArena, arenaSource).forEach(::pin)
        fun metadata(node: JsonNode) {
            if (node.isObject) {
                if (node["path"]?.isTextual == true && node["sha256"]?.isTextual == true) {
                    val path = Path.of(node["path"].asText()).toRealPath()
                    pin(TokenPairedSubject(path, node["sha256"].asText(), path.toString()))
                }
                node.elements().forEachRemaining(::metadata)
            } else if (node.isArray) node.forEach(::metadata)
        }
        metadata(ExpansionOnPolicyStore.wire(candidate.overlayMetadata()))
        val arenaRecord = PurchaseJson.read(arenaSource.path)
        (listOf(arenaRecord.required("candidate"), arenaRecord.required("incumbent")) +
                arenaRecord.required("opponents").toList())
            .forEach { value ->
                val path =
                    arenaSource.path.parent
                        .resolve(PurchaseJson.text(value, "ordinary model"))
                        .toRealPath()
                pin(
                    TokenPairedSubject(
                        path,
                        protocol.record["references"]["cardM8Sha256"].asText(),
                        path.toString(),
                    )
                )
            }
    }
    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to kind,
            "protocolSha256" to protocol.source.sha256,
            "originAdmissionSha256" to admission.source.sha256,
            "originScopeSha256" to admission.scope.sha256,
            "originEcologySha256" to admission.originEcologySha256,
            "ecologySha256" to ecologySha256,
            "ecology" to ecologyIdentity,
            "bankName" to bankName,
            "profile" to CoordinatedQualificationProtocol.PROFILE,
            "supportProfile" to protocol.record["supportProfile"].asText(),
            "candidateBundleSha256" to admission.finalBundle.source.sha256,
            "originalBundleSha256" to admission.scope.original.source.sha256,
            "calibrationSha256" to admission.calibration.source.sha256,
            "seeds" to bank.seeds,
            "roles" to roles,
            "replayIndices" to replayIndices,
        )
    override val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    override val signature = trainingSignature("$kind:${source.sha256}:$scienceSha256")

    override fun expectedOpponents(index: Int): List<Int> = opponents(bankName, index)

    override fun specification(role: String): ArenaSpecification =
        when (role) {
            "CANDIDATE" -> candidate
            "CONTROL" -> raw
            else -> error("Qualification admits exactly the supported CANDIDATE and CONTROL roles.")
        }

    override fun side(role: String): ArenaSide {
        require(role in roles) { "An explicit supported qualification role is required." }
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
            "Every admitted qualification role/world/reproduction must finish exactly once."
        }
        return mapOf(
            "attemptedGames" to attempts.attemptedGames,
            "completedGames" to attempts.completedGames,
        )
    }

    override fun verify() {
        inputPins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "An immutable qualification input changed: $path."
            }
        }
    }

    override fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        val subjects =
            mapOf(
                "study-source.json" to source,
                "protocol.json" to protocol.source,
                "origin-arena.json" to originArena,
                "origin-proof.json" to admission.proof,
                "arena-source.json" to arenaSource,
                "admission.json" to admission.source,
                "historical-admission.json" to admission.historical,
            )
        subjects.forEach { (name, subject) ->
            artifacts.ensureFileSnapshot(subject.path, subject.sha256, name)
        }
        confirmation?.let { artifacts.ensureFileSnapshot(it.path, it.sha256, "confirmation.json") }
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
            "protocol" to protocol.source.metadata("protocol.json"),
            "originArena" to originArena.metadata("origin-arena.json"),
            "originProof" to admission.proof.metadata("origin-proof.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
            "admission" to admission.source.metadata("admission.json"),
            "historicalAdmission" to admission.historical.metadata("historical-admission.json"),
            "policyBindings" to candidate.overlayMetadata(),
            "controlPolicyBindings" to raw.overlayMetadata(),
            "inputPins" to inputPins,
            "ownedReplayIndices" to ownedReplays,
            "maximumTerminalExecutions" to 256 + ownedReplays.size * 2,
        ) +
            (confirmation?.let { mapOf("confirmation" to it.metadata("confirmation.json")) }
                ?: emptyMap())

    companion object {
        fun opponents(bank: String, index: Int): List<Int> {
            require(bank in setOf("mixed", "homogeneous") && index in 0..4095) {
                "Qualification lineups require their declared bank and original global index."
            }
            return List(4) { if (bank == "mixed") (index / 4 + it) % 4 else 0 }
        }

        fun read(path: Path): CoordinatedQualificationInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "host",
                    "protocol",
                    "originArena",
                    "originAdmission",
                    "originProof",
                    "arena",
                    "bankName",
                    "globalStart",
                    "count",
                    "confirmation",
                ),
            )
            require(node["kind"]?.asText() == "coordinated-qualification-input-1") {
                "Use the new supported qualification source; the original83 screen remains unchanged."
            }
            val start = PurchaseJson.integer(node["globalStart"], "qualification shard start")
            require(
                start in 0..3968 &&
                    start % 128 == 0 &&
                    PurchaseJson.integer(node["count"], "shard count") == 128
            ) {
                "Qualification requires one of32 fixed global128-world shards."
            }
            fun subject(key: String) =
                TokenPairedSubject.read(node.required(key), source.path.parent)
            val protocol = CoordinatedQualificationProtocol(subject("protocol"))
            val host =
                requireNotNull(
                    QualificationHost.entries.singleOrNull { it.name == node["host"]?.asText() }
                ) {
                    "A qualification source requires its explicit DESKTOP or MAC owner."
                }
            require(host.owns(start)) {
                "A qualification shard cannot migrate from its declared host."
            }
            val originArena = subject("originArena")
            val originAdmission = subject("originAdmission")
            val originProof = subject("originProof")
            protocol.bindInputs(host, originArena, originAdmission, originProof)
            val admission =
                CoordinatedQualificationOrigin.read(originProof, originAdmission, originArena, host)
            protocol.bind(admission)
            val arenaSource = subject("arena")
            val arena = ArenaSpecification.read(arenaSource.path)
            val bank = PurchaseJson.text(node["bankName"], "bank name")
            protocol.validateArena(arenaSource, arena, admission, bank)
            val confirmation =
                node.required("confirmation").takeUnless(JsonNode::isNull)?.let {
                    TokenPairedSubject.read(it, source.path.parent)
                }
            return CoordinatedQualificationInput(
                    source,
                    host,
                    protocol,
                    originArena,
                    arenaSource,
                    admission,
                    confirmation,
                    bank,
                    arena,
                    start,
                )
                .also { it.verify() }
        }
    }
}

object CoordinatedQualificationMain {
    private val delegate =
        ThreeRoleScreenMain(
            prefix = "coordinated-qualification",
            roles = listOf("CANDIDATE", "CONTROL"),
            rawRole = "CONTROL",
            rawName = "Control",
            maximumSeconds = 28800,
            readInput = CoordinatedQualificationInput::read,
            historical = { _, _, _ -> error("Qualification has no additional historical games.") },
            worldCount = 4096,
            replayIndices = CoordinatedQualificationProtocol.REPLAYS,
            scienceDigest = CpePairedDigest::json,
            report = CoordinatedQualificationReport::report,
            requireExecutionCounts = true,
            expectedOpponents = { identity, index ->
                CoordinatedQualificationInput.opponents(
                    PurchaseJson.text(identity["bankName"], "qualification bank"),
                    index,
                )
            },
        )
    val modes = delegate.modes - "coordinated-qualification-historical"

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)
}
