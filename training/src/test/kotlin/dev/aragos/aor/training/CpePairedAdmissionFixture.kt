package dev.aragos.aor.training

import java.nio.file.Files

/** Hash-only invented transactions: no policy parsing, numerical update or scientific admission. */
internal class CpePairedAdmissionFixture : AutoCloseable {
    val root = Files.createTempDirectory("cpe-admission-unit-").toRealPath()
    private val scope = "a".repeat(64)
    private val ecology = "b".repeat(64)
    private val dictionary = "c".repeat(64)
    private val heads = CpeHead.entries.map { it.name }
    private val origins = List(640) { mapOf("future" to listOf(10000 + it, 20000 + it)) }
    private val seeds = (1..20).toList()
    private val originalModels = heads.associateWith {
        write("original/$it.json", mapOf("UNIT" to it))
    }
    val original = bundle("original/bundle.json", originalModels)
    val protocol =
        write(
            "protocol.json",
            mapOf(
                "kind" to "cpe-paired-campaign-1",
                "originalBundleSha256" to original.sha256,
                "bundleScopeSha256" to scope,
                "ecologySha256" to ecology,
                "heads" to
                    heads.associateWith {
                        mapOf(
                            "originalActorSha256" to model(originalModels, it).sha256,
                            "scopeSha256" to headScope(it),
                            "dictionarySha256" to dictionary,
                            "semanticDictionarySha256" to dictionary,
                        )
                    },
                "iterations" to
                    List(2) {
                        mapOf(
                            "heads" to
                                heads.associateWith {
                                    mapOf("origins" to origins, "shuffleSeeds" to seeds)
                                }
                        )
                    },
            ),
        )
    private val first = iteration(1, null)
    private val second = iteration(2, first)
    val source =
        write(
            "final-admission.json",
            mapOf(
                "kind" to CpePairedAdmission.VERSION,
                "protocol" to subject(protocol),
                "bundleScopeSha256" to scope,
                "ecologySha256" to ecology,
                "iterations" to listOf(subject(first.admission), subject(second.admission)),
                "originalBundle" to subject(original),
                "finalBundle" to subject(second.bundle),
                "completedIterations" to 2,
                "learnerHost" to "DESKTOP",
                "operationallyComplete" to true,
                "validationEffectsUsedForSelection" to false,
            ),
        )

    private data class Result(
        val admission: TokenPairedSubject,
        val bundle: TokenPairedSubject,
        val fit: TokenPairedSubject,
        val models: Map<String, TokenPairedSubject>,
        val children: Map<String, TokenPairedSubject>,
    )

