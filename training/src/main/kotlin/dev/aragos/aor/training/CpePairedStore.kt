package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Workers retain only small immutable receipts after publishing each complete original world. */
internal data class CpePairedReceipt(
    val globalIndex: Int,
    val status: CpePairedStatus,
    val origin: TokenPairedFile,
    val public: TokenPairedFile,
    val labels: TokenPairedFile,
    val tailGames: Int,
    val anchorRows: Int,
)

/** Paired evidence uses existing lossless compression and disjoint sidecar projection owners. */
internal class CpePairedStore(private val input: CpePairedInput, val directory: Path) {
    private val artifacts = TrainingArtifacts(directory)
    private val evidence = CpePairedEvidence(input)

    fun prepare() {
        input.snapshot(artifacts)
        val snapshots =
            Files.walk(directory).use { paths ->
                paths.filter(Files::isRegularFile).sorted().toList().associate { path ->
                    directory.relativize(path).toString() to PurchaseJson.hash(path)
                }
            }
        val dictionary =
            requireNotNull(input.dictionaries[input.head]) { "Cohort dictionary is required." }
        artifacts.write("dictionary.json", dictionary.wire())
        artifacts.write(
            "prepared.json",
            mapOf(
                "kind" to CpePairedInput.VERSION,
                "signature" to input.signature,
                "bundleScopeSha256" to input.bundleScopeSha256,
                "scopeSha256" to input.scopeSha256,
                "scienceSha256" to input.scienceSha256,
                "scienceIdentity" to input.scienceIdentity,
                "ecologySha256" to input.ecologySha256,
                "ecologyIdentity" to input.ecologyIdentity,
                "head" to input.head,
                "iteration" to input.iteration,
                "range" to input.range,
                "requests" to input.range.indices.map(input::request),
                "origins" to input.origins,
                "replayIndices" to input.replayIndices,
                "ownedReplayIndices" to input.ownedReplayIndices,
                "configuration" to input.configuration.id,
                "source" to input.source.metadata("source.json"),
                "arena" to input.arenaSource.metadata("arena.json"),
                "currentBundle" to bundleMetadata(input.current, "current"),
                "originalBundle" to bundleMetadata(input.original, "original"),
                "currentBundleIdentity" to input.current.identity(),
                "originalBundleIdentity" to input.original.identity(),
                "policyBindings" to input.policyBindings,
                "dictionaries" to
                    input.dictionaries.mapKeys { it.key.name }.mapValues { it.value.wire() },
                "dictionarySha256" to dictionary.sha256(),
                "dictionary" to
                    mapOf(
                        "path" to "dictionary.json",
                        "sha256" to PurchaseJson.hash(directory.resolve("dictionary.json")),
                    ),
                "snapshots" to snapshots,
                "maximumTerminalGames" to input.range.count * 19,
                "fits" to 0,
            ),
        )
    }

    fun save(origin: CpePairedOrigin): CpePairedReceipt {
        val index = origin.request.globalIndex
        val wire = wire(origin)
        evidence.validate(wire, index)
        val names = listOf("origin-$index.json.gz", "public-$index.json", "labels-$index.json")
        require(names.none { Files.exists(directory.resolve(it)) }) {
            "C/P/E origins and sidecars cannot be overwritten."
        }
        CardTrajectoryFiles.save(directory.resolve(names[0]), wire)
        check(CardTrajectoryFiles.readIfPresent(directory.resolve(names[0])) == wire) {
            "Compressed evidence must preserve all original fields and signed streams."
        }
        val original = subject(names[0])
        artifacts.write(names[1], publicSidecar(wire, original.sha256))
        artifacts.write(names[2], labelSidecar(wire, original.sha256))
        return CpePairedReceipt(
            index,
            origin.status,
            original,
            subject(names[1]),
            subject(names[2]),
            origin.arms.sumOf { it.outcomes.size },
            origin.anchors.size,
        )
    }

