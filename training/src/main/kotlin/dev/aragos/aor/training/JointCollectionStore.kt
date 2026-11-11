package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Complete-game snapshots are immutable evidence. Only operational state may be replaced; a
 * matching signature is required before restoring missing archives after an interrupted start. The
 * caller holds the directory's exclusive joint lock for this store's lifetime.
 */
internal class JointCollectionStore(
    private val input: JointCollectionInput,
    val directory: Path,
    val signature: String,
) {
    private data class Totals(
        val reward: Double,
        val winShare: Double,
        val rankReward: Double,
        val decisions: Int,
    )

    private val artifacts = TrainingArtifacts(directory)
    private val validation = JointTrajectoryValidation(input, signature)
    private val identity = mapOf("kind" to "joint-collect", "signature" to signature)
    val startedAt: Instant

    init {
        val previous = readIfPresent("state.json")
        if (previous != null) {
            require(
                previous["kind"]?.asText() == "joint-collect" &&
                    previous["signature"]?.asText() == signature
            ) {
                "Joint source, code, dictionaries or model bytes changed; use a new output directory."
            }
            require(previous["phase"]?.asText() in setOf("initialized", "collecting", "complete")) {
                "A joint checkpoint has an unknown operational phase."
            }
            startedAt =
                Instant.parse(PurchaseJson.text(previous.required("startedAt"), "startedAt"))
        } else {
            Files.list(directory).use { paths ->
                require(
                    paths.allMatch { path ->
                        path.fileName.toString() in
                            setOf(
                                "supervisor.json",
                                ".supervisor.lock",
                                "trainer.log",
                                ".joint.lock",
                            )
                    }
                ) {
                    "A fresh joint output may not contain another run's evidence."
                }
            }
            startedAt = Instant.now()
            progress("initialized", 0)
        }
        immutableJson("collection.json", manifest(input, signature))
        snapshot("source.json", input.source, input.sourceSha256)
        snapshot("baseline.json", input.baseline.path, input.baseline.sha256)
        input.ecology?.snapshot(artifacts)
        input.ecology?.let {
            immutableJson(
                "joint-state-dictionary.json",
                JointStateChronology.from(input).metadata(),
            )
        }
        snapshotBundle("", input.bundle)
        input.opponents.forEachIndexed { index, bundle ->
            snapshotBundle("opponent-$index-", bundle)
        }
        Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().startsWith("game-") }
                .forEach { path ->
                    val name = path.fileName.toString()
                    val index =
                        Regex("game-(0|[1-9][0-9]*)\\.json\\.gz")
                            .matchEntire(name)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    require(index != null && index in input.seeds.indices) {
                        "Unexpected joint game snapshot: $name."
                    }
                }
        }
    }

    /** Validate cached records before reusing their terminal labels or skipping their games. */
    fun completedIndices(): Set<Int> {
        val finished = readIfPresent("finished.json")
        if (finished != null) {
            require(
                finished["signature"]?.asText() == signature &&
                    finished["games"]?.intValue() == input.seeds.size &&
                    finished["gameSha256"]?.map { it.asText() } ==
                        input.seeds.indices.map {
                            PurchaseJson.hash(directory.resolve("game-$it.json.gz"))
                        }
            ) {
                "Completed joint game bytes must match their immutable completion manifest."
            }
        }
        val completed =
            input.seeds.indices.filterTo(mutableSetOf()) { index ->
                val record =
                    CardTrajectoryFiles.readIfPresent(directory.resolve("game-$index.json.gz"))
                        ?: return@filterTo false
                validation.validate(record, index)
                stateSidecars(record, index)
                true
            }
        if (finished != null) {
            sidecarHashes().forEach { (name, hashes) ->
                require(finished[name] == PurchaseJson.mapper.valueToTree<JsonNode>(hashes)) {
                    "Completed joint sidecar bytes must match their immutable completion manifest."
                }
            }
        }
        return completed
    }

    fun save(game: JointGameTrajectory) {
        val document = PurchaseJson.mapper.valueToTree<JsonNode>(game)
        validation.validate(document, game.index)
        val expectedReplay = game.index in input.ecology?.replayIndices.orEmpty()
        require(expectedReplay == (game.canonicalReplay != null)) {
            "Only prospectively declared joint origins retain canonical replay histories."
        }
        CardTrajectoryFiles.save(directory.resolve("game-${game.index}.json.gz"), document)
        stateSidecars(document, game.index)
        game.canonicalReplay?.let { replay ->
            immutableJson(
                "replay-${game.index}.json",
                replayDocument(
                    signature,
                    game.index,
                    PurchaseJson.hash(directory.resolve("game-${game.index}.json.gz")),
                    replay,
                ),
            )
        }
        validateReplay(game.index)
    }

    fun progress(phase: String, completed: Int) {
        artifacts.write(
            "state.json",
            identity +
                mapOf(
                    "startedAt" to startedAt.toString(),
                    "phase" to phase,
                    "games" to input.seeds.size,
                    "completedGames" to completed,
                ),
        )
    }

    fun finish() {
        val totals =
            input.seeds.indices.map { index ->
                val record =
                    requireNotNull(
                        CardTrajectoryFiles.readIfPresent(directory.resolve("game-$index.json.gz"))
                    ) {
                        "A completed collection requires every game."
                    }
                validation.validate(record, index)
                stateSidecars(record, index)
                validateReplay(index)
                val terminal = record.required("terminal")
                Totals(
                    terminal.required("reward").doubleValue(),
                    terminal.required("winShare").doubleValue(),
                    terminal.required("rankReward").doubleValue(),
                    record.required("cardDecisions").size() +
                        record.required("purchaseDecisions").size(),
                )
            }
        val hashes =
            input.seeds.indices.map { PurchaseJson.hash(directory.resolve("game-$it.json.gz")) }
        immutableJson(
            "summary.json",
            identity +
                mapOf(
                    "games" to totals.size,
                    "meanReward" to totals.map(Totals::reward).average(),
                    "meanWinShare" to totals.map(Totals::winShare).average(),
                    "meanRankReward" to totals.map(Totals::rankReward).average(),
                    "controlledDecisions" to totals.sumOf(Totals::decisions),
                ),
        )
        if (input.ecology != null) {
            val coverage =
                input.seeds.indices.map { index ->
                    PurchaseJson.read(directory.resolve("joint-coverage-$index.json"))
                        .required("worldCoverage")
                }
            immutableJson(
                "joint-coverage-summary.json",
                identity +
                    mapOf(
                        "independentWorlds" to coverage.size,
                        "worldCounts" to
                            coverage.first().fieldNames().asSequence().associateWith { name ->
                                coverage.count { it.required(name).booleanValue() }
                            },
                        "interpretation" to
                            "Counts use independent origins; repeated decisions and protection variants are not independent worlds.",
                    ),
            )
        }
        immutableJson(
            "finished.json",
            identity + mapOf("games" to totals.size, "gameSha256" to hashes) + sidecarHashes(),
        )
        progress("complete", totals.size)
    }

    private fun sidecarHashes(): Map<String, Any> {
        if (input.ecology == null) return emptyMap()
        return mapOf(
                "jointStateSha256" to "state",
                "jointLabelSha256" to "label",
                "jointCoverageSha256" to "coverage",
            )
            .mapValues { (_, kind) ->
                input.seeds.indices.map {
                    PurchaseJson.hash(directory.resolve("joint-$kind-$it.json"))
                }
            } +
            mapOf(
                "canonicalReplaySha256" to
                    input.ecology.replayIndices.associate { index ->
                        "replay-$index.json" to
                            PurchaseJson.hash(directory.resolve("replay-$index.json"))
                    },
                "jointCoverageSummarySha256" to
                    PurchaseJson.hash(directory.resolve("joint-coverage-summary.json")),
            )
    }

    private fun stateSidecars(game: JsonNode, index: Int) {
        if (input.ecology == null) return
        val provenance =
            mapOf(
                "version" to JointEcology.VERSION,
                "signature" to signature,
                "originIndex" to index,
                "sourceGameSha256" to PurchaseJson.hash(directory.resolve("game-$index.json.gz")),
                "dictionarySha256" to
                    PurchaseJson.hash(directory.resolve("joint-state-dictionary.json")),
            )
        val rows = JointStateChronology.from(input).project(game)
        immutableJson("joint-state-$index.json", provenance + mapOf("rows" to rows))
        immutableJson(
            "joint-label-$index.json",
            provenance + mapOf("terminal" to game.required("terminal")),
        )
        immutableJson(
            "joint-coverage-$index.json",
            provenance +
                JointExplorationCoverage.project(
                    game,
                    requireNotNull(input.bundle.card) {
                            "The joint learner retains its card actor."
                        }
                        .model,
                ),
        )
    }

    private fun snapshotBundle(prefix: String, bundle: JointActorBundle) {
        snapshot("${prefix}bundle.json", bundle.path, bundle.sha256)
        bundle.card?.let { snapshot("${prefix}card-policy.json", it.path, it.sha256) }
        snapshot("${prefix}purchase-policy.json", bundle.purchase.path, bundle.purchase.sha256)
    }

    private fun immutableJson(name: String, value: Any) {
        val expected = PurchaseJson.mapper.valueToTree<JsonNode>(value)
        val existing = readIfPresent(name)
        require(existing == null || existing == expected) {
            "Existing joint evidence $name differs and will not be overwritten."
        }
        if (existing == null) artifacts.write(name, value)
    }

    private fun readIfPresent(name: String): JsonNode? =
        directory.resolve(name).takeIf { Files.exists(it) }?.let(PurchaseJson::read)

    private fun snapshot(name: String, source: Path, expectedHash: String) {
        val destination = directory.resolve(name)
        if (Files.exists(destination)) {
            require(PurchaseJson.hash(destination) == expectedHash) {
                "Existing joint snapshot $name differs and will not be overwritten."
            }
            return
        }
        val temporary = Files.createTempFile(directory, ".joint-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            require(PurchaseJson.hash(temporary) == expectedHash) {
                "A joint input changed after signature validation: $source."
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateReplay(index: Int) {
        if (index !in input.ecology?.replayIndices.orEmpty()) return
        val replay =
            requireNotNull(readIfPresent("replay-$index.json")) {
                "A declared joint history is required."
            }
        require(
            replay["kind"]?.asText() == "joint-canonical-replay-1" &&
                replay["signature"]?.asText() == signature &&
                replay["index"]?.intValue() == index &&
                replay["trajectorySha256"]?.asText() ==
                    PurchaseJson.hash(directory.resolve("game-$index.json.gz")) &&
                replay["replayVerified"]?.booleanValue() == true &&
                replay["header"]?.isObject == true &&
                replay["entries"]?.isArray == true
        ) {
            "A joint canonical history must bind its exact original trajectory."
        }
    }

    companion object {
        internal fun manifest(input: JointCollectionInput, signature: String): Map<String, Any?> =
            mapOf("kind" to "joint-collect", "signature" to signature) +
                mapOf(
                    "schemaVersion" to 1,
                    "trajectoryEncoding" to CardTrajectoryFiles.ENCODING,
                    "mode" to "on-policy",
                    "rulesVersion" to RULES_VERSION,
                    "dataVersion" to DATA_VERSION,
                    "source" to input.source.toString(),
                    "sourceSha256" to input.sourceSha256,
                    "configuration" to input.configuration.id,
                    "games" to input.seeds.size,
                    "seed" to input.seed,
                    "seeds" to input.seeds,
                    "baseline" to
                        mapOf(
                            "path" to input.baseline.path.toString(),
                            "sha256" to input.baseline.sha256,
                        ),
                    "bundle" to input.bundle.metadata(),
                    "opponents" to input.opponents.map(JointActorBundle::metadata),
                    "controlledSeat" to "index % playerCount",
                    "opponentRotation" to "(index / playerCount + seat) % opponentCount",
                    "reward" to ".8 * winShare + .2 * rankReward",
                ) +
                input.ecology?.metadata().orEmpty()

        internal fun replayDocument(
            signature: String,
            index: Int,
            trajectoryHash: String,
            replay: PurchaseCanonicalReplay,
        ): Map<String, Any> =
            mapOf(
                "kind" to "joint-canonical-replay-1",
                "signature" to signature,
                "index" to index,
                "trajectorySha256" to trajectoryHash,
                "replayVerified" to true,
                "header" to replay.header,
                "entries" to replay.entries,
            )
    }
}
