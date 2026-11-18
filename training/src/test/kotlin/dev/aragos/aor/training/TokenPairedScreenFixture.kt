package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Invented complete receipts and hand-constructed actors; no simulated world or fit is run. */
internal class TokenPairedScreenFixture : AutoCloseable {
    val base = TokenPairedFixture()
    val root = base.files.directory
    val source = root.resolve("screen-source.json")
    val finalAdmission = root.resolve("final-admission.json")
    val protocol = root.resolve("protocol.json")
    val original = base.actor
    val input = base.input
    val scope = input.scopeSha256
    val ecology = input.ecologySha256
    val originalHash = PurchaseJson.hash(original)
    val dictionaryHash = "d".repeat(64)
    val finalPolicy = root.resolve("iteration-2/fit/policy.json")

    init {
        base.prior.editArena {
            put("pairsPerConfiguration", 1024)
            put("seed", 719)
        }
        write(
            protocol,
            mapOf(
                "kind" to "token-paired-campaign-1",
                "originalActorSha256" to originalHash,
                "ecologySha256" to ecology,
                "dictionarySha256" to dictionaryHash,
                "screenBank" to
                    mapOf(
                        "nativeOffset" to 719,
                        "count" to 1024,
                        "seeds" to List(1024) { StrategyArena.seed(719, it) },
                    ),
                "iterations" to
                    (1..2).map { iteration ->
                        mapOf(
                            "origins" to
                                List(640) {
                                    TokenPairedSeeds(
                                        it + iteration * 1000,
                                        10000L + it,
                                        listOf(20000L + it, 30000L + it),
                                    )
                                },
                            "shuffleSeeds" to List(20) { iteration * 100 + it },
                        )
                    },
            ),
        )
        iteration(1, original, null)
        iteration(
            2,
            root.resolve("iteration-1/fit/policy.json"),
            root.resolve("iteration-1/admission.json"),
        )
        write(
            finalAdmission,
            mapOf(
                "kind" to TokenPairedAdmission.VERSION,
                "protocol" to subject(protocol, finalAdmission),
                "scopeSha256" to scope,
                "iterations" to
                    (1..2).map {
                        subject(root.resolve("iteration-$it/admission.json"), finalAdmission)
                    },
                "finalActor" to subject(finalPolicy, finalAdmission),
                "originalActor" to subject(original, finalAdmission),
                "ecologySha256" to ecology,
                "completedIterations" to 2,
                "learnerHost" to "DESKTOP",
                "operationallyComplete" to true,
                "validationEffectsUsedForSelection" to false,
            ),
        )
        write(
            root.resolve("historical-admission.json"),
            mapOf(
                "kind" to "token-paired-historical-admission-1",
                "complete" to true,
                "controls" to 2,
                "zeroFailures" to true,
                "protocolSha256" to PurchaseJson.hash(protocol),
                "scopeSha256" to scope,
                "ecologySha256" to ecology,
                "scientificTerminalExecutions" to 2,
                "records" to List(2) { mapOf("constructed" to it) },
            ),
        )
        source(0)
    }

    fun source(start: Int) =
        write(
            source,
            mapOf(
                "kind" to TokenPairedScreenInput.VERSION,
                "arena" to subject(base.prior.arenaSource, source),
                "admission" to subject(finalAdmission, source),
                "historicalAdmission" to subject(root.resolve("historical-admission.json"), source),
                "globalStart" to start,
                "count" to 128,
            ),
        )

    fun readAdmission(): TokenPairedAdmission =
        TokenPairedAdmission.read(
            TokenPairedSubject(
                finalAdmission,
                PurchaseJson.hash(finalAdmission),
                finalAdmission.toString(),
            )
        )

    fun editAdmission(index: Int, change: ObjectNode.() -> Unit) {
        val path = root.resolve("iteration-$index/admission.json")
        val node = PurchaseJson.read(path) as ObjectNode
        node.change()
        write(path, node)
        val final = PurchaseJson.read(finalAdmission) as ObjectNode
        final.set<JsonNode>(
            "iterations",
            PurchaseJson.mapper.valueToTree(
                (1..2).map { subject(root.resolve("iteration-$it/admission.json"), finalAdmission) }
            ),
        )
        write(finalAdmission, final)
    }

