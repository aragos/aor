package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Files
import java.nio.file.Path

internal data class TokenPairedFile(val file: String, val sha256: String)

/** A worker retains only these hashes and counts after lossless origin publication. */
internal data class TokenPairedReceipt(
    val globalIndex: Int,
    val status: TokenPairedStatus,
    val origin: TokenPairedFile,
    val public: TokenPairedFile,
    val labels: TokenPairedFile,
    val tailGames: Int,
    val anchorRows: Int,
)

/** Immutable per-origin evidence with physically separate public and outcome boundaries. */
internal class TokenPairedStore(private val input: TokenPairedInput, val directory: Path) {
    private val artifacts = TrainingArtifacts(directory)

    fun prepare() {
        input.snapshot(artifacts)
        val snapshots =
            Files.walk(directory).use { paths ->
                paths.filter(Files::isRegularFile).sorted().toList().associate { path ->
                    directory.relativize(path).toString() to PurchaseJson.hash(path)
                }
            }
        artifacts.write(
            "dictionary.json",
            mapOf(
                "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
                "stateFeatureNames" to input.dictionary.stateFeatureNames,
                "actionFeatureNames" to input.dictionary.actionFeatureNames,
            ),
        )
        artifacts.write(
            "prepared.json",
            mapOf(
                "kind" to TokenPairedInput.VERSION,
                "signature" to input.signature,
                "scopeSha256" to input.scopeSha256,
                "scienceSha256" to input.scienceSha256,
                "scienceIdentity" to input.scienceIdentity,
                "ecologySha256" to input.ecologySha256,
                "ecologyIdentity" to input.ecologyIdentity,
                "iteration" to input.iteration,
                "range" to input.range,
                "requests" to input.range.indices.map(::TokenPairedRequest),
                "origins" to input.origins,
                "replayIndices" to input.replayIndices,
                "ownedReplayIndices" to input.ownedReplayIndices,
                "configuration" to input.configuration.id,
                "source" to input.source.metadata("source.json"),
                "arena" to input.arenaSource.metadata("arena.json"),
                "currentActor" to input.currentActor.metadata("current-actor.json"),
                "originalActor" to input.originalActor.metadata("original-actor.json"),
                "policyBindings" to input.policyBindings,
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

    fun save(origin: TokenPairedOrigin): TokenPairedReceipt {
        val index = origin.request.globalIndex
        val wire = PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(origin))
        validate(wire, index)
        val name = "origin-$index.json.gz"
        require(!Files.exists(directory.resolve(name))) { "Token origins cannot be overwritten." }
        CardTrajectoryFiles.save(directory.resolve(name), wire)
        check(CardTrajectoryFiles.readIfPresent(directory.resolve(name)) == wire) {
            "Compressed token evidence must preserve every original field and signed stream."
        }
        val original = subject(name)
        val publicName = "public-$index.json"
        val labelsName = "labels-$index.json"
        require(
            !Files.exists(directory.resolve(publicName)) &&
                !Files.exists(directory.resolve(labelsName))
        ) {
            "Token public and label sidecars cannot be overwritten."
        }
        artifacts.write(publicName, publicSidecar(wire, original.sha256))
        artifacts.write(labelsName, labelSidecar(wire, original.sha256))
        return TokenPairedReceipt(
            index,
            origin.status,
            original,
            subject(publicName),
            subject(labelsName),
            origin.arms.sumOf { it.outcomes.size },
            origin.anchors.size,
        )
    }

    fun finish(receipts: List<TokenPairedReceipt>) {
        require(receipts.map { it.globalIndex }.sorted() == input.range.indices.toList()) {
            "Every original global request must complete exactly once, without refill."
        }
        receipts.forEach { receipt ->
            listOf(receipt.origin, receipt.public, receipt.labels).forEach { file ->
                require(subject(file.file) == file) { "Completed token evidence changed." }
            }
            val origin =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(directory.resolve(receipt.origin.file))
                ) {
                    "The complete token origin must be retained."
                }
            validate(origin, receipt.globalIndex)
            require(
                PurchaseJson.read(directory.resolve(receipt.public.file)) ==
                    publicSidecar(origin, receipt.origin.sha256) &&
                    PurchaseJson.read(directory.resolve(receipt.labels.file)) ==
                        labelSidecar(origin, receipt.origin.sha256)
            ) {
                "Public and label sidecars must be the exact disjoint projections of their origin."
            }
        }
        input.snapshot(artifacts)
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "token-paired-finished-1",
                "signature" to input.signature,
                "scopeSha256" to input.scopeSha256,
                "scienceSha256" to input.scienceSha256,
                "complete" to true,
                "iteration" to input.iteration,
                "range" to input.range,
                "origins" to receipts.sortedBy { it.globalIndex },
                "backboneGames" to receipts.size,
                "readyRoots" to receipts.count { it.status == TokenPairedStatus.READY },
                "singletonRoots" to receipts.count { it.status == TokenPairedStatus.SINGLETON },
                "missingRoots" to receipts.count { it.status == TokenPairedStatus.MISSING },
                "capturedRoots" to receipts.count { it.status != TokenPairedStatus.MISSING },
                "tailGames" to receipts.sumOf { it.tailGames },
                "scientificTerminalGames" to receipts.sumOf { 1 + it.tailGames },
            ),
        )
    }

    fun readCompleted(index: Int): Pair<JsonNode, TokenPairedFile> {
        require(index in input.ownedReplayIndices) {
            "Only prescribed global reproductions are admitted."
        }
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        require(
            finished["complete"]?.booleanValue() == true &&
                finished["signature"]?.asText() == input.signature
        ) {
            "Reproduction requires this exact completed source shard."
        }
        val receipt = finished["origins"].single { it["globalIndex"].intValue() == index }
        val file =
            TokenPairedFile(
                receipt["origin"]["file"].asText(),
                receipt["origin"]["sha256"].asText(),
            )
        require(file.file == "origin-$index.json.gz" && subject(file.file) == file) {
            "Reproduction must use the exact original raw origin bytes."
        }
        val origin =
            requireNotNull(CardTrajectoryFiles.readIfPresent(directory.resolve(file.file))) {
                "The original complete evidence is required."
            }
        validate(origin, index)
        return origin to file
    }

    private fun subject(name: String) =
        TokenPairedFile(name, PurchaseJson.hash(directory.resolve(name)))

    internal fun validate(origin: JsonNode, index: Int) {
        require(index in input.range.indices) { "An origin index must belong to its source shard." }
        require(
            origin["kind"]?.asText() == "token-paired-origin-1" &&
                origin["signature"]?.asText() == input.signature &&
                origin["scopeSha256"]?.asText() == input.scopeSha256 &&
                origin["scienceSha256"]?.asText() == input.scienceSha256 &&
                origin["iteration"]?.intValue() == input.iteration &&
                origin["request"] == wire(TokenPairedRequest(index)) &&
                origin["streams"] == wire(input.origins[index])
        ) {
            "Origin source, global request and exact streams must match their immutable input."
        }
        val prefix = origin.required("prefix")
        val backbone = origin.required("backbone")
        for (canonical in listOf(prefix, backbone)) {
            require(
                canonical["replayVerified"]?.booleanValue() == true &&
                    canonical["canonicalSha256"]?.asText() ==
                        CardAdvantageExecutor.hash(canonical["entries"].map(JsonNode::asText))
            ) {
                "Canonical prefix and backbone hashes must bind their verified exact entries."
            }
        }
        require(
            backbone["terminal"].booleanValue() &&
                prefix["header"] == backbone["header"] &&
                backbone["entries"].take(prefix["entries"].size()) == prefix["entries"].toList() &&
                origin["backboneTerminal"]["continuationSeed"].longValue() ==
                    input.origins[index].gameSeed.toLong() &&
                origin["backboneTerminal"]["decisions"] == backbone["playerDecisions"]
        ) {
            "Every origin retains one complete current-policy backbone and its exact prefix."
        }
        val anchors = origin.required("anchors")
        require(input.iteration == 1 && index < 512 || anchors.isEmpty) {
            "Only original iteration-one TRAIN backbone states belong in the fixed anchor."
        }
        require(anchors.map { it["number"].intValue() }.let { it == it.sorted().distinct() }) {
            "Anchor decision identities must be unique and chronological."
        }
        anchors.forEach { anchor ->
            require(
                anchor["iteration"].intValue() == input.iteration &&
                    anchor["globalIndex"].intValue() == index
            ) {
                "An anchor must retain its original independent world identity."
            }
            probabilities(anchor["originalProbabilities"], anchor["observation"]["actions"].size())
        }
        val root = origin.required("publicRoot")
        val arms = origin.required("arms")
        val status = origin["status"]?.asText()
        if (status == TokenPairedStatus.MISSING.name) {
            require(
                root.isNull &&
                    arms.isEmpty &&
                    prefix == backbone &&
                    origin["missingReason"].asText() == "TARGET_WINDOW_NOT_REACHED"
            ) {
                "Missing requests retain their original backbone and anchors without invented labels."
            }
            return
        }
        require(
            status in setOf(TokenPairedStatus.READY.name, TokenPairedStatus.SINGLETON.name) &&
                !root.isNull &&
                origin["missingReason"].isNull
        ) {
            "A captured token root requires its public record and finite declared status."
        }
        val actions = root["observation"]["actions"]
        val reference = root["referenceIndex"].intValue()
        val proposals =
            TokenPairedProposals.sample(
                actions.size(),
                reference,
                input.origins[index].proposalSeed,
            )
        require(
            root["observation"]["baselineIndex"].intValue() == reference &&
                root["proposedIndices"].map(JsonNode::intValue) == proposals.indices &&
                root["inclusionProbability"].doubleValue() == proposals.inclusionProbability &&
                root["sampledCommandIndex"].intValue() in 0 until actions.size() &&
                root["number"].intValue() >= root["batchStartNumber"].intValue()
        ) {
            "Reference, sampled action and proposal inclusion are distinct exact public quantities."
        }
        probabilities(root["currentProbabilities"], actions.size())
        probabilities(root["originalProbabilities"], actions.size())
        if (status == TokenPairedStatus.SINGLETON.name) {
            require(actions.size() == 1 && arms.isEmpty) {
                "Singleton windows have no paired action deltas."
            }
            return
        }
        require(
            actions.size() > 1 && arms.map { it["actionIndex"].intValue() } == proposals.indices
        ) {
            "Ready origins require every predeclared reference/proposal arm exactly once."
        }
        arms.forEach { arm ->
            val actionIndex = arm["actionIndex"].intValue()
            require(
                arm["command"] == actions[actionIndex]["command"] &&
                    arm["outcomes"].size() == 2 &&
                    arm["inclusionProbability"].doubleValue() ==
                        if (actionIndex == reference) 1.0 else proposals.inclusionProbability
            ) {
                "Each arm binds its original offered index, command, inclusion probability and two streams."
            }
            arm["outcomes"].forEachIndexed { replicate, outcome ->
                require(
                    outcome["replicate"].intValue() == replicate &&
                        outcome["terminal"]["continuationSeed"].longValue() ==
                            input.origins[index].future[replicate] &&
                        outcome["replayVerified"].booleanValue() &&
                        outcome["canonicalSha256"].asText() ==
                            CardAdvantageExecutor.hash(
                                outcome["canonicalEntries"].map(JsonNode::asText)
                            )
                ) {
                    "Every paired terminal requires its shared future stream and verified canonical suffix."
                }
            }
        }
    }

    companion object {
        private fun wire(value: Any): JsonNode =
            PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

        private fun probabilities(node: JsonNode, count: Int) {
            val values = node.map(JsonNode::doubleValue)
            require(
                values.size == count &&
                    values.all { it.isFinite() && it > 0.0 && it <= 1.0 } &&
                    kotlin.math.abs(values.sum() - 1.0) < 1e-9
            ) {
                "Token probability evidence requires complete finite support."
            }
        }

        private fun common(origin: JsonNode, hash: String, kind: String): ObjectNode =
            PurchaseJson.mapper.createObjectNode().also { result ->
                result.put("kind", kind)
                result.put("originSha256", hash)
                listOf(
                        "signature",
                        "scopeSha256",
                        "scienceSha256",
                        "iteration",
                        "request",
                        "streams",
                        "status",
                    )
                    .forEach { key -> result.set<JsonNode>(key, origin.required(key)) }
            }

        internal fun publicSidecar(origin: JsonNode, hash: String): ObjectNode =
            common(origin, hash, "token-paired-public-1").also { result ->
                listOf("missingReason", "observedWindows", "publicRoot", "anchors").forEach { key ->
                    result.set<JsonNode>(key, origin.required(key))
                }
            }

        internal fun labelSidecar(origin: JsonNode, hash: String): ObjectNode =
            common(origin, hash, "token-paired-labels-1").also { result ->
                result.set<JsonNode>(
                    "arms",
                    wire(
                        origin["arms"].map { arm ->
                            mapOf(
                                "actionIndex" to arm["actionIndex"],
                                "command" to arm["command"],
                                "inclusionProbability" to arm["inclusionProbability"],
                                "outcomes" to
                                    arm["outcomes"].map { outcome ->
                                        mapOf(
                                            "replicate" to outcome["replicate"],
                                            "terminal" to outcome["terminal"],
                                            "canonicalSha256" to outcome["canonicalSha256"],
                                            "replayVerified" to outcome["replayVerified"],
                                        )
                                    },
                            )
                        }
                    ),
                )
            }
    }
}
