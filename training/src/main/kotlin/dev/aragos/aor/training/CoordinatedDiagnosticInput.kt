package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * Range admission only; policies, controller composition, full games and replays keep their owners.
 */
internal class CoordinatedDiagnosticInput
private constructor(
    override val source: TokenPairedSubject,
    val host: QualificationHost,
    val protocol: CoordinatedDiagnosticProtocol,
    val originArena: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val admission: CoordinatedQualificationOrigin,
    val cell: DiagnosticCell,
    override val raw: ArenaSpecification,
    val maskBindings: Map<String, JsonNode>,
) : ThreeRoleScreenInput {
    override val kind = "coordinated-diagnostic-screen-1"
    override val roles = listOf("CANDIDATE", "INCUMBENT")
    override val indices = DiagnosticCell.range(host)
    override val globalStart = indices.first
    override val replayIndices = cell.replayIndices
    override val ownedReplays = replayIndices.filter { it in indices }
    override val finalAdmissionSha256 = admission.source.sha256
    override val historicalAdmissionSha256 = admission.historical.sha256

    override fun replayRoles(index: Int) = cell.replayRoles(index)

    override fun expectedOpponents(index: Int) = cell.opponents(index)

    override fun specification(role: String): ArenaSpecification {
        side(role)
        return raw
    }

    override fun side(role: String): ArenaSide =
        when (role) {
            "CANDIDATE" -> ArenaSide.CANDIDATE
            "INCUMBENT" -> ArenaSide.INCUMBENT
            else -> error("A diagnostic requires its explicit complementary side.")
        }

    private val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
    private val bindings = ExpansionOnPolicyStore.wire(raw.overlayMetadata())
    val ecologyIdentity =
        TokenPairedEcology.identity(
            raw.copy(coordinatedEs = null),
            protocol.record["references"]["cardM8Sha256"].asText(),
        )
    val ecologySha256 = CpePairedDigest.json(ecologyIdentity)
    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to kind,
            "protocolSha256" to protocol.source.sha256,
            "originAdmissionSha256" to admission.source.sha256,
            "originScopeSha256" to admission.scope.sha256,
            "originEcologySha256" to admission.originEcologySha256,
            "ecologySha256" to ecologySha256,
            "ecologyIdentity" to ecologyIdentity,
            "ecology" to cell.ecology.name,
            "support" to cell.support.name,
            "pair" to cell.pair,
            "roleMasks" to cell.roleMasks,
            "maskBindings" to maskBindings,
            "candidateBundleSha256" to admission.finalBundle.source.sha256,
            "originalBundleSha256" to admission.scope.original.source.sha256,
            "calibrationSha256" to admission.calibration.source.sha256,
            "seeds" to protocol.seeds,
            "roles" to roles,
            "replayIndices" to replayIndices,
            "roleReplayIndices" to cell.roleMasks.mapValues { DiagnosticCell.replay(it.value) },
            "diagnosticOnly" to true,
            "promotionAllowed" to false,
        )
    override val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    override val signature = trainingSignature("$kind:${source.sha256}:$scienceSha256")
    val inputPins = buildMap {
        putAll(admission.pins)
        fun pin(subject: TokenPairedSubject) {
            subject.verify()
            val old = put(subject.path.toString(), subject.sha256)
            require(old == null || old == subject.sha256) {
                "A diagnostic path cannot have conflicting immutable bytes."
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
        metadata(bindings)
        val arenaRecord = PurchaseJson.read(arenaSource.path)
        (listOf(arenaRecord.required("candidate"), arenaRecord.required("incumbent")) +
                arenaRecord.required("opponents").toList())
            .forEach { value ->
                val path =
                    arenaSource.path.parent
                        .resolve(PurchaseJson.text(value, "ordinary role"))
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
            "Every diagnostic role/world/reproduction must finish exactly once."
        }
        return mapOf(
            "attemptedGames" to attempts.attemptedGames,
            "completedGames" to attempts.completedGames,
        )
    }

    override fun verify() {
        inputPins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "Immutable diagnostic input changed: $path."
            }
        }
    }

    private val snapshots
        get() =
            mapOf(
                "study-source.json" to source,
                "protocol.json" to protocol.source,
                "origin-arena.json" to originArena,
                "origin-proof.json" to admission.proof,
                "arena-source.json" to arenaSource,
                "admission.json" to admission.source,
                "historical-admission.json" to admission.historical,
                "original-bundle.json" to admission.scope.original.source,
                "final-bundle.json" to admission.finalBundle.source,
                "calibration.json" to admission.calibration.source,
            )

    override fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        snapshots.forEach { (name, subject) ->
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
            "count" to indices.count(),
            "source" to source.metadata("study-source.json"),
            "protocol" to protocol.source.metadata("protocol.json"),
            "originArena" to originArena.metadata("origin-arena.json"),
            "originProof" to admission.proof.metadata("origin-proof.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
            "admission" to admission.source.metadata("admission.json"),
            "historicalAdmission" to admission.historical.metadata("historical-admission.json"),
            "originalBundle" to admission.scope.original.source.metadata("original-bundle.json"),
            "finalBundle" to admission.finalBundle.source.metadata("final-bundle.json"),
            "calibration" to admission.calibration.source.metadata("calibration.json"),
            "policyBindings" to raw.overlayMetadata(),
            "roleMasks" to cell.roleMasks,
            "maskBindings" to maskBindings,
            "inputPins" to inputPins,
            "ownedReplayIndices" to ownedReplays,
            "ownedRoleReplays" to
                ownedReplays.flatMap { index ->
                    replayRoles(index).map { role -> mapOf("index" to index, "role" to role) }
                },
            "maximumTerminalExecutions" to
                indices.count() * 2 + ownedReplays.sumOf { replayRoles(it).size },
        )

    companion object {
        fun read(path: Path): CoordinatedDiagnosticInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "protocol",
                    "originArena",
                    "originAdmission",
                    "originProof",
                    "arena",
                    "host",
                    "ecology",
                    "support",
                    "pair",
                    "globalStart",
                    "count",
                ),
            )
            require(node["kind"]?.asText() == "coordinated-diagnostic-input-1") {
                "Use the explicit non-promoting factorial source."
            }
            fun subject(key: String) =
                TokenPairedSubject.read(node.required(key), source.path.parent)
            val host = QualificationHost.entries.singleOrNull { it.name == node["host"]?.asText() }
            requireNotNull(host) { "The original diagnostic DESKTOP/MAC owner is required." }
            val range = DiagnosticCell.range(host)
            require(
                PurchaseJson.integer(node["globalStart"], "range start") == range.first &&
                    PurchaseJson.integer(node["count"], "range count") == range.count()
            ) {
                "Each case uses its one fixed320/192-world host range."
            }
            val cell =
                DiagnosticCell(
                    requireNotNull(
                        DiagnosticEcology.entries.singleOrNull {
                            it.name == node["ecology"]?.asText()
                        }
                    ) {
                        "A fixed diagnostic ecology is required."
                    },
                    requireNotNull(
                        DiagnosticSupport.entries.singleOrNull {
                            it.name == node["support"]?.asText()
                        }
                    ) {
                        "A fixed all-seat support context is required."
                    },
                    PurchaseJson.integer(node["pair"], "complement pair"),
                )
            val protocol = CoordinatedDiagnosticProtocol(subject("protocol"))
            val originArena = subject("originArena")
            val originAdmission = subject("originAdmission")
            val proof = subject("originProof")
            CoordinatedEvaluationReferences.bindInputs(
                protocol.record,
                host,
                originArena,
                originAdmission,
                proof,
            )
            val admission =
                CoordinatedQualificationOrigin.read(proof, originAdmission, originArena, host)
            CoordinatedEvaluationReferences.bind(protocol.record, admission)
            val arenaSource = subject("arena")
            val masks = CoordinatedDiagnosticMasks.validate(arenaSource, admission, cell)
            val arena = ArenaSpecification.read(arenaSource.path)
            CoordinatedEvaluationReferences.validateArena(
                protocol.record,
                arenaSource,
                arena.copy(coordinatedEs = null),
                admission,
                cell.ecology == DiagnosticEcology.MIXED,
                512,
                protocol.nativeOffset,
                ArenaPurpose.SCREENING,
            )
            return CoordinatedDiagnosticInput(
                    source,
                    host,
                    protocol,
                    originArena,
                    arenaSource,
                    admission,
                    cell,
                    arena,
                    masks,
                )
                .also { it.verify() }
        }
    }
}

