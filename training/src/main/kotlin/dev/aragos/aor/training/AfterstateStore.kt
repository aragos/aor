package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import java.nio.file.Files
import java.nio.file.Path

/** New-output-only, complete-origin files; one origin at a time is decoded during final checks. */
internal class AfterstateStore(private val input: AfterstateInput, val directory: Path) {
    private val artifacts = TrainingArtifacts(directory)
    private val names = input.names.toSet()

    init {
        Files.list(directory).use { paths ->
            require(paths.findAny().isEmpty) {
                "Afterstate collection requires a new empty output; no resume or overwrite."
            }
        }
        input.pins.entries.forEachIndexed { index, (path, hash) ->
            artifacts.ensureFileSnapshot(path, hash, "input-$index.json")
        }
        artifacts.write("manifest.json", input.metadata())
        artifacts.write("dictionary.json", input.dictionary())
    }

    fun save(game: AfterstateGame) {
        val original = game.original
        val identity =
            mapOf(
                "version" to AfterstateValueEncoder.VERSION,
                "signature" to input.signature,
                "index" to game.index,
                "gameSeed" to original.gameSeed,
                "controlledSeat" to original.controlledSeat,
                "sourceSha256" to input.sourceSha256,
                "dictionarySha256" to PurchaseJson.hash(directory.resolve("dictionary.json")),
            )
        val states =
            identity +
                mapOf(
                    "rows" to game.rows,
                    "exclusions" to game.exclusions,
                    "originalStateParity" to true,
                )
        val statePath = directory.resolve("states-${game.index}.json.gz")
        CardTrajectoryFiles.save(statePath, PurchaseJson.mapper.valueToTree(states))
        artifacts.write(
            "label-${game.index}.json",
            identity +
                mapOf(
                    "statesSha256" to PurchaseJson.hash(statePath),
                    "terminal" to original.terminal,
                ),
        )
        original.canonicalReplay?.let { replay ->
            CardTrajectoryFiles.save(
                directory.resolve("control-${game.index}.json.gz"),
                PurchaseJson.mapper.valueToTree(original),
            )
            artifacts.write(
                "replay-${game.index}.json",
                identity +
                    mapOf(
                        "replayVerified" to true,
                        "header" to replay.header,
                        "entries" to replay.entries,
                    ),
            )
        }
        verify(game.index)
    }

    fun progress(completed: Int) {
        artifacts.write(
            "state.json",
            mapOf(
                "kind" to AfterstateValueEncoder.VERSION,
                "signature" to input.signature,
                "completed" to completed,
                "games" to input.collection.seeds.size,
            ),
        )
    }

    /** Returns only compact counts, never retains a population of parsed game documents. */
    fun verify(index: Int): Map<String, Int> {
        val statePath = directory.resolve("states-$index.json.gz")
        val states =
            requireNotNull(CardTrajectoryFiles.readIfPresent(statePath)) {
                "A complete origin requires its state file."
            }
        val label = PurchaseJson.read(directory.resolve("label-$index.json"))
        for (document in listOf(states, label)) {
            require(
                document.required("signature").asText() == input.signature &&
                    document.required("index").intValue() == index &&
                    document.required("gameSeed").intValue() == input.collection.seeds[index] &&
                    document.required("controlledSeat").intValue() == input.collection.seat(index)
            ) {
                "Afterstate state/label identity differs from its frozen origin."
            }
            require(
                document.required("dictionarySha256").asText() ==
                    PurchaseJson.hash(directory.resolve("dictionary.json"))
            ) {
                "Afterstate rows must bind the exact static dictionary."
            }
        }
        require(label.required("statesSha256").asText() == PurchaseJson.hash(statePath)) {
            "Terminal labels must bind exact compressed state bytes."
        }
        val rows = PurchaseJson.array(states.required("rows"), "afterstate rows")
        val ordering = rows.map {
            PurchaseJson.integer(it.required("number"), "decision") to
                PurchaseJson.integer(it.required("ordinal"), "ordinal")
        }
        require(
            ordering ==
                ordering
                    .distinct()
                    .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }) &&
                ordering.all { it.first >= 0 && it.second in 0..1 }
        ) {
            "Afterstate chronology requires unique before/after absolute decision order."
        }
        val counts = mutableMapOf<String, Int>()
        for (row in rows) {
            val context =
                AfterstateContext.valueOf(PurchaseJson.text(row.required("context"), "context"))
            require(
                (row.required("ordinal").intValue() == 1) ==
                    (context == AfterstateContext.OWNER_EXIT)
            ) {
                "Only a genuine owner-exit boundary may follow an action."
            }
            val features = row.required("stateFeatures")
            require(
                features.isObject &&
                    features.properties().all { (name, value) ->
                        name in names &&
                            value.isNumber &&
                            value.doubleValue().isFinite() &&
                            value.doubleValue() in -16.0..16.0
                    }
            ) {
                "Afterstate features must preserve the finite frozen vocabulary and units."
            }
            require(
                features["${context.prefix}.decision.${context.decision}"]?.doubleValue() == 1.0
            ) {
                "Each row must honestly encode its actual context."
            }
            val exact = row.required("exact").booleanValue()
            val reason = row.required("exactReason").takeUnless(JsonNode::isNull)?.asText()
            require(
                exact == (reason != null) && reason in setOf(null, "OWN_CHAOS", "GAME_TERMINAL")
            ) {
                "Exact value bypass requires an explicit authoritative terminal reason."
            }
            if (reason == "OWN_CHAOS") {
                require(
                    features["purchase.role.0.chaos"]?.doubleValue() == 1.0 &&
                        label.required("terminal").required("reward").doubleValue() == 0.0
                ) {
                    "Own Chaos must retain its authoritative zero reward."
                }
            }
            counts[context.name] = (counts[context.name] ?: 0) + 1
            if (exact) counts["exact"] = (counts["exact"] ?: 0) + 1
        }
        val expectedReplay = index in input.collection.ecology?.replayIndices.orEmpty()
        require(Files.exists(directory.resolve("replay-$index.json")) == expectedReplay) {
            "Only prospectively prescribed origins retain canonical histories."
        }
        require(Files.exists(directory.resolve("control-$index.json.gz")) == expectedReplay) {
            "Only prescribed origins retain full actor-control evidence outside value inputs."
        }
        return counts.toMap()
    }

    fun finish() {
        val coverage = input.collection.seeds.indices.map(::verify)
        input.unchanged()
        val owned =
            input.files.indices.map { "input-$it.json" } +
                listOf("manifest.json", "dictionary.json") +
                input.collection.seeds.indices.flatMap {
                    listOf("states-$it.json.gz", "label-$it.json")
                } +
                input.collection.ecology?.replayIndices.orEmpty().flatMap {
                    listOf("replay-$it.json", "control-$it.json.gz")
                }
        val allowed = owned.toSet() + setOf("state.json", ".DS_Store")
        Files.list(directory).use { paths ->
            require(
                paths.allMatch {
                    it.fileName.toString() in allowed &&
                        Files.isRegularFile(it) &&
                        !Files.isSymbolicLink(it)
                }
            ) {
                "Afterstate output contains undeclared scientific files or links."
            }
        }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to AfterstateValueEncoder.VERSION,
                "signature" to input.signature,
                "games" to input.collection.seeds.size,
                "originalStateParity" to true,
                "coverage" to coverage,
                "artifactSha256" to
                    owned.associateWith { PurchaseJson.hash(directory.resolve(it)) },
            ),
        )
    }
}