    private fun iteration(number: Int, previous: Result?): Result {
        val prefix = "iteration-$number"
        val current = previous?.bundle ?: original
        val currentModels = previous?.models ?: originalModels
        val binding =
            mapOf(
                "originalBundleSha256" to original.sha256,
                "currentBundleSha256" to current.sha256,
                "bundleScopeSha256" to scope,
                "ecologySha256" to ecology,
            )
        val ready = mapOf("TRAIN" to 256, "VALIDATION" to 64)
        val cohorts = heads.associateWith { head ->
            write(
                "$prefix/$head-corpus.json",
                binding +
                    mapOf(
                        "kind" to "cpe-paired-corpus-1",
                        "head" to head,
                        "iteration" to number,
                        "complete" to true,
                        "exposurePassed" to true,
                        "origins" to 640,
                        "ready" to ready,
                        "protocolDigest" to
                            CpePairedDigest.bytes(
                                Files.readString(protocol.path).removeSuffix("\n")
                            ),
                        "scopeSha256" to headScope(head),
                    ),
            )
        }
        val corpus =
            write(
                "$prefix/corpus.json",
                binding +
                    mapOf(
                        "kind" to "cpe-paired-iteration-corpus-1",
                        "complete" to true,
                        "exposurePassed" to true,
                        "iteration" to number,
                        "independentOrigins" to 1920,
                        "protocolDigest" to
                            CpePairedDigest.bytes(
                                Files.readString(protocol.path).removeSuffix("\n")
                            ),
                        "cohorts" to cohorts.mapValues { subject(it.value) },
                    ),
            )
        val models = heads.associateWith {
            write("$prefix/fit/heads/$it/policy.json", mapOf("UNIT" to it, "iteration" to number))
        }
        val output = bundle("$prefix/fit/bundle.json", models)
        val contexts = heads.associateWith { head ->
            mapOf(
                "head" to head,
                "iteration" to number,
                "source_sha256" to model(cohorts, head).sha256,
                "scope_sha256" to headScope(head),
                "bundle_scope_sha256" to scope,
                "ecology_sha256" to ecology,
                "dictionary_sha256" to dictionary,
                "dictionary_semantic_sha256" to dictionary,
                "request_bank_sha256" to CpePairedDigest.json(origins),
                "original_model_sha256" to model(originalModels, head).sha256,
                "current_model_sha256" to model(currentModels, head).sha256,
                "original_bundle_sha256" to original.sha256,
                "current_bundle_sha256" to current.sha256,
            )
        }
        val predecessors = heads.associateWith { head ->
            previous?.let {
                mapOf(
                    "bundleFinishedSha256" to it.fit.sha256,
                    "headFinishedSha256" to model(it.children, head).sha256,
                    "modelSha256" to model(it.models, head).sha256,
                )
            }
        }
        val common =
            mapOf(
                "recipe" to "UNIT_HASH_ONLY",
                "sourceSha256" to emptyMap<String, String>(),
                "originalDeadline" to 1,
            )
        val children = heads.associateWith { head ->
            val childRoot = "$prefix/fit/heads/$head"
            val identity =
                common +
                    mapOf(
                        "kind" to "cpe-paired-head-identity-1",
                        "context" to contexts[head],
                        "epochSeeds" to seeds,
                        "trainingSha256" to dictionary,
                        "predecessor" to predecessors[head],
                        "publicCoverage" to mapOf("trainingReady" to 256, "validationReady" to 64),
                    )
            val identityFile = write("$childRoot/identity.json", identity)
            val checkpoint = write("$childRoot/checkpoint.json", mapOf("UNIT" to "no optimizer"))
            val history = write("$childRoot/history.json", emptyList<String>())
            write(
                "$childRoot/finished.json",
                mapOf(
                    "kind" to "cpe-paired-head-complete-1",
                    "complete" to true,
                    "operationalPassed" to true,
                    "actorChanged" to true,
                    "acceptedEpochs" to 1,
                    "outputModelSha256" to model(models, head).sha256,
                    "identity" to identity,
                    "artifacts" to
                        mapOf(
                            "identity.json" to identityFile.sha256,
                            "policy.json" to model(models, head).sha256,
                            "checkpoint.json" to checkpoint.sha256,
                            "history.json" to history.sha256,
                        ),
                ),
            )
        }
        val identity =
            common +
                binding +
                mapOf(
                    "kind" to "cpe-paired-bundle-identity-1",
                    "iteration" to number,
                    "contexts" to contexts,
                    "epochSeeds" to heads.associateWith { seeds },
                    "trainingSha256" to heads.associateWith { dictionary },
                    "predecessors" to predecessors,
                    "previousFinishedSha256" to previous?.fit?.sha256,
                )
        val identityFile = write("$prefix/fit/identity.json", identity)
        val files =
            mapOf("identity.json" to identityFile.sha256, "bundle.json" to output.sha256) +
                heads
                    .flatMap { head ->
                        listOf(
                                "identity.json",
                                "policy.json",
                                "checkpoint.json",
                                "history.json",
                                "finished.json",
                            )
                            .map { name ->
                                "heads/$head/$name" to
                                    PurchaseJson.hash(root.resolve("$prefix/fit/heads/$head/$name"))
                            }
                    }
                    .toMap()
        val fit =
            write(
                "$prefix/fit/finished.json",
                mapOf(
                    "kind" to "cpe-paired-bundle-complete-1",
                    "complete" to true,
                    "operationalPassed" to true,
                    "outputBundleSha256" to output.sha256,
                    "identity" to identity,
                    "artifacts" to files,
                ),
            )
        val seal =
            write(
                "$prefix/validation.json",
                binding +
                    mapOf(
                        "kind" to "cpe-paired-validation-probabilities-1",
                        "complete" to true,
                        "semanticLabelsDecoded" to false,
                        "iteration" to number,
                        "fitFinishedSha256" to fit.sha256,
                        "bundleSha256" to output.sha256,
                        "heads" to
                            heads.associateWith { head ->
                                mapOf(
                                    "context" to contexts[head],
                                    "modelSha256" to model(models, head).sha256,
                                    "counts" to mapOf("READY" to 64, "MISSING" to 64),
                                    "rows" to
                                        (512 until 640).map { index ->
                                            mapOf(
                                                "index" to index,
                                                "status" to if (index < 576) "READY" else "MISSING",
                                                "publicSha256" to dictionary,
                                                "requestSha256" to dictionary,
                                                "futureSeeds" to origins[index]["future"],
                                                "referenceIndex" to if (index < 576) 0 else null,
                                                "proposedIndices" to
                                                    if (index < 576) listOf(0, 1) else null,
                                                "inclusionProbability" to
                                                    if (index < 576) 1.0 else null,
                                                "currentProbabilities" to
                                                    if (index < 576) listOf(.4, .6) else null,
                                                "probabilities" to
                                                    if (index < 576) listOf(.5, .5) else null,
                                            )
                                        },
                                )
                            },
                    ),
            )
        val admission =
            write(
                "$prefix/admission.json",
                binding.filterKeys { it in setOf("bundleScopeSha256", "ecologySha256") } +
                    mapOf(
                        "kind" to "cpe-paired-iteration-admission-1",
                        "iteration" to number,
                        "protocolSha256" to protocol.sha256,
                        "currentBundle" to subject(current),
                        "outputBundle" to subject(output),
                        "originalBundle" to subject(original),
                        "corpus" to subject(corpus),
                        "cohorts" to cohorts.mapValues { subject(it.value) },
                        "fitCompletion" to subject(fit),
                        "validationProbabilitySeal" to subject(seal),
                        "previousAdmission" to previous?.let { subject(it.admission) },
                        "ready" to heads.associateWith { ready },
                        "acceptedEpochs" to heads.associateWith { 1 },
                        "allHeadsChanged" to true,
                    ),
            )
        return Result(admission, output, fit, models, children)
    }

