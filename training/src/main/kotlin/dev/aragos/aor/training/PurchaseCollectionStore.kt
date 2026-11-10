package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Complete-game snapshots are immutable evidence. Only operational state may be replaced; a
 * matching signature is required before restoring missing archives after an interrupted start. The
 * caller holds the directory's exclusive purchase lock for this store's lifetime.
 */
internal class PurchaseCollectionStore(
    private val input: PurchaseCollectionInput,
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
    private val validation = PurchaseTrajectoryValidation(input, signature)
    private val identity = mapOf("kind" to "purchase-collect", "signature" to signature)
    val startedAt: Instant

    init {
        val previous = readIfPresent("state.json")
        if (previous != null) {
            require(
                previous["kind"]?.asText() == "purchase-collect" &&
                    previous["signature"]?.asText() == signature
            ) {
                "Purchase source, code, dictionaries or model bytes changed; use a new output directory."
            }
            require(previous["phase"]?.asText() in setOf("initialized", "collecting", "complete")) {
                "A purchase checkpoint has an unknown operational phase."
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
                                ".purchase.lock",
                            )
                    }
                ) {
                    "A fresh purchase output may not contain another run's evidence."
                }
            }
            startedAt = Instant.now()
            progress("initialized", 0)
        }
        immutableJson("collection.json", manifest())
        snapshot("source.json", input.source, input.sourceSha256)
        snapshot("baseline.json", input.baseline.path, input.baseline.sha256)
        input.sharedPurchaseBaseline?.let {
            snapshot("shared-purchase-baseline.json", it.path, it.sha256)
        }
        input.opponents.forEachIndexed { index, model ->
            snapshot("opponent-$index.json", model.path, model.sha256)
        }
        input.policyPath?.let {
            snapshot(
                "policy.json",
                it,
                requireNotNull(input.modelSha256) {
                    "An on-policy collection requires its model hash."
                },
            )
        }
        input.opponentPurchasePolicy?.let {
            snapshot("opponent-purchase-policy.json", it.path, it.sha256)
        }
        Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().startsWith("game-") }
                .forEach { path ->
                    val name = path.fileName.toString()
                    val index =
                        Regex("game-(0|[1-9][0-9]*)\\.json")
                            .matchEntire(name)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    require(index != null && index in input.seeds.indices) {
                        "Unexpected purchase game snapshot: $name."
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
                            PurchaseJson.hash(directory.resolve("game-$it.json"))
                        }
            ) {
                "Completed purchase game bytes must match their immutable completion manifest."
            }
        }
        return input.seeds.indices.filterTo(mutableSetOf()) { index ->
            val record = readIfPresent("game-$index.json") ?: return@filterTo false
            validation.validate(record, index)
            true
        }
    }

    fun save(game: PurchaseGameTrajectory) {
        val document = PurchaseJson.mapper.valueToTree<JsonNode>(game)
        validation.validate(document, game.index)
        immutableJson("game-${game.index}.json", document)
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
                    requireNotNull(readIfPresent("game-$index.json")) {
                        "A completed collection requires every game."
                    }
                val terminal = record.required("terminal")
                Totals(
                    terminal.required("reward").doubleValue(),
                    terminal.required("winShare").doubleValue(),
                    terminal.required("rankReward").doubleValue(),
                    record.required("decisions").size(),
                )
            }
        val hashes =
            input.seeds.indices.map { PurchaseJson.hash(directory.resolve("game-$it.json")) }
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
        immutableJson(
            "finished.json",
            identity + mapOf("games" to totals.size, "gameSha256" to hashes),
        )
        progress("complete", totals.size)
    }

    private fun manifest(): Map<String, Any?> =
        identity +
            input.initializationMetadata() +
            mapOf(
                "schemaVersion" to 1,
                "mode" to input.mode,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "encoderVersion" to PurchaseObservationEncoder.ENCODER_VERSION,
                "actorVersion" to PurchasePolicy.ACTOR_VERSION,
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
                "opponents" to
                    input.opponents.map {
                        mapOf("path" to it.path.toString(), "sha256" to it.sha256)
                    },
                "policy" to input.policyPath?.toString(),
                "modelSha256" to input.modelSha256,
                "stateFeatureNames" to input.dictionary.stateFeatureNames,
                "actionFeatureNames" to input.dictionary.actionFeatureNames,
                "controlledSeat" to "index % playerCount",
                "opponentRotation" to "(index / playerCount + seat) % opponentCount",
                "reward" to ".8 * winShare + .2 * rankReward",
            ) +
            (input.opponentPurchasePolicy?.let {
                mapOf(
                    "opponentPurchasePolicy" to
                        mapOf(
                            "path" to it.path.toString(),
                            "sha256" to it.sha256,
                        )
                )
            } ?: emptyMap()) +
            (input.sharedPurchaseBaseline?.let {
                mapOf(
                    "sharedPurchaseBaseline" to
                        mapOf("path" to it.path.toString(), "sha256" to it.sha256)
                )
            } ?: emptyMap())

    private fun immutableJson(name: String, value: Any) {
        val expected = PurchaseJson.mapper.valueToTree<JsonNode>(value)
        val existing = readIfPresent(name)
        require(existing == null || existing == expected) {
            "Existing purchase evidence $name differs and will not be overwritten."
        }
        if (existing == null) artifacts.write(name, value)
    }

    private fun readIfPresent(name: String): JsonNode? =
        directory.resolve(name).takeIf { Files.exists(it) }?.let(PurchaseJson::read)

    private fun snapshot(name: String, source: Path, expectedHash: String) {
        val destination = directory.resolve(name)
        if (Files.exists(destination)) {
            require(PurchaseJson.hash(destination) == expectedHash) {
                "Existing purchase snapshot $name differs and will not be overwritten."
            }
            return
        }
        val temporary = Files.createTempFile(directory, ".purchase-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            require(PurchaseJson.hash(temporary) == expectedHash) {
                "A purchase input changed after signature validation: $source."
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