    fun finish(receipts: List<CpePairedReceipt>) {
        require(receipts.map { it.globalIndex }.sorted() == input.range.indices.toList()) {
            "Every original cohort index must complete exactly once, without refill."
        }
        receipts.forEach { receipt ->
            listOf(receipt.origin, receipt.public, receipt.labels).forEach { file ->
                require(subject(file.file) == file) { "Completed C/P/E evidence changed." }
            }
            val origin =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(directory.resolve(receipt.origin.file))
                ) {
                    "A complete original is required."
                }
            evidence.validate(origin, receipt.globalIndex)
            require(
                PurchaseJson.read(directory.resolve(receipt.public.file)) ==
                    publicSidecar(origin, receipt.origin.sha256) &&
                    PurchaseJson.read(directory.resolve(receipt.labels.file)) ==
                        labelSidecar(origin, receipt.origin.sha256)
            ) {
                "Sidecars must remain exact disjoint projections of the original bytes."
            }
            require(
                origin["status"].asText() == receipt.status.name &&
                    origin["arms"].sumOf { it["outcomes"].size() } == receipt.tailGames &&
                    origin["anchors"].size() == receipt.anchorRows
            ) {
                "Receipt counts must match each complete original world."
            }
        }
        input.snapshot(artifacts)
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "cpe-paired-finished-1",
                "signature" to input.signature,
                "bundleScopeSha256" to input.bundleScopeSha256,
                "scopeSha256" to input.scopeSha256,
                "scienceSha256" to input.scienceSha256,
                "head" to input.head,
                "complete" to true,
                "iteration" to input.iteration,
                "range" to input.range,
                "origins" to receipts.sortedBy { it.globalIndex },
                "backboneGames" to receipts.size,
                "readyRoots" to receipts.count { it.status == CpePairedStatus.READY },
                "missingRoots" to receipts.count { it.status == CpePairedStatus.MISSING },
                "capturedRoots" to receipts.count { it.status == CpePairedStatus.READY },
                "tailGames" to receipts.sumOf { it.tailGames },
                "scientificTerminalGames" to receipts.sumOf { 1 + it.tailGames },
            ),
        )
    }

    fun readCompleted(index: Int): Pair<JsonNode, TokenPairedFile> {
        require(index in input.ownedReplayIndices) {
            "Only the four predetermined cohort reproductions are allowed."
        }
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        require(
            finished["complete"]?.booleanValue() == true &&
                finished["signature"]?.asText() == input.signature &&
                finished["head"]?.asText() == input.head.name
        ) {
            "Reproduction requires this exact completed cohort shard."
        }
        val receipt = finished["origins"].single { it["globalIndex"].intValue() == index }
        val file =
            TokenPairedFile(
                receipt["origin"]["file"].asText(),
                receipt["origin"]["sha256"].asText(),
            )
        require(file.file == "origin-$index.json.gz" && subject(file.file) == file) {
            "Reproduction requires exact original raw evidence."
        }
        val origin =
            requireNotNull(CardTrajectoryFiles.readIfPresent(directory.resolve(file.file))) {
                "Original evidence is required."
            }
        evidence.validate(origin, index)
        return origin to file
    }

    private fun subject(name: String) =
        TokenPairedFile(name, PurchaseJson.hash(directory.resolve(name)))

    private fun bundleMetadata(bundle: CpePairedBundle, prefix: String): Map<String, Any> =
        bundle.source.metadata("$prefix/bundle.json") +
            mapOf(
                "heads" to
                    CpeHead.entries.associate { head ->
                        head.name to
                            bundle.subject(head).metadata("$prefix/${head.name.lowercase()}.json")
                    }
            )

    companion object {
        private fun wire(value: Any): JsonNode =
            PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

        internal fun publicSidecar(origin: JsonNode, hash: String): ObjectNode =
            TokenPairedStore.publicSidecar(origin, hash).also { result ->
                result.put("kind", "cpe-paired-public-1")
                listOf("bundleScopeSha256", "branchingWindows", "selectedOrdinal").forEach { field
                    ->
                    result.set<JsonNode>(field, origin.required(field))
                }
            }

        internal fun labelSidecar(origin: JsonNode, hash: String): ObjectNode =
            TokenPairedStore.labelSidecar(origin, hash).also { result ->
                result.put("kind", "cpe-paired-labels-1")
                result.set<JsonNode>("bundleScopeSha256", origin.required("bundleScopeSha256"))
            }
    }
}