    private fun bundle(name: String, models: Map<String, TokenPairedSubject>): TokenPairedSubject {
        val owner = root.resolve(name).parent
        return write(
            name,
            mapOf(
                "kind" to CpePairedBundle.VERSION,
                "heads" to
                    models.mapValues { (_, model) ->
                        mapOf(
                            "path" to owner.relativize(model.path).toString(),
                            "sha256" to model.sha256,
                            "dictionarySha256" to dictionary,
                        )
                    },
            ),
        )
    }

    fun write(name: String, value: Any): TokenPairedSubject {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        if (name == "protocol.json") {
            Files.writeString(path, PurchaseJson.mapper.writeValueAsString(value) + "\n")
        } else {
            TrainingArtifacts(path.parent).write(path.fileName.toString(), value)
        }
        return TokenPairedSubject(path.toRealPath(), PurchaseJson.hash(path), path.toString())
    }

    private fun model(values: Map<String, TokenPairedSubject>, head: String) =
        requireNotNull(values[head]) { "Fixture head required." }

    private fun subject(value: TokenPairedSubject) =
        mapOf("path" to value.path.toString(), "sha256" to value.sha256)

    private fun headScope(head: String) =
        CpePairedDigest.json(mapOf("bundleScopeSha256" to scope, "head" to head))

    override fun close() {
        root.toFile().deleteRecursively()
    }
}
