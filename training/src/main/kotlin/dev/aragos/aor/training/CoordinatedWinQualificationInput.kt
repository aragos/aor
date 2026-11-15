package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * The root-nominated portable inference unit is evaluated through existing bounded screen owners.
 */
internal class CoordinatedWinQualificationInput
private constructor(
    override val source: TokenPairedSubject,
    val host: QualificationHost,
    val protocol: CoordinatedWinQualificationProtocol,
    val nomination: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val asset: CoordinatedInferenceAsset,
    val nominationPins: Map<String, String>,
    val confirmation: TokenPairedSubject?,
    val bankName: String,
    evaluation: ArenaSpecification,
    override val globalStart: Int,
) : ThreeRoleScreenInput {
    override val kind = "coordinated-win-qualification-screen-1"
    override val roles = listOf("CANDIDATE", "CONTROL")
    override val replayIndices = CoordinatedQualificationProtocol.REPLAYS
    override val indices = globalStart until globalStart + 128
    override val ownedReplays = replayIndices.filter { it in indices }
    override val raw =
        evaluation.copy(
            coordinatedEs =
                ArenaCoordinatedPolicy.supported(
                    null,
                    asset.original,
                    asset.baseline,
                    null,
                )
        )
    private val candidate =
        evaluation.copy(
            coordinatedEs =
                ArenaCoordinatedPolicy.supported(
                    asset.current,
                    asset.original,
                    asset.baseline,
                    asset.calibration,
                )
        )
    override val admissionBindings =
        mapOf(
            "inferenceAssetSha256" to asset.source.sha256,
            "nominationSha256" to nomination.sha256,
        )
    private val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
    private val gatePins = protocol.confirmation(confirmation, bankName)
    private val bank =
        requireNotNull(protocol.banks[bankName]) {
            "Only the two declared qualification banks exist."
        }
    val ecologyIdentity =
        TokenPairedEcology.identity(raw, protocol.record["references"]["cardM8Sha256"].asText())
    val ecologySha256 = CpePairedDigest.json(ecologyIdentity)
    val inputPins =
        asset.pins +
            nominationPins +
            gatePins +
            CoordinatedWinEcology.pins(arenaSource, candidate) +
            mapOf(
                source.path.toString() to source.sha256,
                protocol.source.path.toString() to protocol.source.sha256,
            )
    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to kind,
            "protocolSha256" to protocol.source.sha256,
            "inferenceAssetSha256" to asset.source.sha256,
            "nominationSha256" to nomination.sha256,
            "ecologySha256" to ecologySha256,
            "ecology" to ecologyIdentity,
            "bankName" to bankName,
            "profile" to CoordinatedInferenceAsset.PROFILE,
            "supportProfile" to protocol.record["supportProfile"].asText(),
            "candidateBundleSha256" to asset.current.source.sha256,
            "originalBundleSha256" to asset.original.source.sha256,
            "calibrationSha256" to asset.calibration.source.sha256,
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
                "inference-asset.json" to asset.source,
                "nomination.json" to nomination,
                "arena-source.json" to arenaSource,
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
            "inferenceAsset" to asset.source.metadata("inference-asset.json"),
            "nomination" to nomination.metadata("nomination.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
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

        fun read(path: Path): CoordinatedWinQualificationInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "host",
                    "protocol",
                    "inferenceAsset",
                    "nomination",
                    "arena",
                    "bankName",
                    "globalStart",
                    "count",
                    "confirmation",
                ),
            )
            require(node["kind"]?.asText() == "coordinated-win-qualification-input-1") {
                "Use the typed nominated-inference win-only qualification source."
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
            val protocol = CoordinatedWinQualificationProtocol(subject("protocol"))
            val host =
                requireNotNull(
                    QualificationHost.entries.singleOrNull { it.name == node["host"]?.asText() }
                ) {
                    "A qualification source requires its explicit DESKTOP or MAC owner."
                }
            require(host.owns(start)) {
                "A qualification shard cannot migrate from its declared host."
            }
            val assetSubject = subject("inferenceAsset")
            val nomination = subject("nomination")
            protocol.bindInputs(assetSubject, nomination)
            val nominationPins = CoordinatedPolicyNomination.read(nomination, assetSubject)
            val arenaSource = subject("arena")
            val configuration = ArenaSpecification.read(arenaSource.path).configurations.single()
            val asset = CoordinatedInferenceAsset.read(assetSubject, configuration)
            val bank = PurchaseJson.text(node["bankName"], "bank name")
            val bankSpec =
                requireNotNull(protocol.banks[bank]) {
                    "Only the declared mixed/homogeneous bank is admitted."
                }
            val arena = CoordinatedInferenceEcology.read(arenaSource, asset, bankSpec, bank)
            val confirmation =
                node.required("confirmation").takeUnless(JsonNode::isNull)?.let {
                    TokenPairedSubject.read(it, source.path.parent)
                }
            return CoordinatedWinQualificationInput(
                    source,
                    host,
                    protocol,
                    nomination,
                    arenaSource,
                    asset,
                    nominationPins,
                    confirmation,
                    bank,
                    arena,
                    start,
                )
                .also { it.verify() }
        }
    }
}

object CoordinatedWinQualificationMain {
    private val delegate =
        ThreeRoleScreenMain(
            prefix = "coordinated-win-qualification",
            roles = listOf("CANDIDATE", "CONTROL"),
            rawRole = "CONTROL",
            rawName = "Control",
            maximumSeconds = 28800,
            readInput = CoordinatedWinQualificationInput::read,
            historical = { _, _, _ -> error("Qualification has no additional historical games.") },
            worldCount = 4096,
            replayIndices = CoordinatedQualificationProtocol.REPLAYS,
            scienceDigest = CpePairedDigest::json,
            report = CoordinatedWinQualificationProtocol.REPORT::report,
            admissionKeys = listOf("inferenceAssetSha256", "nominationSha256"),
            requireExecutionCounts = true,
            expectedOpponents = { identity, index ->
                CoordinatedWinQualificationInput.opponents(
                    PurchaseJson.text(identity["bankName"], "qualification bank"),
                    index,
                )
            },
        )
    val modes = delegate.modes - "coordinated-win-qualification-historical"

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)
}