    private fun iteration(number: Int, current: Path, previous: Path?) {
        val directory = root.resolve("iteration-$number")
        val admission = directory.resolve("admission.json")
        val policy = directory.resolve("fit/policy.json")
        val model = PurchaseJson.read(original) as ObjectNode
        model.set<JsonNode>(
            "actorWeights",
            PurchaseJson.mapper.valueToTree(List(64) { number * .01 }),
        )
        write(policy, model)
        val corpus = directory.resolve("corpus.json")
        val ready = mapOf("TRAIN" to 300, "VALIDATION" to 80)
        val science = number.toString().repeat(64)
        write(
            corpus,
            mapOf(
                "kind" to "token-paired-corpus-1",
                "iteration" to number,
                "complete" to true,
                "exposurePassed" to true,
                "origins" to 640,
                "trainOrigins" to 512,
                "validationOrigins" to 128,
                "scopeSha256" to scope,
                "scienceSha256" to science,
                "currentActorSha256" to PurchaseJson.hash(current),
                "originalActorSha256" to originalHash,
                "ready" to ready,
                "historicalReproductions" to 4,
                "semanticValidationLabelsDecoded" to false,
                "chunks" to List(10) { mapOf("constructed" to it) },
                "scienceIdentity" to
                    mapOf(
                        "origins" to
                            PurchaseJson.read(protocol)["iterations"][number - 1]["origins"]
                    ),
            ),
        )
        val context =
            mapOf(
                "iteration" to number,
                "source_sha256" to PurchaseJson.hash(corpus),
                "scope_sha256" to scope,
                "current_model_sha256" to PurchaseJson.hash(current),
                "original_model_sha256" to originalHash,
                "dictionary_sha256" to dictionaryHash,
            )
        val predecessor = previous?.let { old ->
            val node = PurchaseJson.read(old)
            mapOf(
                "finishedSha256" to node["fitCompletion"]["sha256"].asText(),
                "modelSha256" to node["outputActor"]["sha256"].asText(),
            )
        }
        val identity =
            mapOf(
                "context" to context,
                "predecessor" to predecessor,
                "epochSeeds" to
                    PurchaseJson.read(protocol)["iterations"][number - 1]["shuffleSeeds"],
                "publicCoverage" to mapOf("trainingReady" to 300, "validationReady" to 80),
            )
        write(directory.resolve("fit/identity.json"), identity)
        write(
            directory.resolve("fit/checkpoint.json"),
            mapOf("constructedOpaqueCheckpoint" to true),
        )
        write(directory.resolve("fit/history.json"), emptyList<Any>())
        val done = directory.resolve("fit/finished.json")
        write(
            done,
            mapOf(
                "kind" to "token-paired-fit-complete-1",
                "complete" to true,
                "operationalPassed" to true,
                "actorChanged" to true,
                "outputModelSha256" to PurchaseJson.hash(policy),
                "acceptedEpochs" to 20,
                "identity" to identity,
                "artifacts" to
                    listOf("identity.json", "checkpoint.json", "history.json", "policy.json")
                        .associateWith {
                            PurchaseJson.hash(directory.resolve("fit/$it"))
                        },
            ),
        )
        val seal = directory.resolve("validation.json")
        write(
            seal,
            mapOf(
                "kind" to "token-paired-validation-probabilities-1",
                "complete" to true,
                "semanticLabelsDecoded" to false,
                "context" to context,
                "fitFinishedSha256" to PurchaseJson.hash(done),
                "modelSha256" to PurchaseJson.hash(policy),
                "originalModelSha256" to originalHash,
                "currentModelSha256" to PurchaseJson.hash(current),
                "counts" to mapOf("READY" to 80, "MISSING" to 48, "SINGLETON" to 0),
                "rows" to
                    (512 until 640).map { index ->
                        mapOf(
                            "index" to index,
                            "futureSeeds" to
                                PurchaseJson.read(protocol)["iterations"][number - 1]["origins"][
                                    index]["future"],
                            "status" to if (index < 592) "READY" else "MISSING",
                            "publicSha256" to index.toString(16).padStart(64, '0'),
                            "requestSha256" to index.toString(16).padStart(64, '1'),
                            "proposedIndices" to if (index < 592) listOf(0, 1) else emptyList(),
                            "inclusionProbability" to if (index < 592) 1.0 else 0.0,
                            "currentProbabilities" to
                                if (index < 592) listOf(.97, .03) else emptyList(),
                            "probabilities" to if (index < 592) listOf(.96, .04) else null,
                        )
                    },
            ),
        )
        write(
            admission,
            mapOf(
                "kind" to "token-paired-iteration-admission-1",
                "iteration" to number,
                "scopeSha256" to scope,
                "scienceSha256" to science,
                "protocolSha256" to PurchaseJson.hash(protocol),
                "currentActor" to subject(current, admission),
                "outputActor" to subject(policy, admission),
                "originalActor" to subject(original, admission),
                "corpus" to subject(corpus, admission),
                "fitCompletion" to subject(done, admission),
                "validationProbabilitySeal" to subject(seal, admission),
                "previousAdmission" to previous?.let { subject(it, admission) },
                "ready" to ready,
                "acceptedEpochs" to 20,
                "actorChanged" to true,
            ),
        )
    }

    fun write(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        TrainingArtifacts(path.parent).write(path.fileName.toString(), value)
    }

    fun subject(path: Path, owner: Path): Map<String, String> =
        mapOf(
            "path" to owner.parent.relativize(path).toString(),
            "sha256" to PurchaseJson.hash(path),
        )

    override fun close() = base.close()
}