/** Raw component and selector joins precede decoding any constructed mask descriptor. */
internal object CoordinatedDiagnosticMasks {
    fun validate(
        source: TokenPairedSubject,
        admission: CoordinatedQualificationOrigin,
        cell: DiagnosticCell,
    ): Map<String, JsonNode> {
        val overlay = PurchaseJson.read(source.path).required("coordinatedEs")
        val supported = cell.support == DiagnosticSupport.SUPPORT1
        PurchaseJson.fields(
            overlay,
            setOf(
                "version",
                "execution",
                "originalBundle",
                "baseline",
                "calibration",
                "candidate",
                "incumbent",
            ),
            if (supported) setOf("supportProfile") else emptySet(),
        )
        require(
            overlay["version"]?.asText() ==
                if (supported) ArenaCoordinatedPolicy.SUPPORTED_VERSION
                else ArenaCoordinatedPolicy.VERSION
        ) {
            "The declared support context must match its exact complete role version."
        }
        require(
            overlay["execution"]?.asText() == "CALIBRATED" &&
                (!supported || overlay["supportProfile"]?.asText() == "known-own-chaos-support-1")
        ) {
            "Every factorial role retains the original sealed selectors and explicit support setting."
        }
        fun subject(key: String) =
            TokenPairedSubject.read(overlay.required(key), source.path.parent)
        require(
            subject("originalBundle").sha256 == admission.scope.original.source.sha256 &&
                subject("baseline").sha256 == admission.scope.baseline.sha256 &&
                subject("calibration").sha256 == admission.calibration.source.sha256
        ) {
            "A mask cannot rebase original E/T anchors, prior or calibration."
        }
        return cell.roleMasks.mapValues { (role, mask) ->
            val descriptorSubject = subject(role.lowercase())
            val descriptor = PurchaseJson.read(descriptorSubject.path)
            PurchaseJson.fields(descriptor, setOf("kind", "heads"))
            require(descriptor["kind"]?.asText() == CoordinatedEsBundle.VERSION) {
                "A mask selects a complete immutable four-head descriptor."
            }
            PurchaseJson.fields(
                descriptor.required("heads"),
                CoordinatedEsHead.entries.map { it.name }.toSet(),
            )
            val expected =
                CoordinatedEsHead.entries.associate { head ->
                    val bundle =
                        if (mask and (1 shl head.ordinal) == 0) admission.scope.original
                        else admission.finalBundle
                    val component = descriptor["heads"].required(head.name)
                    PurchaseJson.fields(component, setOf("path", "sha256", "dictionarySha256"))
                    require(
                        component["sha256"]?.asText() == bundle.subject(head).sha256 &&
                            component["dictionarySha256"]?.asText() ==
                                bundle.dictionaries[head]?.sha256()
                    ) {
                        "Every mask bit must select its exact original or final frozen head and dictionary."
                    }
                    head.name to
                        mapOf(
                            "sha256" to bundle.subject(head).sha256,
                            "dictionarySha256" to
                                requireNotNull(bundle.dictionaries[head]) {
                                        "Complete mask dictionary."
                                    }
                                    .sha256(),
                        )
                }
            ExpansionOnPolicyStore.wire(expected)
        }
    }
}

object CoordinatedDiagnosticMain {
    private val delegate =
        ThreeRoleScreenMain(
            prefix = "coordinated-diagnostic",
            roles = listOf("CANDIDATE", "INCUMBENT"),
            rawRole = "INCUMBENT",
            rawName = "Complement",
            maximumSeconds = 14400,
            readInput = CoordinatedDiagnosticInput::read,
            historical = { _, _, _ ->
                error("A factorial diagnostic has no additional historical executions.")
            },
        )
    val modes =
        setOf("coordinated-diagnostic-screen-prepare", "coordinated-diagnostic-screen-chunk")

    fun run(arguments: List<String>) = delegate.run(arguments)
}
